package com.vgs.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Runs on the GATEWAY phone (needs "Notification access" in Android settings).
 * Every new notification is forwarded to the remote phone over the Bluetooth link.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotifListener? = null

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.contains(ComponentName(ctx, NotifListener::class.java).flattenToString())
        }
    }

    private val lastSent = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        Hub.log("Notification access active")
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = Hub.service ?: return
        if (!svc.shouldMirror()) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return // music players, navigation, downloads…
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_CALL) return // calls are handled separately

        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val sig = title + "\u0000" + text
        synchronized(lastSent) {
            if (lastSent[sbn.key] == sig) return // same content re-posted, skip
            lastSent[sbn.key] = sig
        }
        svc.mirrorPosted(sbn.key, appName(sbn.packageName), title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lastSent) { lastSent.remove(sbn.key) }
        Hub.service?.mirrorRemoved(sbn.key)
    }

    /** Called when the user swipes the copy away on the remote phone. */
    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Hub.log("Could not dismiss: ${e.message}")
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
