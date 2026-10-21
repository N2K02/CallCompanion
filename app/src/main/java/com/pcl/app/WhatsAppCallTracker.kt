package com.pcl.app

import android.os.Handler
import android.os.SystemClock

/**
 * Remote: follows the Gateway's WhatsApp calls from the call states the Gateway reports
 * (ringing, in a call, idle), one call per WhatsApp app at a time. It decides two things:
 *
 *  - **Whose call it is.** A call answered from this phone, or started from this phone, is
 *    shown here until it ends. A call answered or started on the Gateway itself is the
 *    Gateway's: this phone stops ringing and hides it ([onTakenByGateway]).
 *  - **What goes in Recents** when it ends ([onEnded]): incoming, missed, declined or outgoing.
 *
 * Android may replace WhatsApp's ringing notification with a new one when the call is
 * answered, which can look like "idle, then in a call". So an end is only final after
 * [END_GRACE_MS] without a new state.
 */
class WhatsAppCallTracker(
    private val handler: Handler,
    /** A contact's number by the name WhatsApp shows, or "" when unknown. */
    private val numberFor: (name: String) -> String,
    private val onTakenByGateway: (pkg: String) -> Unit,
    private val onEnded: (CallHistory.Record) -> Unit,
) {
    private class Call(val rang: Boolean, val startedAt: Long) {
        var connectedAt = 0L      // elapsedRealtime when it connected; 0 while ringing
        var endedAt = 0L          // elapsedRealtime when the Gateway reported idle
        var here = false          // answered or started from this phone
        var declinedHere = false
        var name = ""
        var number = ""
    }

    private val calls = HashMap<String, Call>()
    private val answerTappedAt = HashMap<String, Long>()
    private val pendingEnds = HashMap<String, Runnable>()
    private var requestedAt = 0L
    private var requestedNumber = ""
    private var requestedName = ""

    /** This phone asked the Gateway to start a WhatsApp call. */
    @Synchronized
    fun onRequested(number: String, name: String) {
        requestedAt = SystemClock.elapsedRealtime()
        requestedNumber = number
        requestedName = name
    }

    /** Answer or Decline was pressed on this phone for [pkg]'s ringing call. */
    @Synchronized
    fun onButton(pkg: String, label: String) {
        when {
            WhatsAppCalls.isAnswer(label) -> answerTappedAt[pkg] = SystemClock.elapsedRealtime()
            WhatsAppCalls.isDecline(label) -> calls[pkg]?.declinedHere = true
        }
    }

    /** The name WhatsApp shows for the caller, from the Gateway's notification. */
    @Synchronized
    fun onCaller(pkg: String, title: String) {
        val call = calls[pkg] ?: return
        val label = title.trim()
        if (label.isBlank() || label.equals("WhatsApp", true) ||
            label.equals("WhatsApp Business", true) ||
            label.equals("Incoming call", true) ||
            label.equals("Ongoing call", true) ||
            label.equals("Call in progress", true) ||
            label.equals("Unknown", true) ||
            label == call.name || (call.here && call.name.isNotEmpty())) return
        call.name = label
        val digits = label.filter { it.isDigit() }
        call.number = if (digits.length >= 7 && label.all { it.isDigit() || it in "+ -()" }) label
        else numberFor(call.name).ifEmpty { call.number }
    }

    /** False when the Gateway took [pkg]'s call: this phone then shows nothing for it. */
    @Synchronized
    fun shownHere(pkg: String): Boolean {
        val call = calls[pkg] ?: return true
        return call.connectedAt == 0L || call.here
    }

    /** When [pkg]'s call connected (epoch ms) if this phone shows it, for the call timer. */
    @Synchronized
    fun connectedAt(pkg: String): Long? {
        val call = calls[pkg] ?: return null
        if (call.connectedAt == 0L) return null
        return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - call.connectedAt)
    }

    @Synchronized
    fun onState(pkg: String, state: String) {
        val now = SystemClock.elapsedRealtime()
        pendingEnds.remove(pkg)?.let(handler::removeCallbacks)
        val call = calls[pkg]
        when (state) {
            WhatsAppCalls.RINGING -> if (call == null || call.endedAt > 0) {
                // A call that just ended is final once a new one rings.
                if (call != null) finish(pkg)
                calls[pkg] = Call(rang = true, startedAt = System.currentTimeMillis())
                answerTappedAt.remove(pkg)
            }
            WhatsAppCalls.ACTIVE -> {
                val c = call ?: Call(rang = false, startedAt = System.currentTimeMillis()).also { calls[pkg] = it }
                c.endedAt = 0L
                if (c.connectedAt == 0L) {
                    c.connectedAt = now
                    c.here = if (c.rang) now - (answerTappedAt[pkg] ?: 0L) < ANSWER_WINDOW_MS
                    else now - requestedAt < REQUEST_WINDOW_MS
                    if (!c.rang && c.here) {
                        c.number = requestedNumber
                        c.name = requestedName
                    }
                    if (!c.here) onTakenByGateway(pkg)
                }
            }
            WhatsAppCalls.IDLE -> if (call != null && call.endedAt == 0L) {
                call.endedAt = now
                val end = Runnable { finish(pkg) }
                pendingEnds[pkg] = end
                handler.postDelayed(end, END_GRACE_MS)
            }
        }
    }

    /**
     * The link dropped. Only this phone's connected calls are kept, so they show again when the
     * Gateway reports them on reconnecting (or end, if they ended meanwhile).
     */
    @Synchronized
    fun reset() {
        pendingEnds.values.forEach(handler::removeCallbacks)
        pendingEnds.clear()
        calls.entries.removeAll { (_, c) -> !(c.connectedAt > 0 && c.here) }
        calls.values.forEach { it.endedAt = 0L }
        answerTappedAt.clear()
    }

    @Synchronized
    private fun finish(pkg: String) {
        pendingEnds.remove(pkg)
        val call = calls.remove(pkg) ?: return
        if (call.endedAt == 0L) return
        val type = when {
            call.connectedAt > 0 && call.rang -> CallHistory.Type.INCOMING
            call.connectedAt > 0 -> CallHistory.Type.OUTGOING
            call.declinedHere -> CallHistory.Type.DECLINED
            else -> CallHistory.Type.MISSED
        }
        val talk = if (call.connectedAt > 0) (call.endedAt - call.connectedAt) / 1000 else 0
        onEnded(CallHistory.Record(call.number, call.name, type, call.startedAt, talk, whatsapp = true))
    }

    companion object {
        /** Answer pressed here this recently before the call connected: it's this phone's call. */
        private const val ANSWER_WINDOW_MS = 30_000L
        /** A call asked for from here this recently: it's this phone's call. */
        private const val REQUEST_WINDOW_MS = 90_000L
        const val END_GRACE_MS = 2_500L
    }
}
