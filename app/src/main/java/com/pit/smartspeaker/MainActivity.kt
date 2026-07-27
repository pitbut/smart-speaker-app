package com.pit.smartspeaker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pit.smartspeaker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false

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
