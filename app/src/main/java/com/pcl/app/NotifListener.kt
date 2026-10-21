package com.pcl.app

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

/**
 * Gateway only (it needs user-granted Notification access): mirrors notifications and call
 * notifications with their buttons to the Remote, and follows the Gateway's WhatsApp calls.
 * On the Remote it does nothing; the Remote has no WhatsApp.
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

        // Call-style notifications (Android 12+): Android draws Answer / Decline / Hang up
        // itself from these extras, so those buttons are not in Notification.actions.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3

        fun isCallStyle(n: Notification): Boolean {
            val ex = n.extras ?: return false
            return ex.containsKey(EXTRA_CALL_TYPE) ||
                ex.getString(Notification.EXTRA_TEMPLATE)?.endsWith("\$CallStyle") == true
        }

        private fun pendingIntent(ex: Bundle, key: String): PendingIntent? = try {
            if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(key, PendingIntent::class.java)
            else @Suppress("DEPRECATION") (ex.getParcelable(key) as? PendingIntent)
        } catch (_: Exception) {
            null
        }

        /**
         * The buttons of [n] that PCL can press, call buttons first. For call-style
         * notifications that includes the Answer / Decline / Hang up buttons Android draws.
         */
        fun relaysOf(n: Notification): List<Relay> {
            val list = ArrayList<Relay>()
            val ex = n.extras
            if (ex != null && isCallStyle(n)) {
                pendingIntent(ex, EXTRA_DECLINE_INTENT)?.let { list += Relay("Decline", it) }
                pendingIntent(ex, EXTRA_ANSWER_INTENT)?.let { list += Relay("Answer", it) }
                pendingIntent(ex, EXTRA_HANG_UP_INTENT)?.let { list += Relay("Hang up", it) }
            }
            val system = list.map { it.intent }
            n.actions?.forEach { a ->
                val label = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val intent = a.actionIntent ?: return@forEach
                // Older AndroidX call styles also add the call buttons as ordinary actions.
                if (intent in system) return@forEach
                list += Relay(label.take(40), intent)
            }
            return list
        }

        /** The call state a notification shows, or null when it can't tell. */
        fun callStateOf(n: Notification, relays: List<Relay> = relaysOf(n)): String? =
            when (n.extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0) {
                CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> WhatsAppCalls.RINGING
                CALL_TYPE_ONGOING -> WhatsAppCalls.ACTIVE
                else -> WhatsAppCalls.stateFromActionLabels(relays.map { it.label })
            }
    }

    /** A button PCL can press on the original notification. */
    class Relay(val label: String, val intent: PendingIntent)

    private val lastSent = HashMap<String, String>()
    private val actionsByKey = HashMap<String, List<Relay>>()
    private val actionPackagesByKey = HashMap<String, String>()
    private val whatsappCallStates = HashMap<String, String>()
    private val whatsappCallPackages = HashMap<String, String>()
    /** WhatsApp notifications already logged as "looks like a call, buttons not recognised". */
    private val unrecognisedLogged = HashSet<String>()

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
                unrecognisedLogged.remove(sbn.key)
                wasTracked
            }
            if (changed && report) reportWhatsAppState(sbn.packageName)
            return
        }
        val n = sbn.notification ?: return
        val relays = relaysOf(n)
        // Do not guess when WhatsApp/OEM exposes no recognizable call actions.
        val state = callStateOf(n, relays)
        if (state == null) {
            val looksLikeCall = n.category == Notification.CATEGORY_CALL || n.fullScreenIntent != null
            if (looksLikeCall && synchronized(whatsappCallStates) { unrecognisedLogged.add(sbn.key) }) {
                Hub.log("WhatsApp call notification with unrecognised buttons: " +
                    relays.joinToString { "\"${it.label}\"" }.ifEmpty { "none" } +
                    ". Please report these labels so PCL can learn them.")
            }
            return
        }
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
        if (svc.role != LinkService.ROLE_GATEWAY || sbn.packageName == packageName) return
        val isWhatsApp = WhatsAppCalls.isWhatsApp(sbn.packageName)
        if (isWhatsApp) updateWhatsappCallNotification(sbn, removed = false)
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: return
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: ""

        val relays = relaysOf(n)
        val shown = relays.take(3)
        val indices = shown.indices.toList()
        val labels = shown.map { it.label }
        val callState = callStateOf(n, relays)
        // A WhatsApp call: call-style or call-category notification, one that opens a full-screen
        // call screen, or one with call buttons. "Reply" alone (a message) is not enough.
        val whatsappCall = isWhatsApp && (isCallStyle(n) || n.category == Notification.CATEGORY_CALL ||
            n.fullScreenIntent != null || callState != null)
        val callNotification = whatsappCall || n.category == Notification.CATEGORY_CALL || isCallStyle(n) ||
            (sbn.isOngoing && labels.any { WhatsAppCalls.containsAny(it, GENERIC_CALL_ACTIONS) })

        if (callNotification) {
            // Call-style notifications may carry the caller only as a Person.
            val structuredCaller = callerName(ex)
            if (structuredCaller.isNotBlank()) title = structuredCaller
            if (text.isEmpty()) text = when (callState) {
                WhatsAppCalls.RINGING -> "Incoming call"
                WhatsAppCalls.ACTIVE -> "Call in progress"
                else -> ""
            }
            if (title.isEmpty() && text.isEmpty()) title = appName(sbn.packageName)
        }
        if (title.isEmpty() && text.isEmpty()) return

        if (callNotification) {
            synchronized(actionsByKey) {
                actionsByKey[sbn.key] = relays
                actionPackagesByKey[sbn.key] = sbn.packageName
            }
            // SIM calls already have PCL's own call notification on the Remote. The Phone
            // app's buttons are kept here only as a fallback for answering and ending.
            if (sbn.packageName == defaultDialer()) {
                val person = callerPerson(ex)
                val uriNumber = person?.uri?.takeIf { it.startsWith("tel:", ignoreCase = true) }
                    ?.removePrefix("tel:").orEmpty()
                val candidate = uriNumber.ifEmpty {
                    title.takeIf { it.matches(Regex("[+0-9() .-]{5,30}")) }?.trim().orEmpty()
                }
                val personName = person?.name?.toString().orEmpty()
                val candidateName = personName.ifEmpty {
                    title.takeUnless { it.isBlank() || it.equals("Incoming call", true) ||
                        it.equals("Ongoing call", true) || it.equals("Phone", true) ||
                        it == candidate }.orEmpty()
                }
                svc.updateCallerFromNotification(candidate, candidateName)
                return
            }
            if (!svc.canRelay()) return
            val signature = "CALL\u0000$title\u0000$text\u0000" + labels.joinToString("\u0001")
            synchronized(lastSent) { if (lastSent[sbn.key] == signature) return; lastSent[sbn.key] = signature }
            svc.mirrorCallPosted(sbn.key, sbn.packageName, appName(sbn.packageName), title, text, indices, labels)
            return
        }
        // Calls always go to the Remote; other notifications only while that's switched on.
        if (!svc.canRelay() || !svc.shouldMirror() || sbn.isOngoing) return
        val signature = title + "\u0000" + text
        synchronized(lastSent) { if (lastSent[sbn.key] == signature) return; lastSent[sbn.key] = signature }
        svc.mirrorPosted(sbn.key, appName(sbn.packageName), title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (Hub.service?.role != LinkService.ROLE_GATEWAY) return
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

    /** Invoke only an action supplied by the original app notification. */
    fun performAction(key: String, index: Int): Boolean {
        val action = synchronized(actionsByKey) { actionsByKey[key]?.getOrNull(index) } ?: return false
        return dispatchAction(action)
    }

    private fun defaultDialer(): String? = try {
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    } catch (_: Exception) {
        null
    }

    private fun callerPerson(ex: Bundle): android.app.Person? = try {
        val person = if (Build.VERSION.SDK_INT >= 33) {
            ex.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            ex.getParcelable(Notification.EXTRA_CALL_PERSON) as? android.app.Person
        }
        person
    } catch (_: Exception) {
        null
    }

    private fun callerName(ex: Bundle): String = callerPerson(ex)?.name?.toString().orEmpty()

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
                .flatMap { it.value }
        }
        val matching = actions.firstOrNull { action ->
            val label = action.label
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

    private fun dispatchAction(action: Relay): Boolean {
        val intent = action.intent
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Answering a WhatsApp call may open its call screen. Let PCL's own right to
                // start it apply (it has one while its accessibility helper is on).
                @Suppress("DEPRECATION")
                val options = android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                intent.send(this, 0, null, null, null, null, options)
            } else {
                intent.send()
            }
            Hub.log("Invoked notification action: ${action.label}")
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
