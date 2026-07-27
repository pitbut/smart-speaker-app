package com.pit.smartspeaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class ListenService : Service(), TextToSpeech.OnInitListener, RecognitionListener {

    companion object {
        const val ACTION_STATUS = "com.pit.smartspeaker.STATUS"
        const val ACTION_HEARD = "com.pit.smartspeaker.HEARD"
        const val EXTRA_STATUS = "status"
        const val EXTRA_TEXT = "text"
        const val CHANNEL_ID = "smart_speaker_channel"
        const val NOTIF_ID = 1
        const val TAG = "ListenService"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awaitingCommand = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Загружаю офлайн-модель распознавания..."))
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = false
                broadcastStatus("Не установлен русский голос TTS. Открой Настройки телефона → Text-to-speech → скачай русский язык")
                return
            }
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speechService?.setPause(true)
                }

                override fun onDone(utteranceId: String?) {
                    handler.postDelayed({ speechService?.setPause(false) }, 300)
                }

                override fun onError(utteranceId: String?) {
                    handler.postDelayed({ speechService?.setPause(false) }, 300)
                }
            })
        } else {
            broadcastStatus("Ошибка инициализации синтеза речи (TTS)")
        }
    }

    private fun loadModel() {
        StorageService.unpack(
            this, "model", "model",
            { unpackedModel ->
                model = unpackedModel
                startRecognizer()
            },
            { exception ->
                Log.e(TAG, "Model unpack failed", exception)
                broadcastStatus("Ошибка загрузки модели: ${exception.javaClass.simpleName}: ${exception.message}")
            }
        )
    }

    private fun startRecognizer() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            broadcastStatus("Слушаю кодовое слово: «${Prefs.getWakePhrase(this)}»")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognizer", e)
            broadcastStatus("Ошибка запуска распознавания: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- Vosk RecognitionListener callbacks ----

    override fun onPartialResult(hypothesis: String?) {
        // Not used, but required by the interface
    }

    override fun onResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        if (text.isNotBlank()) {
            handleRecognizedText(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        if (text.isNotBlank()) {
            handleRecognizedText(text)
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Recognition error", exception)
        // Restart listening — transient audio errors shouldn't kill the service
        handler.postDelayed({ restartListening() }, 1000)
    }

    override fun onTimeout() {
        restartListening()
    }

    private fun restartListening() {
        try {
            speechService?.stop()
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e(TAG, "Restart failed", e)
        }
    }

    private fun extractText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try {
            JSONObject(hypothesis).optString("text", "").lowercase(Locale("ru"))
        } catch (e: Exception) {
            ""
        }
    }

    // ---- Command flow ----

    private fun handleRecognizedText(text: String) {
        broadcastHeard(text)
        val wakePhrase = Prefs.getWakePhrase(this)
        val wakeWords = wakePhrase.split(" ").filter { it.isNotBlank() }

        if (!awaitingCommand) {
            if (wakeWords.all { text.contains(it) }) {
                awaitingCommand = true
                broadcastStatus("Слушаю команду...")
                speak("Да?")
            }
        } else {
            awaitingCommand = false
            CommandProcessor.process(
                context = this,
                rawText = text,
                onResponse = { response ->
                    speak(response)
                    broadcastStatus("Слушаю кодовое слово: «$wakePhrase»")
                },
                onLater = { laterMessage ->
                    // Timer/alarm fired while idle - announce proactively
                    speak(laterMessage)
                }
            )
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_id")
        }
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
        })
    }

    private fun broadcastHeard(text: String) {
        sendBroadcast(Intent(ACTION_HEARD).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, text)
        })
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Умная колонка")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Smart Speaker", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
