package com.pit.smartspeaker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tries, in order: local files -> Spotify -> Jamendo.
 * (YouTube was dropped — it isn't reliably reachable in Uzbekistan.)
 * Each source is skipped if not configured/installed/found, falling through
 * to the next. Returns a short description of what happened for the reply.
 */
object MediaController {

    private const val TAG = "MediaController"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    // ---- Public entry points ----

    /** For a "play music" request — tries all sources in priority order. */
    fun playMusic(context: Context, query: String): String {
        if (query.isBlank()) return "Что включить? Не расслышала название"

        playLocalFile(context, query)?.let { return it }
        playViaSpotify(context, query)?.let { return it }
        playViaJamendo(context, query)?.let { return it }

        return "Не нашла \"$query\" ни локально, ни в Spotify/Jamendo. Проверь Настройки — нужны ключи для поиска"
    }

    fun stop(context: Context): String {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // ignore
            }
            mediaPlayer = null
        }

        val listener = MediaNotificationListener.instance
        val sent = listener?.sendStopToActiveSessions() ?: false

        return if (sent) "Останавливаю" else "Не нашла, что останавливать"
    }

    // ---- Local files ----

    private fun playLocalFile(context: Context, query: String): String? {
        val words = query.lowercase().split(" ").filter { it.length > 1 }
        if (words.isEmpty()) return null

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleCol)?.lowercase() ?: ""
                    val artist = cursor.getString(artistCol)?.lowercase() ?: ""
                    val haystack = "$title $artist"

                    if (words.any { haystack.contains(it) }) {
                        val id = cursor.getLong(idCol)
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())

                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(context, uri)
                            prepare()
                            start()
                        }
                        return "Включаю $title из локальных файлов"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local file search failed", e)
        }
        return null
    }

    // ---- Spotify ----

    private fun playViaSpotify(context: Context, query: String): String? {
        val clientId = Prefs.getSpotifyClientId(context)
        val clientSecret = Prefs.getSpotifyClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        if (!isPackageInstalled(context, "com.spotify.music")) return null

        try {
            val token = getSpotifyToken(clientId, clientSecret) ?: return null
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/search?q=$encodedQuery&type=track&limit=1")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                val json = JSONObject(body)
                val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return null
                if (items.length() == 0) return null
                val track = items.getJSONObject(0)
                val trackId = track.getString("id")
                val trackName = track.optString("name", query)

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:track:$trackId"))
                intent.setPackage("com.spotify.music")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                return "Включаю $trackName в Spotify"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify playback failed", e)
        }
        return null
    }

    private fun getSpotifyToken(clientId: String, clientSecret: String): String? {
        val credentials = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
        val body = FormBody.Builder().add("grant_type", "client_credentials").build()
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .addHeader("Authorization", "Basic $credentials")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            return JSONObject(responseBody).optString("access_token", null)
        }
    }

    // ---- Jamendo ----
    // Free, legally licensed music catalog with a public streaming API that
    // isn't blocked/restricted the way YouTube can be — no app install
    // required, the track streams straight into the local MediaPlayer.

    private fun playViaJamendo(context: Context, query: String): String? {
        val clientId = Prefs.getJamendoClientId(context)
        if (clientId.isBlank()) return null

        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.jamendo.com/v3.0/tracks/" +
                "?client_id=$clientId&format=json&limit=1&namesearch=$encodedQuery&audioformat=mp32"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val track = results.getJSONObject(0)
                val audioUrl = track.optString("audio", "")
                if (audioUrl.isBlank()) return null
                val name = track.optString("name", query)
                val artist = track.optString("artist_name", "")

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(audioUrl)
                    prepare()
                    start()
                }
                return "Включаю $name${if (artist.isNotBlank()) " — $artist" else ""} (Jamendo)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Jamendo playback failed", e)
        }
        return null
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
