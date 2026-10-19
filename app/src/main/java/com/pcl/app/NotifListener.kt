package com.pcl.app

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

/**
 * Runs on both phones and requires user-granted Notification Access.
 * Gateway mirrors supported notifications/actions; Remote observes its local WhatsApp call state.
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

        /** Action labels that mark an ongoing non-WhatsApp notification as a call. */
        private val GENERIC_CALL_ACTIONS = listOf("answer", "accept", "decline", "reject", "hang up", "end call")
    }

    private val lastSent = HashMap<String, String>()
    private val actionsByKey = HashMap<String, Array<Notification.Action>>()
    private val actionPackagesByKey = HashMap<String, String>()
    private val whatsappCallStates = HashMap<String, String>()
    private val whatsappCallPackages = HashMap<String, String>()

    private fun reportWhatsAppState(pkg: String) {
        val state = synchronized(whatsappCallStates) {
            val states = whatsappCallStates.filterKeys { whatsappCallPackages[it] == pkg }.values
            when {
                states.any { it == WhatsAppCalls.ACTIVE } -> WhatsAppCalls.ACTIVE
                states.any { it == WhatsAppCalls.RINGING } -> WhatsAppCalls.RINGING
                else -> WhatsAppCalls.IDLE
            }
        }
        // LinkService ignores repeats, so reporting an unchanged state is harmless.
        Hub.service?.updateLocalWhatsappCallState(pkg, state)
    }

    private fun updateWhatsappCallNotification(sbn: StatusBarNotification, removed: Boolean, report: Boolean = true) {
        if (!WhatsAppCalls.isWhatsApp(sbn.packageName)) return
        if (removed) {
            val changed = synchronized(whatsappCallStates) {
                val wasTracked = whatsappCallStates.remove(sbn.key) != null
                whatsappCallPackages.remove(sbn.key)
                wasTracked
            }
            if (changed && report) reportWhatsAppState(sbn.packageName)
            return
        }
        val n = sbn.notification ?: return
        val labels = (n.actions ?: emptyArray()).mapNotNull { it.title?.toString() }
        // Do not guess when WhatsApp/OEM exposes no recognizable call actions.
        val state = WhatsAppCalls.stateFromActionLabels(labels) ?: return
        val changed = synchronized(whatsappCallStates) {
            whatsappCallPackages[sbn.key] = sbn.packageName
            whatsappCallStates.put(sbn.key, state) != state
        }
        if (changed && report) reportWhatsAppState(sbn.packageName)
    }

    override fun onListenerConnected() {
        instance = this
        Hub.log("Notification access active")
        rebuildWhatsAppState()
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        // Keep the last known call state: losing the listener doesn't mean a call ended.
        // It is rebuilt from the live notifications when Android reconnects the listener.
        Hub.log("Notification access disconnected by Android; asking it to reconnect")
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotifListener::class.java))
        } catch (e: Exception) {
            Hub.log("Could not ask Android to reconnect notification access: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Calls can start or end while Android has the listener disconnected, so rebuild the
     * WhatsApp call state from what is showing now instead of trusting the old map.
     */
    private fun rebuildWhatsAppState() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        synchronized(whatsappCallStates) {
            whatsappCallStates.clear()
            whatsappCallPackages.clear()
        }
        active.filter { WhatsAppCalls.isWhatsApp(it.packageName) }
            .forEach { updateWhatsappCallNotification(it, removed = false, report = false) }
        WhatsAppCalls.PACKAGES.forEach(::reportWhatsAppState)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = Hub.service ?: return
        if (sbn.packageName == packageName) return
        val isWhatsApp = WhatsAppCalls.isWhatsApp(sbn.packageName)
        if (isWhatsApp) updateWhatsappCallNotification(sbn, removed = false)
        // Notification access on Remote is used only for local WhatsApp call-state detection.
        if (svc.role == LinkService.ROLE_REMOTE || !svc.shouldMirror()) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val actions = n.actions ?: emptyArray()
        val available = actions.withIndex().filter { it.value.actionIntent != null && !it.value.title.isNullOrBlank() }.take(3)
        val indices = available.map { it.index }
        val labels = available.map { it.value.title.toString().take(40) }
        val whatsappCallAction = isWhatsApp && labels.any { WhatsAppCalls.isAnswer(it) || WhatsAppCalls.isDecline(it) }
        val callNotification = n.category == Notification.CATEGORY_CALL || whatsappCallAction ||
            (sbn.isOngoing && labels.any { WhatsAppCalls.containsAny(it, GENERIC_CALL_ACTIONS) })

        if (callNotification) {
            // Prefer the Remote phone's native WhatsApp notification when its own
            // call-state observer already sees the same app ringing or active.
            // The Gateway copy is only a fallback when Remote has no local call state.
            if (isWhatsApp && svc.shouldSuppressWhatsAppMirror(sbn.packageName)) {
                suppressWhatsAppCallMirrors(sbn.packageName)
                Hub.log("Suppressed duplicate WhatsApp call notification: Remote already reports a native ringing/active call")
                return
            }
            synchronized(actionsByKey) {
                actionsByKey[sbn.key] = actions
                actionPackagesByKey[sbn.key] = sbn.packageName
            }
            val signature = "CALL\u0000$title\u0000$text\u0000" + labels.joinToString("\u0001")
            synchronized(lastSent) { if (lastSent[sbn.key] == signature) return; lastSent[sbn.key] = signature }
            svc.mirrorCallPosted(sbn.key, sbn.packageName, appName(sbn.packageName), title, text, indices, labels)
            return
        }
        if (sbn.isOngoing) return
        val signature = title + "\u0000" + text
        synchronized(lastSent) { if (lastSent[sbn.key] == signature) return; lastSent[sbn.key] = signature }
        svc.mirrorPosted(sbn.key, appName(sbn.packageName), title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (WhatsAppCalls.isWhatsApp(sbn.packageName)) updateWhatsappCallNotification(sbn, removed = true)
        synchronized(lastSent) { lastSent.remove(sbn.key) }
        synchronized(actionsByKey) {
            actionsByKey.remove(sbn.key)
            actionPackagesByKey.remove(sbn.key)
        }
        Hub.service?.mirrorRemoved(sbn.key)
    }

    /** Process notifications already present when the Bluetooth link reconnects. */
    fun syncActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            // Dedupe is useful for repeated posts, but not after a reconnect: the Remote
            // may have missed notifications while disconnected.
            synchronized(lastSent) { active.forEach { lastSent.remove(it.key) } }
            active.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Hub.log("Could not sync active notifications: ${e.message}")
        }
    }

    fun notificationPackage(key: String): String? =
        synchronized(actionsByKey) { actionPackagesByKey[key] }

    /**
     * Remove already mirrored WhatsApp call notifications when Remote's native
     * WhatsApp call is detected. This is deliberately package-level because
     * Android notifications do not reliably identify the signed-in account.
     */
    fun suppressWhatsAppCallMirrors(pkg: String) {
        if (!WhatsAppCalls.isWhatsApp(pkg)) return
        val keys = synchronized(actionsByKey) {
            actionPackagesByKey.filterValues { it == pkg }.keys.toList()
        }
        keys.forEach { key ->
            synchronized(actionsByKey) {
                actionsByKey.remove(key)
                actionPackagesByKey.remove(key)
            }
            synchronized(lastSent) { lastSent.remove(key) }
            Hub.service?.mirrorRemoved(key)
        }
    }

    /** Invoke only an action supplied by the original app notification. */
    fun performAction(key: String, index: Int): Boolean {
        val action = synchronized(actionsByKey) { actionsByKey[key]?.getOrNull(index) } ?: return false
        return dispatchAction(action)
    }

    /** Fallback for OEM Phone apps that expose controls only in their call notification. */
    fun performCallAction(kind: String): Boolean {
        // Never let a third-party VoIP notification accidentally answer or end the SIM
        // call. Restrict this fallback to notifications posted by Android's default dialer.
        val dialerPackage = try {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (_: Exception) { null } ?: return false
        val actions = synchronized(actionsByKey) {
            actionsByKey.entries
                .filter { actionPackagesByKey[it.key] == dialerPackage }
                .flatMap { it.value.asList() }
        }
        val matching = actions.firstOrNull { action ->
            val label = action.title?.toString().orEmpty()
            when (kind) {
                "answer" -> WhatsAppCalls.isAnswer(label)
                "reject" -> WhatsAppCalls.isDecline(label)
                // System dialers often label the button just "End".
                "end" -> WhatsAppCalls.isEnd(label) || WhatsAppCalls.containsAny(label, listOf("end"))
                else -> false
            }
        } ?: return false
        return dispatchAction(matching)
    }

    private fun dispatchAction(action: Notification.Action): Boolean {
        val intent = action.actionIntent ?: return false
        return try {
            intent.send()
            Hub.log("Invoked notification action: ${action.title}")
            true
        } catch (e: PendingIntent.CanceledException) {
            Hub.log("Notification action expired: ${e.message}")
            false
        } catch (e: SecurityException) {
            Hub.log("Notification action not allowed: ${e.message}")
            false
        } catch (e: RuntimeException) {
            Hub.log("Notification action failed: ${e.message}")
            false
        }
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
