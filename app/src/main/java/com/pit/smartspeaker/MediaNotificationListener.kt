package com.pit.smartspeaker

import android.content.ComponentName
import android.media.session.MediaSessionManager
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
}
