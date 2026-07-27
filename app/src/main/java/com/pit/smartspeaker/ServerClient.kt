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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // Render free tier can be slow to wake up
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
                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    return IntentResult("chat", JSONObject(), "Сервер не ответил, попробуй ещё раз")
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
            IntentResult("chat", JSONObject(), "Не удалось связаться с сервером: ${e.message}")
        }
    }

    fun clearHistory() {
        history.clear()
    }
}
