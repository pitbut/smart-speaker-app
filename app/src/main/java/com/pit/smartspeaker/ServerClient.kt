package com.pit.smartspeaker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the Gemini intent-recognition server. Keeps a short rolling
 * history of the conversation so the server can resolve context
 * ("а что насчёт завтра?" after a weather question, etc).
 */
object ServerClient {

    data class IntentResult(
        val action: String,
        val params: JSONObject,
        val speech: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // Render free tier can take a while to wake up
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val MAX_HISTORY_TURNS = 6
    private val history = mutableListOf<Pair<String, String>>() // role to text

    /** Must be called from a background thread — performs blocking network I/O. */
    fun ask(context: android.content.Context, text: String): IntentResult {
        val serverUrl = Prefs.getServerUrl(context).trimEnd('/')
        if (serverUrl.isBlank()) {
            return IntentResult("chat", JSONObject(), "Адрес сервера не настроен в Настройках")
        }

        val historyArray = JSONArray()
        history.takeLast(MAX_HISTORY_TURNS).forEach { (role, t) ->
            historyArray.put(JSONObject().apply {
                put("role", role)
                put("text", t)
            })
        }

        val bodyJson = JSONObject().apply {
            put("text", text)
            put("history", historyArray)
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/process")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    return IntentResult(
                        "chat", JSONObject(),
                        "Сервер ответил ошибкой: код ${response.code}, тело: ${responseBody?.take(200)}"
                    )
                }
                if (responseBody.isNullOrBlank()) {
                    return IntentResult("chat", JSONObject(), "Сервер вернул пустой ответ (код ${response.code})")
                }
                val json = JSONObject(responseBody)
                val action = json.optString("action", "unknown")
                val params = json.optJSONObject("params") ?: JSONObject()
                val speech = if (json.isNull("speech")) null else json.optString("speech", null)

                history.add("user" to text)
                if (speech != null) history.add("assistant" to speech)

                IntentResult(action, params, speech)
            }
        } catch (e: Exception) {
            IntentResult(
                "chat", JSONObject(),
                "Ошибка связи с сервером: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun clearHistory() {
        history.clear()
    }
}
