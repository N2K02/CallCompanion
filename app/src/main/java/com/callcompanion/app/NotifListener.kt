package com.callcompanion.app

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
    }

    private val lastSent = HashMap<String, String>()
    private val actionsByKey = HashMap<String, Array<Notification.Action>>()
    private val actionPackagesByKey = HashMap<String, String>()
    private val whatsappCallStates = HashMap<String, String>()

    private fun isWhatsAppPackage(pkg: String): Boolean =
        pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    private fun reportWhatsAppState() {
        val state = synchronized(whatsappCallStates) {
            when {
                whatsappCallStates.values.any { it == "ACTIVE" } -> "ACTIVE"
                whatsappCallStates.values.any { it == "RINGING" } -> "RINGING"
                else -> "IDLE"
            }
        }
        Hub.service?.updateLocalWhatsappCallState(state)
    }

    private fun updateWhatsappCallNotification(sbn: StatusBarNotification, removed: Boolean) {
        if (!isWhatsAppPackage(sbn.packageName)) return
        if (removed) {
            val changed = synchronized(whatsappCallStates) { whatsappCallStates.remove(sbn.key) != null }
            if (changed) reportWhatsAppState()
            return
        }
        val n = sbn.notification ?: return
        val labels = (n.actions ?: emptyArray()).mapNotNull { it.title?.toString()?.trim()?.lowercase() }
        val hasAnswer = labels.any { label -> listOf("answer", "accept", "pick up", "rispondi", "contestar", "responder", "décrocher", "annehmen", "接听", "接聽", "応答").any(label::contains) }
        val hasDecline = labels.any { label -> listOf("decline", "reject", "ignore", "rifiuta", "rechazar", "refuser", "ablehnen", "拒绝", "拒接", "拒否").any(label::contains) }
        val hasEnd = labels.any { label -> listOf("hang up", "end call", "disconnect", "terminate", "riaggancia", "colgar", "raccrocher", "auflegen", "終了", "切断").any(label::contains) }
        val state = when {
            hasEnd -> "ACTIVE"
            hasAnswer && hasDecline -> "RINGING"
            else -> null // Do not guess when WhatsApp/OEM exposes no recognizable call actions.
        } ?: return
        val changed = synchronized(whatsappCallStates) { whatsappCallStates.put(sbn.key, state) != state }
        if (changed) reportWhatsAppState()
    }

    /** WhatsApp linked devices manage VoIP ringing and answer state themselves. */
    private fun isWhatsAppPackage(pkg: String): Boolean =
        pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    override fun onListenerConnected() {
        instance = this
        Hub.log("Notification access active")
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = Hub.service ?: return
        if (sbn.packageName == packageName) return
        if (isWhatsAppPackage(sbn.packageName)) updateWhatsappCallNotification(sbn, removed = false)
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
        val lowerActions = labels.joinToString(" ").lowercase()
        val whatsappCallAction = isWhatsAppPackage(sbn.packageName) &&
            listOf("answer", "accept", "decline", "reject", "pick up", "rispondi", "contestar", "responder", "décrocher", "annehmen", "rifiuta", "rechazar", "refuser", "ablehnen", "接听", "接聽", "拒绝", "拒接", "応答", "拒否").any { lowerActions.contains(it) }
        val callNotification = n.category == Notification.CATEGORY_CALL || whatsappCallAction ||
            (sbn.isOngoing && listOf("answer", "accept", "decline", "reject", "hang up", "end call").any { lowerActions.contains(it) })

        if (callNotification) {
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
        if (isWhatsAppPackage(sbn.packageName)) updateWhatsappCallNotification(sbn, removed = true)
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

    fun isWhatsAppNotification(key: String): Boolean =
        synchronized(actionPackagesByKey) { isWhatsAppPackage(actionPackagesByKey[key].orEmpty()) }

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
            val label = action.title?.toString()?.trim()?.lowercase().orEmpty()
            when (kind) {
                "answer" -> listOf("answer", "accept", "pick up", "接听", "接聽", "応答", "rispondi", "contestar", "responder", "décrocher", "annehmen").any(label::contains)
                "reject" -> listOf("decline", "reject", "ignore", "拒绝", "拒接", "拒否", "rifiuta", "rechazar", "refuser", "ablehnen").any(label::contains)
                "end" -> listOf("hang up", "end call", "disconnect", "terminate", "end", "終了", "切断", "riaggancia", "colgar", "raccrocher", "auflegen").any(label::contains)
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
