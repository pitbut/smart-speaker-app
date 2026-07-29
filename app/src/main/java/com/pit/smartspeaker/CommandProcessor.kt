package com.pit.smartspeaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fast local answers for time/date/timer/alarm (no network needed).
 * Everything else goes to the Gemini intent server (ServerClient), which
 * returns a structured action the phone then executes, or a "chat" reply
 * for free-form conversation.
 */
object CommandProcessor {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mqttHelpers = mutableMapOf<Context, MqttHelper>()

    fun process(
        context: Context,
        rawText: String,
        onResponse: (String) -> Unit,
        onLater: (String) -> Unit
    ) {
        val text = rawText.lowercase(Locale("ru"))

        // Local fast paths: timer/alarm need exact device scheduling anyway
        val timeResponse = TimeCommands.tryHandle(context, text) { laterMessage ->
            mainHandler.post { onLater(laterMessage) }
        }
        if (timeResponse != null) {
            onResponse(timeResponse)
            return
        }

        if (text.contains("сколько времени") || text.contains("который час")) {
            val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
            onResponse("Сейчас ${fmt.format(Date())}")
            return
        }
        if (text.contains("какое число") || text.contains("какая дата")) {
            val fmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
            onResponse("Сегодня ${fmt.format(Date())}")
            return
        }

        // Everything else goes to the server for intent recognition
        executor.execute {
            val result = ServerClient.ask(context, rawText)
            handleServerResult(context, result, onResponse)
        }
    }

    private fun handleServerResult(
        context: Context,
        result: ServerClient.IntentResult,
        onResponse: (String) -> Unit
    ) {
        when (result.action) {
            "get_weather" -> {
                val city = result.params.optString("city", "").ifBlank { Prefs.getWeatherCity(context) }
                val weather = WeatherHelper.getWeatherDescription(city)
                mainHandler.post { onResponse(weather) }
            }
            "smart_home" -> {
                val device = result.params.optString("device", "устройство")
                val state = result.params.optString("state", "on")
                val turnOn = state == "on"
                val payload = if (turnOn) "ON" else "OFF"
                val helper = mqttHelpers.getOrPut(context) { MqttHelper(context) }
                helper.publish(payload) { success, error ->
                    val response = when {
                        success -> result.speech ?: if (turnOn) "Включаю $device" else "Выключаю $device"
                        else -> error ?: "Не удалось выполнить команду умного дома"
                    }
                    mainHandler.post { onResponse(response) }
                }
            }
            // "play_youtube" is routed here too: YouTube itself was dropped
            // (unreliable in Uzbekistan), so any video request falls back
            // to the same audio sources as a regular music request.
            "play_music", "play_youtube" -> {
                val query = result.params.optString("query", "")
                executor.execute {
                    val response = MediaController.playMusic(context, query)
                    mainHandler.post { onResponse(response) }
                }
            }
            "stop_media" -> {
                executor.execute {
                    val response = MediaController.stop(context)
                    mainHandler.post { onResponse(response) }
                }
            }
            "get_time" -> {
                val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
                mainHandler.post { onResponse("Сейчас ${fmt.format(Date())}") }
            }
            "get_date" -> {
                val fmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
                mainHandler.post { onResponse("Сегодня ${fmt.format(Date())}") }
            }
            else -> {
                // chat / unknown
                mainHandler.post { onResponse(result.speech ?: "Я пока не знаю такую команду") }
            }
        }
    }
}
