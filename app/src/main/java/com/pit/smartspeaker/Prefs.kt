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

    fun getServerUrl(context: Context): String =
        sp(context).getString("server_url", "https://umnay-kolonka.onrender.com") ?: ""

    fun setServerUrl(context: Context, value: String) {
        sp(context).edit().putString("server_url", value).apply()
    }

    fun getYoutubeApiKey(context: Context): String =
        sp(context).getString("youtube_api_key", "") ?: ""

    fun setYoutubeApiKey(context: Context, value: String) {
        sp(context).edit().putString("youtube_api_key", value).apply()
    }

    fun getSpotifyClientId(context: Context): String =
        sp(context).getString("spotify_client_id", "") ?: ""

    fun setSpotifyClientId(context: Context, value: String) {
        sp(context).edit().putString("spotify_client_id", value).apply()
    }

    fun getSpotifyClientSecret(context: Context): String =
        sp(context).getString("spotify_client_secret", "") ?: ""

    fun setSpotifyClientSecret(context: Context, value: String) {
        sp(context).edit().putString("spotify_client_secret", value).apply()
    }

    fun getJamendoClientId(context: Context): String =
        sp(context).getString("jamendo_client_id", "") ?: ""

    fun setJamendoClientId(context: Context, value: String) {
        sp(context).edit().putString("jamendo_client_id", value).apply()
    }
}
