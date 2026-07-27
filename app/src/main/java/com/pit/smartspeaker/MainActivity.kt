package com.pit.smartspeaker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pit.smartspeaker.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false
    private var testTts: TextToSpeech? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ListenService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(ListenService.EXTRA_STATUS) ?: ""
                    binding.statusText.text = status
                }
                ListenService.ACTION_HEARD -> {
                    val heard = intent.getStringExtra(ListenService.EXTRA_TEXT) ?: ""
                    binding.lastHeardText.text = "Услышано: $heard"
                }
            }
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.installVoiceButton.setOnClickListener {
            try {
                startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this, "Не удалось открыть установку голосов", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.testVoiceButton.setOnClickListener {
            Toast.makeText(this, "Инициализирую TTS...", Toast.LENGTH_SHORT).show()
            testTts = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Toast.makeText(this, "Ошибка инициализации TTS: код $status", Toast.LENGTH_LONG).show()
                    return@TextToSpeech
                }
                val langResult = testTts?.setLanguage(Locale("ru", "RU"))
                Toast.makeText(this, "setLanguage(ru) вернул код: $langResult", Toast.LENGTH_LONG).show()
                testTts?.speak("Проверка звука, раз два три", TextToSpeech.QUEUE_FLUSH, null, "test_id")
            }
        }

        binding.toggleButton.setOnClickListener {
            if (!isRunning) {
                if (hasPermissions()) {
                    startListening()
                } else {
                    requestPermissions()
                }
            } else {
                stopListening()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ListenService.ACTION_STATUS)
            addAction(ListenService.ACTION_HEARD)
        }
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        }
    }

    private fun startListening() {
        val intent = Intent(this, ListenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        binding.toggleButton.text = "Выключить"
        binding.statusText.text = "Слушаю кодовое слово..."
    }

    private fun stopListening() {
        stopService(Intent(this, ListenService::class.java))
        isRunning = false
        binding.toggleButton.text = "Включить"
        binding.statusText.text = "Колонка выключена"
    }
}
