package com.pcl.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** What the hotspot automation is doing, as shown in the app on both phones. */
data class HotspotStatus(
    /** Stable code sent over Bluetooth, e.g. "ON" or "MANUAL_ON". */
    val code: String,
    /** One plain sentence for the screen. */
    val text: String,
    /** True when the person has to switch the hotspot themselves. */
    val needsYou: Boolean = false,
)

/**
 * Gateway only: turns the Wi-Fi hotspot on for a WhatsApp call handled on the Remote, and
 * off again afterwards. Best effort — Android gives ordinary apps no hotspot API.
 *
 * Rules:
 *  - Changes are debounced, so notification churn on the Remote can't flip the network back and forth.
 *  - Nothing happens when it isn't needed: the hotspot is already on, or the Remote is already on Wi-Fi.
 *  - A tap on a WhatsApp call button only holds the hotspot request for a minute; if no call
 *    state follows (the call was never placed), the request lapses.
 *  - PCL only turns off a hotspot it turned on, and records "PCL turned it on" only once the new
 *    state is confirmed — by Android's hotspot broadcast, or by the Settings switch on phones
 *    that don't send that broadcast.
 *  - Settings is opened only when the optional accessibility helper is on and the Gateway is not
 *    in a phone call. In every other case a notification offers the manual route.
 *
 * All state lives on the main thread; public entry points may be called from any thread
 * (they run immediately on the main thread, and are posted to it from anywhere else).
 */
class HotspotController(
    private val context: Context,
    private val nm: NotificationManager,
    private val channelId: String,
    private val isGatewayInPhoneCall: () -> Boolean,
    private val onStatusChanged: (HotspotStatus) -> Unit,
) {
    companion object {
        private const val NOTIFICATION_ID = 43
        private const val ENABLE_DELAY_MS = 3_000L
        private const val DISABLE_DELAY_MS = 15_000L
        private const val ATTEMPT_TIMEOUT_MS = 20_000L
        private const val RETRY_COOLDOWN_MS = 60_000L
        private const val CALL_BUTTON_TTL_MS = 60_000L

        // Android's hotspot-state broadcast. It isn't in the public SDK, but stock and most
        // OEM builds send it; when it never arrives PCL falls back to reading the Settings switch.
        private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"
        private const val AP_DISABLED = 11
        private const val AP_ENABLED = 13
        private const val AP_FAILED = 14

        private const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

        val READY = HotspotStatus("IDLE", "Waiting for a WhatsApp call on the Remote.")
    }

    private val main = Handler(Looper.getMainLooper())

    /** Hotspot state as reported by Android, or null while unknown. */
    @Volatile var observedOn: Boolean? = null
        private set

    /** An attempt to change the hotspot through Settings is in progress. */
    @Volatile var pending = false
        private set

    /** What the pending attempt is trying to reach. */
    @Volatile var targetOn = true
        private set

    /** Current status for the screen (and for the Remote). */
    @Volatile var status: HotspotStatus = READY
        private set

    private var switchTapped = false
    private var enabledByPcl = false
    private var lastFailureAt = 0L
    private var wanted: Boolean? = null
    private var remoteActive = false
    private var callButtonUntil = 0L
    private var remoteOnWifi = false
    private var registered = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private val debounce = Runnable { wanted?.let { reconcile(it) } }
    private val callButtonExpired = Runnable { evaluate() }
    private val timeout = Runnable { finish(false, "the hotspot switch wasn't found or confirmed in time") }

    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(EXTRA_AP_STATE, -1)) {
                AP_ENABLED -> onObserved(true)
                AP_DISABLED, AP_FAILED -> onObserved(false)
            }
        }
    }

    fun start() {
        onMain {
            if (registered) return@onMain
            val filter = IntentFilter(ACTION_AP_STATE)
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(apReceiver, filter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(apReceiver, filter)
                registered = true
            } catch (e: RuntimeException) {
                Hub.log("Hotspot state monitoring unavailable: ${e.message ?: e.javaClass.simpleName}")
            }
            setStatus(READY)
        }
    }

    fun stop() {
        onMain {
            main.removeCallbacks(debounce)
            main.removeCallbacks(timeout)
            main.removeCallbacks(callButtonExpired)
            if (registered) {
                runCatching { context.unregisterReceiver(apReceiver) }
                registered = false
            }
            if (pending) {
                pending = false
                refreshHelper()
            }
            wanted = null
            remoteActive = false
            callButtonUntil = 0L
            if (enabledByPcl) {
                showNotice(
                    "PCL stopped while its hotspot is on",
                    "PCL turned the Gateway hotspot on for a Remote WhatsApp call and has now stopped, so it won't turn it off. Turn it off in Settings when you no longer need it."
                )
            }
        }
    }

    /**
     * The Remote reports whether one of its WhatsApp calls is active. [remoteHasWifi] is true when
     * the Remote is already on working Wi-Fi, so it doesn't need the hotspot.
     */
    fun onRemoteCallActive(active: Boolean, remoteHasWifi: Boolean) {
        onMain {
            remoteOnWifi = remoteHasWifi
            remoteActive = active
            // A reported call state supersedes an earlier call-button hint.
            callButtonUntil = 0L
            main.removeCallbacks(callButtonExpired)
            evaluate()
        }
    }

    /**
     * The Remote's accessibility helper saw a tap on a WhatsApp call button. That is only a hint:
     * it holds the hotspot request for a minute, and lapses if no call state follows.
     */
    fun onCallButtonTapped(remoteHasWifi: Boolean) {
        onMain {
            remoteOnWifi = remoteHasWifi
            callButtonUntil = SystemClock.elapsedRealtime() + CALL_BUTTON_TTL_MS
            main.removeCallbacks(callButtonExpired)
            main.postDelayed(callButtonExpired, CALL_BUTTON_TTL_MS)
            evaluate()
        }
    }

    /** The Bluetooth link to the Remote dropped. The Remote replays its call state when it reconnects. */
    fun onLinkLost() {
        onMain {
            main.removeCallbacks(debounce)
            main.removeCallbacks(callButtonExpired)
            wanted = null
            remoteActive = false
            callButtonUntil = 0L
            // A scheduled switch was cancelled above, so don't keep showing it.
            if (!enabledByPcl && !pending && status.code == "WAITING") setStatus(READY)
            if (enabledByPcl) {
                setStatus(HotspotStatus("ON", "On. The Remote disconnected; PCL turns the hotspot off once it reconnects with no call."))
                showNotice(
                    "Remote disconnected",
                    "PCL turned the Gateway hotspot on for a Remote WhatsApp call. It will turn it off once the Remote reconnects with no active call. If the Remote doesn't come back, turn the hotspot off in Settings."
                )
            }
        }
    }

    /** The Gateway's own phone call ended: retry a change that was held back so it wouldn't interrupt it. */
    fun onGatewayPhoneIdle() {
        onMain { if (!pending) wanted?.let { reconcile(it) } }
    }

    /** Accessibility helper tapped the hotspot switch. */
    fun onSwitchTapped() {
        onMain {
            if (!pending) return@onMain
            switchTapped = true
            Hub.log("Hotspot switch tapped; waiting for Android to confirm")
        }
    }

    /** Accessibility helper read the Settings switch. Counts as confirmation only when Android hasn't said otherwise. */
    fun onSwitchState(on: Boolean) {
        onMain {
            if (pending && on == targetOn && (observedOn == null || observedOn == on)) {
                finish(true, "the Settings switch shows the hotspot ${if (on) "on" else "off"}")
            }
        }
    }

    fun onAutomationFailed(detail: String) {
        onMain { finish(false, detail) }
    }

    // ---------------------------------------------------------------- internals (main thread)

    private fun setStatus(next: HotspotStatus) {
        if (next == status) return
        status = next
        onStatusChanged(next)
        Hub.notifyUi()
    }

    /** Works out whether the hotspot is wanted now, and schedules the (debounced) change. */
    private fun evaluate() {
        val want = remoteActive || SystemClock.elapsedRealtime() < callButtonUntil
        // Same answer as last time: nothing to do, and no reason to reopen Settings.
        if (want == wanted) return
        wanted = want
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, if (want) ENABLE_DELAY_MS else DISABLE_DELAY_MS)
        if (want && !enabledByPcl && observedOn != true && !remoteOnWifi) {
            setStatus(HotspotStatus("WAITING", "WhatsApp call on the Remote; switching the hotspot on in a few seconds."))
        } else if (!want && (enabledByPcl || pending)) {
            setStatus(HotspotStatus("WAITING", "Remote call ended; switching the hotspot off in about 15 seconds."))
        }
    }

    private fun reconcile(on: Boolean) {
        if (on) {
            if (enabledByPcl || (pending && targetOn)) return
            if (observedOn == true) {
                Hub.log("Gateway hotspot is already on; PCL leaves it as it is")
                setStatus(HotspotStatus("ALREADY_ON", "Already on, so PCL leaves it as it is and won't turn it off."))
                return
            }
            if (remoteOnWifi) {
                Hub.log("Remote is already on Wi-Fi; the Gateway hotspot isn't needed")
                setStatus(HotspotStatus("NOT_NEEDED", "Not needed: the Remote is already on Wi-Fi."))
                return
            }
            if (SystemClock.elapsedRealtime() - lastFailureAt < RETRY_COOLDOWN_MS) {
                Hub.log("Hotspot: the last attempt failed less than a minute ago; not retrying yet")
                setStatus(HotspotStatus("MANUAL_ON", "The last attempt failed a moment ago. Turn the hotspot on in Settings.", needsYou = true))
                return
            }
            begin(true)
        } else {
            if (pending && targetOn) {
                // The call ended while PCL was still turning the hotspot on.
                val tapped = switchTapped
                cancelAttempt()
                when {
                    tapped && observedOn == true -> enabledByPcl = true
                    tapped -> {
                        setStatus(HotspotStatus("CHECK", "The call ended before Android confirmed the hotspot. Check it in Settings.", needsYou = true))
                        showNotice(
                            "Check the Gateway hotspot",
                            "The Remote call ended while PCL was turning the hotspot on, and Android didn't confirm the result. Check the hotspot in Settings."
                        )
                        return
                    }
                    else -> nm.cancel(NOTIFICATION_ID)
                }
            }
            if (!enabledByPcl) {
                // Nothing of PCL's to undo; drop any "turn it on yourself" prompt for the finished call.
                if (status.needsYou && status.code == "MANUAL_ON") nm.cancel(NOTIFICATION_ID)
                if (status.code != "CHECK") setStatus(READY)
                return
            }
            if (observedOn == false) {
                enabledByPcl = false
                nm.cancel(NOTIFICATION_ID)
                Hub.log("Gateway hotspot is already off")
                setStatus(READY)
                return
            }
            begin(false)
        }
    }

    private fun begin(on: Boolean) {
        targetOn = on
        switchTapped = false
        showNotice(
            if (on) "Remote WhatsApp call detected" else "Remote WhatsApp call ended",
            if (on) "PCL is trying to turn on this phone's Wi-Fi hotspot. If it doesn't, tap Open hotspot settings and turn it on, then connect the Remote to this Wi-Fi network."
            else "PCL is trying to turn off the hotspot it turned on. If it doesn't, tap Open hotspot settings and turn it off."
        )
        val manual = if (on) "MANUAL_ON" else "MANUAL_OFF"
        val verb = if (on) "on" else "off"
        if (!WhatsappCallAccessibilityService.isEnabled(context)) {
            Hub.log("Hotspot automation is off (PCL accessibility helper not enabled); use the notification")
            setStatus(HotspotStatus(manual, "Turn the hotspot $verb in Settings. Automatic switching needs the optional PCL call & hotspot helper.", needsYou = true))
            return
        }
        if (isGatewayInPhoneCall()) {
            Hub.log("Gateway is in a phone call; PCL won't open Settings over it. It will retry when the call ends, or use the notification")
            setStatus(HotspotStatus("WAITING", "Waiting for this phone's call to end before switching the hotspot $verb. You can switch it yourself now."))
            return
        }
        pending = true
        refreshHelper()
        try {
            context.startActivity(Intent(TETHER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(timeout, ATTEMPT_TIMEOUT_MS)
            Hub.log(if (on) "Opening hotspot settings to turn the hotspot on" else "Opening hotspot settings to turn off the hotspot PCL turned on")
            setStatus(HotspotStatus(if (on) "SWITCHING_ON" else "SWITCHING_OFF", "Switching the hotspot $verb in Settings…"))
        } catch (e: Exception) {
            cancelAttempt()
            lastFailureAt = SystemClock.elapsedRealtime()
            Hub.log("Could not open hotspot settings: ${e.message ?: e.javaClass.simpleName}; use the notification")
            setStatus(HotspotStatus(manual, "Couldn't open Settings. Turn the hotspot $verb yourself.", needsYou = true))
        }
    }

    private fun onObserved(on: Boolean) {
        val changed = observedOn != on
        observedOn = on
        if (pending && on == targetOn) {
            finish(true, if (on) "Android reports the hotspot is on" else "Android reports the hotspot is off")
        } else if (!pending && !on && enabledByPcl && changed) {
            // Turned off by hand (or by Android): nothing left for PCL to clean up.
            enabledByPcl = false
            nm.cancel(NOTIFICATION_ID)
            Hub.log("Gateway hotspot was turned off")
            setStatus(READY)
        } else if (!pending && changed && status.needsYou && on == (status.code == "MANUAL_ON")) {
            // The person did what the prompt asked.
            nm.cancel(NOTIFICATION_ID)
            setStatus(if (on) HotspotStatus("ALREADY_ON", "On (switched on by hand), so PCL won't turn it off.") else READY)
        }
    }

    private fun finish(success: Boolean, detail: String) {
        if (!pending) return
        cancelAttempt()
        val verb = if (targetOn) "on" else "off"
        if (success) {
            enabledByPcl = targetOn && switchTapped
            nm.cancel(NOTIFICATION_ID)
            Hub.log("Hotspot: $detail")
            Hub.log(
                if (targetOn) "Connect the Remote to the Gateway Wi-Fi if it hasn't joined automatically"
                else "Hotspot off; the Remote should fall back to Bluetooth internet where supported"
            )
            setStatus(
                when {
                    !targetOn -> READY
                    enabledByPcl -> HotspotStatus("ON", "On for the Remote's WhatsApp call. PCL turns it off after the call.")
                    else -> HotspotStatus("ALREADY_ON", "Already on, so PCL leaves it as it is and won't turn it off.")
                }
            )
        } else {
            lastFailureAt = SystemClock.elapsedRealtime()
            Hub.log("Hotspot automation stopped: $detail")
            showNotice(
                "Turn $verb the hotspot manually",
                "PCL couldn't confirm the change. Tap Open hotspot settings to check the hotspot."
            )
            setStatus(HotspotStatus(if (targetOn) "MANUAL_ON" else "MANUAL_OFF", "PCL couldn't confirm the change. Turn the hotspot $verb in Settings.", needsYou = true))
        }
    }

    private fun cancelAttempt() {
        pending = false
        main.removeCallbacks(timeout)
        refreshHelper()
    }

    /** Lets the accessibility helper widen or narrow the events it receives. */
    private fun refreshHelper() {
        WhatsappCallAccessibilityService.instance?.refreshEventFilter()
    }

    private fun showNotice(title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(TETHER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            nm.notify(
                NOTIFICATION_ID, Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, android.R.drawable.sym_action_call),
                            "Open hotspot settings", open
                        ).build()
                    )
                    .build()
            )
        } catch (e: Exception) {
            Hub.log("Could not show hotspot notification: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
