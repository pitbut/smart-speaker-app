package com.pit.smartspeaker

import android.content.Context

object Prefs {
    private const val FILE = "smart_speaker_prefs"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getWakePhrase(context: Context): String =
        sp(context).getString("wake_phrase", "катя слушай") ?: "катя слушай"

    fun setWakePhrase(context: Context, value: String) {
        sp(context).edit().putString("wake_phrase", value).apply()
    }

    fun getWeatherCity(context: Context): String =
        sp(context).getString("weather_city", "Ташкент") ?: "Ташкент"

    fun setWeatherCity(context: Context, value: String) {
        sp(context).edit().putString("weather_city", value).apply()
    }

    fun getMqttBroker(context: Context): String =
        sp(context).getString("mqtt_broker", "") ?: ""

    fun setMqttBroker(context: Context, value: String) {
        sp(context).edit().putString("mqtt_broker", value).apply()
    }

    fun getMqttTopic(context: Context): String =
        sp(context).getString("mqtt_topic", "home/smart-speaker") ?: "home/smart-speaker"

    fun setMqttTopic(context: Context, value: String) {
        sp(context).edit().putString("mqtt_topic", value).apply()
    }

    fun getMqttUser(context: Context): String =
        sp(context).getString("mqtt_user", "") ?: ""

    fun setMqttUser(context: Context, value: String) {
        sp(context).edit().putString("mqtt_user", value).apply()
    }

    fun getMqttPass(context: Context): String =
        sp(context).getString("mqtt_pass", "") ?: ""

    fun setMqttPass(context: Context, value: String) {
        sp(context).edit().putString("mqtt_pass", value).apply()
    }
}
