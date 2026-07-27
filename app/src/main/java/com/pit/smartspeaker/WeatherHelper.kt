package com.pit.smartspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Fetches current weather via Open-Meteo (free, no API key).
 * Must be called from a background thread — performs blocking network I/O.
 */
object WeatherHelper {

    private val client = OkHttpClient()

    fun getWeatherDescription(city: String): String {
        return try {
            val (lat, lon) = geocode(city) ?: return "Не могу найти город $city"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Не удалось получить погоду"
                val body = response.body?.string() ?: return "Не удалось получить погоду"
                val json = JSONObject(body)
                val current = json.getJSONObject("current_weather")
                val temp = current.getDouble("temperature").roundToInt()
                val wind = current.getDouble("windspeed").roundToInt()
                "В городе $city сейчас $temp градусов, ветер $wind километров в час"
            }
        } catch (e: Exception) {
            "Не удалось получить погоду: ${e.message}"
        }
    }

    private fun geocode(city: String): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ru"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.getJSONObject(0)
            return Pair(first.getDouble("latitude"), first.getDouble("longitude"))
        }
    }
}
