package com.pit.smartspeaker

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Required by Android to gain access to MediaSessionManager.getActiveSessions().
 * We don't actually care about notifications — this service exists purely to
 * satisfy the permission requirement for universal media control (the same
 * mechanism headset play/pause buttons use), so voice commands like "останови"
 * can pause/stop whatever app is currently playing audio (YouTube, Spotify,
 * YouTube Music, etc) without needing each app's own API.
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Not used
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not used
    }

    fun sendStopToActiveSessions(): Boolean {
        return try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val sessions = manager.getActiveSessions(component)
            var sentAny = false
            for (controller in sessions) {
                controller.transportControls.pause()
                controller.transportControls.stop()
                sentAny = true
            }
            sentAny
        } catch (e: Exception) {
            false
        }
    }

    fun hasActiveSessions(): Boolean {
        return try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            manager.getActiveSessions(component).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** True if any external session (Spotify, YouTube, ...) is actively playing right now. */
    fun hasPlayingSession(): Boolean {
        return try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            manager.getActiveSessions(component).any {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Pauses (not stops) every session that's currently playing. Returns true if any were paused. */
    fun pauseActiveSessions(): Boolean {
        return try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            var paused = false
            manager.getActiveSessions(component).forEach { controller ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                    paused = true
                }
            }
            paused
        } catch (e: Exception) {
            false
        }
    }

    /** Resumes every session that supports it — a no-op for ones already playing or stopped for good. */
    fun resumeActiveSessions() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            manager.getActiveSessions(component).forEach { it.transportControls.play() }
        } catch (e: Exception) {
            // ignore
        }
    }
}
