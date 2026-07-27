package com.pit.smartspeaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Rule-based command handler. Commands that need network or hardware I/O
 * run on a background thread; the response always comes back via onResponse
 * on the main thread so the caller can safely trigger TTS / UI updates.
 * onLater is invoked separately for delayed events (timer/alarm firing).
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

        val timeResponse = TimeCommands.tryHandle(context, text) { laterMessage ->
            mainHandler.post { onLater(laterMessage) }
        }
        if (timeResponse != null) {
            onResponse(timeResponse)
            return
        }

        when {
            text.contains("погод") -> {
                val city = Prefs.getWeatherCity(context)
                executor.execute {
                    val result = WeatherHelper.getWeatherDescription(city)
                    mainHandler.post { onResponse(result) }
                }
            }
            text.contains("включи") || text.contains("выключи") -> {
                val turnOn = text.contains("включи")
                val payload = if (turnOn) "ON" else "OFF"
                val helper = mqttHelpers.getOrPut(context) { MqttHelper(context) }
                executor.execute {
                    helper.publish(payload) { success, error ->
                        val response = when {
                            success -> if (turnOn) "Включаю" else "Выключаю"
                            else -> error ?: "Не удалось выполнить команду умного дома"
                        }
                        mainHandler.post { onResponse(response) }
                    }
                }
            }
            text.contains("время") || text.contains("час") -> {
                val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
                onResponse("Сейчас ${fmt.format(Date())}")
            }
            text.contains("дата") || text.contains("число") -> {
                val fmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
                onResponse("Сегодня ${fmt.format(Date())}")
            }
            text.contains("привет") -> onResponse("Привет! Чем могу помочь?")
            text.contains("как дела") -> onResponse("Всё отлично, готова помогать!")
            text.contains("спасибо") -> onResponse("Пожалуйста!")
            else -> onResponse("Я пока не знаю такую команду")
        }
    }
}
