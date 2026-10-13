package com.vgs.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
            if (instance != null) return true
            val cn = ComponentName(ctx, NotifListener::class.java)
            if (Build.VERSION.SDK_INT >= 27) {
                try {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    if (nm != null) return nm.isNotificationListenerAccessGranted(cn)
                } catch (e: Exception) {
                }
            }
            // Fallback: the setting may store the long or the short component name.
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(':').any {
                it == cn.flattenToString() || it == cn.flattenToShortString()
            }
        }

        /** Android sometimes unbinds the listener after an app update; ask it to reconnect. */
        fun rebind(ctx: Context) {
            if (instance != null || !isEnabled(ctx)) return
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, NotifListener::class.java))
                Hub.log("Asked Android to reconnect notification access")
            } catch (e: Exception) {
                Hub.log("Notification rebind failed: ${e.message}")
            }
        }
    }

    private val lastSent = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        Hub.log("🔔 Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Hub.log("🔕 Notification listener disconnected")
        rebind(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return // music players, navigation, downloads…
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return // e.g. "3 new messages" bundle
        if (n.category == Notification.CATEGORY_CALL) return // calls are handled separately
        val app = appName(sbn.packageName)
        val svc = Hub.service
        if (svc == null) {
            Hub.log("🔔 $app notification NOT sent: VGS is stopped (press Start)")
            return
        }
        val why = svc.mirrorBlockReason()
        if (why != null) {
            Hub.log("🔔 $app notification NOT sent: $why")
            return
        }

        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ""
        if (title.isEmpty() && text.isEmpty()) {
            Hub.log("🔔 $app notification skipped (no text)")
            return
        }

        val sig = title + "\u0000" + text
        synchronized(lastSent) {
            if (lastSent[sbn.key] == sig) return // same content re-posted, skip
            lastSent[sbn.key] = sig
        }
        svc.mirrorPosted(sbn.key, app, title, text)
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
