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

/**
 * Gateway only: turns the Wi-Fi hotspot on for a WhatsApp call handled on the Remote, and
 * off again afterwards. Best effort — Android gives ordinary apps no hotspot API.
 *
 * Rules:
 *  - Changes are debounced, so notification churn on the Remote can't flip the network back and forth.
 *  - Nothing happens when it isn't needed: the hotspot is already on, or the Remote is already on Wi-Fi.
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
) {
    companion object {
        private const val NOTIFICATION_ID = 43
        private const val ENABLE_DELAY_MS = 3_000L
        private const val DISABLE_DELAY_MS = 15_000L
        private const val ATTEMPT_TIMEOUT_MS = 20_000L
        private const val RETRY_COOLDOWN_MS = 60_000L

        // Android's hotspot-state broadcast. It isn't in the public SDK, but stock and most
        // OEM builds send it; when it never arrives PCL falls back to reading the Settings switch.
        private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"
        private const val AP_DISABLED = 11
        private const val AP_ENABLED = 13
        private const val AP_FAILED = 14

        private const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
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

    private var switchTapped = false
    private var enabledByPcl = false
    private var lastFailureAt = 0L
    private var wanted: Boolean? = null
    private var remoteOnWifi = false
    private var registered = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private val debounce = Runnable { wanted?.let { reconcile(it) } }
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
        }
    }

    fun stop() {
        onMain {
            main.removeCallbacks(debounce)
            main.removeCallbacks(timeout)
            if (registered) {
                runCatching { context.unregisterReceiver(apReceiver) }
                registered = false
            }
            if (pending) {
                pending = false
                refreshHelper()
            }
            wanted = null
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
            // Same answer as last time: nothing to do, and no reason to reopen Settings.
            if (wanted == active) return@onMain
            wanted = active
            main.removeCallbacks(debounce)
            main.postDelayed(debounce, if (active) ENABLE_DELAY_MS else DISABLE_DELAY_MS)
        }
    }

    /** The Bluetooth link to the Remote dropped. The Remote replays its call state when it reconnects. */
    fun onLinkLost() {
        onMain {
            main.removeCallbacks(debounce)
            wanted = null
            if (enabledByPcl) {
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

    private fun reconcile(on: Boolean) {
        if (on) {
            if (enabledByPcl || (pending && targetOn)) return
            if (observedOn == true) {
                Hub.log("Gateway hotspot is already on; PCL leaves it as it is")
                return
            }
            if (remoteOnWifi) {
                Hub.log("Remote is already on Wi-Fi; the Gateway hotspot isn't needed")
                return
            }
            if (SystemClock.elapsedRealtime() - lastFailureAt < RETRY_COOLDOWN_MS) {
                Hub.log("Hotspot: the last attempt failed less than a minute ago; not retrying yet")
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
                    tapped -> showNotice(
                        "Check the Gateway hotspot",
                        "The Remote call ended while PCL was turning the hotspot on, and Android didn't confirm the result. Check the hotspot in Settings."
                    )
                    else -> nm.cancel(NOTIFICATION_ID)
                }
            }
            if (!enabledByPcl) return
            if (observedOn == false) {
                enabledByPcl = false
                nm.cancel(NOTIFICATION_ID)
                Hub.log("Gateway hotspot is already off")
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
        if (!WhatsappCallAccessibilityService.isEnabled(context)) {
            Hub.log("Hotspot automation is off (PCL accessibility helper not enabled); use the notification")
            return
        }
        if (isGatewayInPhoneCall()) {
            Hub.log("Gateway is in a phone call; PCL won't open Settings over it. It will retry when the call ends, or use the notification")
            return
        }
        pending = true
        refreshHelper()
        try {
            context.startActivity(Intent(TETHER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(timeout, ATTEMPT_TIMEOUT_MS)
            Hub.log(if (on) "Opening hotspot settings to turn the hotspot on" else "Opening hotspot settings to turn off the hotspot PCL turned on")
        } catch (e: Exception) {
            cancelAttempt()
            lastFailureAt = SystemClock.elapsedRealtime()
            Hub.log("Could not open hotspot settings: ${e.message ?: e.javaClass.simpleName}; use the notification")
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
        }
    }

    private fun finish(success: Boolean, detail: String) {
        if (!pending) return
        cancelAttempt()
        if (success) {
            enabledByPcl = targetOn && switchTapped
            nm.cancel(NOTIFICATION_ID)
            Hub.log("Hotspot: $detail")
            Hub.log(
                if (targetOn) "Connect the Remote to the Gateway Wi-Fi if it hasn't joined automatically"
                else "Hotspot off; the Remote should fall back to Bluetooth internet where supported"
            )
        } else {
            lastFailureAt = SystemClock.elapsedRealtime()
            Hub.log("Hotspot automation stopped: $detail")
            showNotice(
                if (targetOn) "Turn on the hotspot manually" else "Turn off the hotspot manually",
                "PCL couldn't confirm the change. Tap Open hotspot settings to check the hotspot."
            )
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
