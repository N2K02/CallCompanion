package com.pcl.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opt-in accessibility helper on the Gateway, shown in Android settings as
 * "PCL WhatsApp call helper". It starts a WhatsApp call the Remote asked for: Android only
 * lets an app open another app from the background through a service like this one. For a
 * number WhatsApp only knows as a chat, it then taps the chat's voice-call button, and
 * confirms WhatsApp's "start call?" question if it asks.
 *
 * It only looks at WhatsApp, and only acts for [CALL_WINDOW_MS] after each request.
 */
class WhatsappCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WhatsappCallAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, WhatsappCallAccessibilityService::class.java)
                .flattenToString()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }

        /** How long PCL helps WhatsApp start a call before giving up. */
        const val CALL_WINDOW_MS = 12_000L
    }

    private var lastCallTapAt = 0L

    @Volatile private var speakerRequest: String? = null
    @Volatile private var speakerDeadline = 0L

    /** Best-effort fallback for a visible dialer call; never taps an unknown control. */
    fun requestSpeakerRoute(route: String): Boolean {
        if (route != "SPEAKER" && route != "EARPIECE") return false
        speakerRequest = route
        speakerDeadline = SystemClock.elapsedRealtime() + 3_000L
        refreshEventFilter()
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        return root != null && trySpeakerRoute(root)
    }

    private fun trySpeakerRoute(root: AccessibilityNodeInfo): Boolean {
        val route = speakerRequest ?: return false
        if (SystemClock.elapsedRealtime() > speakerDeadline) {
            speakerRequest = null
            refreshEventFilter()
            return false
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg !in setOf("com.google.android.dialer", "com.android.dialer")) return false
        val target = findNode(root) { node ->
            val label = labelOf(node).trim().lowercase()
            (label == "speaker" || label == "speakerphone" ||
                label == "turn on speaker" || label == "turn off speaker") &&
                node.isCheckable && (node.isClickable || clickableAncestor(node) != null)
        } ?: return false
        if (target.isChecked == (route == "SPEAKER")) {
            speakerRequest = null
            refreshEventFilter()
            return true
        }
        val clicked = click(target)
        if (clicked) {
            speakerRequest = null
            refreshEventFilter()
            Hub.log("Dialer speaker accessibility action dispatched")
        }
        return clicked
    }


    /** Gateway: a WhatsApp call being started for the Remote. */
    private class CallJob(
        val pkg: String,
        val viaChat: Boolean,
        val digits: String,
        val name: String,
        val until: Long,
    ) {
        var tappedCall = false
    }

    @Volatile
    private var callJob: CallJob? = null

    /**
     * Gateway: opens WhatsApp to call [plan] and, for [CALL_WINDOW_MS], helps it along.
     * [name] is the contact name WhatsApp shows for the number, if the Gateway knows one.
     */
    fun startWhatsAppCall(plan: WhatsAppDialer.Plan, name: String) {
        callJob = CallJob(plan.pkg, plan.viaChat, plan.digits, name, SystemClock.elapsedRealtime() + CALL_WINDOW_MS)
        lastCallTapAt = 0L
        refreshEventFilter()
        startActivity(
            Intent(this, WhatsAppCallActivity::class.java)
                .putExtra(WhatsAppCallActivity.EXTRA_TARGET, plan.intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    /** Gateway: stop helping with the call start (it started, failed or timed out). */
    fun stopWhatsAppCall() {
        if (callJob == null) return
        callJob = null
        refreshEventFilter()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshEventFilter()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** WhatsApp's window changes while a call is being started; just its screen changes otherwise. */
    fun refreshEventFilter() {
        val info = serviceInfo ?: return
        info.eventTypes = if (callJob != null || speakerRequest != null) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        info.packageNames = (WhatsAppCalls.PACKAGES + listOf("com.google.android.dialer", "com.android.dialer")).toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (speakerRequest != null) {
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root != null && trySpeakerRoute(root)) return
        }
        val job = callJob ?: return
        if (!WhatsAppCalls.isWhatsApp(event.packageName?.toString())) return
        if (SystemClock.elapsedRealtime() > job.until) stopWhatsAppCall() else tryAutomateCall(job)
    }

    /**
     * Gateway, while a WhatsApp call is being started: confirms WhatsApp's "start call?"
     * question, and on the chat route taps the voice-call button once. That tap only happens
     * in a chat whose top bar shows this contact's name or number, so a call list or another
     * chat is never touched.
     */
    private fun tryAutomateCall(job: CallJob) {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != job.pkg) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCallTapAt < 1_500L) return

        val confirm = findNode(root) { n ->
            n.isClickable && n.className?.toString()?.contains("Button") == true &&
                WhatsAppCalls.isConfirmCall(labelOf(n))
        }
        if (confirm != null) {
            if (click(confirm)) {
                lastCallTapAt = now
                Hub.log("Confirmed WhatsApp's call question on the Gateway")
            }
            return
        }

        if (!job.viaChat || job.tappedCall) return
        val topBar = resources.displayMetrics.heightPixels / 5
        if (!chatIsFor(root, job, topBar)) return
        val button = findNode(root) { n -> isInTopBar(n, topBar) && WhatsAppCalls.isVoiceCallButton(labelOf(n)) } ?: return
        if (click(button)) {
            job.tappedCall = true
            lastCallTapAt = now
            Hub.log("Tapped WhatsApp's voice-call button on the Gateway")
        }
    }

    /** True when the top bar shows the contact's name or the number being called. */
    private fun chatIsFor(root: AccessibilityNodeInfo, job: CallJob, topBar: Int): Boolean {
        val tail = job.digits.takeLast(7)
        return findNode(root) { n ->
            if (!isInTopBar(n, topBar)) return@findNode false
            val text = n.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@findNode false
            (job.name.isNotBlank() && text.equals(job.name.trim(), ignoreCase = true)) ||
                (tail.length == 7 && text.filter { it.isDigit() }.endsWith(tail))
        } != null
    }

    private fun isInTopBar(node: AccessibilityNodeInfo, topBar: Int): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        return !r.isEmpty && r.bottom <= topBar
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")

    private fun click(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable) node else clickableAncestor(node) ?: return false
        return try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (match(node)) return node
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        repeat(3) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    override fun onInterrupt() {
        // No ongoing interaction to cancel.
    }
}
