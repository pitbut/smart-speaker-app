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
 * Tries, in order: local files -> Spotify -> YouTube Music -> YouTube.
 * Each source is skipped if not configured/installed/found, falling through
 * to the next. Returns a short description of what happened for the reply.
 */
object MediaController {

    private const val TAG = "MediaController"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var localPlayer: MediaPlayer? = null

    /** True while playback is paused because we detected voice, not because the user asked to stop. */
    @Volatile
    private var pausedForInterruption = false

    // ---- Public entry points ----

    /** For a generic "play music" request — tries all sources in priority order. */
    fun playMusic(context: Context, query: String): String {
        if (query.isBlank()) return "Что включить? Не расслышала название"
        pausedForInterruption = false

        playLocalFile(context, query)?.let { return it }
        playViaJamendo(context, query)?.let { return it }
        playViaSpotify(context, query)?.let { return it }
        playViaYoutubeMusic(context, query)?.let { return it }
        playViaYoutube(context, query, videoMode = false)?.let { return it }

        return "Не нашла \"$query\" ни локально, ни в Jamendo/Spotify/YouTube. Проверь Настройки — нужны ключи для поиска"
    }

    /** For an explicit "play this video on YouTube" request. */
    fun playYoutubeVideo(context: Context, query: String): String {
        if (query.isBlank()) return "Что включить? Не расслышала название"
        pausedForInterruption = false
        playViaYoutube(context, query, videoMode = true)?.let { return it }
        return "Не удалось найти видео на YouTube. Проверь ключ YouTube API в Настройках"
    }

    fun stop(context: Context): String {
        pausedForInterruption = false
        localPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // ignore
            }
            localPlayer = null
        }

        val listener = MediaNotificationListener.instance
        val sent = listener?.sendStopToActiveSessions() ?: false

        return if (sent) "Останавливаю" else "Не нашла, что останавливать"
    }

    // ---- Voice-interruption handling (pause while the user is talking, resume after) ----

    /** True if something is audibly playing right now — local player or an external media session. */
    fun isPlaying(): Boolean {
        val localPlaying = localPlayer?.isPlaying == true
        val externalPlaying = MediaNotificationListener.instance?.hasPlayingSession() ?: false
        return localPlaying || externalPlaying
    }

    /**
     * Pauses whatever is playing so the mic has a quiet moment to listen, without losing
     * position. Returns true if it actually paused something. Call [resumeIfInterrupted]
     * afterwards to continue.
     */
    fun pauseForInterruption(): Boolean {
        var paused = false
        localPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.pause()
                    paused = true
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (MediaNotificationListener.instance?.pauseActiveSessions() == true) {
            paused = true
        }
        if (paused) pausedForInterruption = true
        return paused
    }

    /** Resumes playback paused by [pauseForInterruption] — a no-op if nothing was paused that way. */
    fun resumeIfInterrupted() {
        if (!pausedForInterruption) return
        pausedForInterruption = false
        localPlayer?.let {
            try {
                if (!it.isPlaying) it.start()
            } catch (e: Exception) {
                // ignore
            }
        }
        MediaNotificationListener.instance?.resumeActiveSessions()
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

                        localPlayer?.release()
                        localPlayer = MediaPlayer().apply {
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

    // ---- Jamendo (free/CC-licensed catalog, streams directly, no app needed) ----

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
                val audioUrl = track.optString("audio").ifBlank { return null }
                val trackName = track.optString("name", query)
                val artistName = track.optString("artist_name", "")

                localPlayer?.let {
                    try {
                        if (it.isPlaying) it.stop()
                        it.release()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                localPlayer = MediaPlayer().apply {
                    setDataSource(audioUrl)
                    prepare()
                    start()
                }

                return if (artistName.isBlank()) "Включаю $trackName из Jamendo"
                else "Включаю $trackName, $artistName, из Jamendo"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Jamendo playback failed", e)
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

    // ---- YouTube / YouTube Music ----

    private fun playViaYoutubeMusic(context: Context, query: String): String? {
        if (!isPackageInstalled(context, "com.google.android.apps.youtube.music")) return null
        val videoId = searchYoutube(context, query) ?: return null

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$videoId"))
        intent.setPackage("com.google.android.apps.youtube.music")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Включаю в YouTube Music"
        } catch (e: Exception) {
            null
        }
    }

    private fun playViaYoutube(context: Context, query: String, videoMode: Boolean): String? {
        val videoId = searchYoutube(context, query) ?: return null

        val uri = if (isPackageInstalled(context, "com.google.android.youtube")) {
            Uri.parse("vnd.youtube:$videoId")
        } else {
            Uri.parse("https://www.youtube.com/watch?v=$videoId")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (videoMode) "Включаю на YouTube" else "Включаю в YouTube"
        } catch (e: Exception) {
            null
        }
    }

    private fun searchYoutube(context: Context, query: String): String? {
        val apiKey = Prefs.getYoutubeApiKey(context)
        if (apiKey.isBlank()) return null

        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&maxResults=1&q=$encodedQuery&key=$apiKey"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: return null
                if (items.length() == 0) return null
                return items.getJSONObject(0).getJSONObject("id").getString("videoId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search failed", e)
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
