package com.pcl.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opt-in accessibility helper, shown in Android settings as "PCL call & hotspot helper":
 * - Remote: notices taps on WhatsApp call buttons and tells the Gateway over Bluetooth.
 * - Gateway: while a hotspot change is pending, tries to find and tap the Wi-Fi hotspot
 *   switch in Android Settings. OEM layouts vary, so a notification is always left as the
 *   manual route.
 *
 * To keep its footprint small it only receives WhatsApp taps, and widens to Settings
 * screens only while a hotspot change is actually in progress.
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

        /** Labels of the Wi-Fi hotspot row or switch. */
        private val HOTSPOT_WORDS = listOf(
            "hotspot", "point d'accès", "point d’accès", "point d’acces", "zona wifi",
            "punto di accesso", "punto de acceso", "热点"
        )

        /** Labels of a page that leads to the hotspot ("Tethering"). Opened, never toggled. */
        private val TETHERING_WORDS = listOf("tethering", "partage de connexion")

        /** Other tethering switches that must never be touched: turning off Bluetooth
         *  tethering would cut the Remote's standby internet route. */
        private val OTHER_TETHERING_WORDS = listOf("usb", "bluetooth", "ethernet")
    }

    private var lastTriggerAt = 0L
    private var lastSettingsTapAt = 0L
    private var lastToggleTapAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshEventFilter()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Receive WhatsApp taps only, plus Settings screens while a hotspot change is pending. */
    fun refreshEventFilter() {
        val info = serviceInfo ?: return
        val service = Hub.service
        if (service?.role == LinkService.ROLE_GATEWAY && service.hotspot.pending) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            info.packageNames = null // Settings' package name differs between manufacturers.
        } else {
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
            info.packageNames = WhatsAppCalls.PACKAGES.toTypedArray()
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val service = Hub.service
        if (service?.role == LinkService.ROLE_GATEWAY && service.hotspot.pending) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                tryAutomateHotspot(event, service.hotspot)
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString() ?: return
        if (!WhatsAppCalls.isWhatsApp(pkg)) return

        val node = try { event.source } catch (_: Exception) { null }
        val label = buildString {
            append(event.text.joinToString(" "))
            node?.text?.let { append(" "); append(it) }
            node?.contentDescription?.let { append(" "); append(it) }
        }
        if (!WhatsAppCalls.isCallButton(label)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < 4_000L) return
        lastTriggerAt = now
        Hub.log("WhatsApp call button detected on Remote; requesting Gateway hotspot")
        service?.onWhatsAppCallUiAction("START")
    }

    private fun tryAutomateHotspot(event: AccessibilityEvent, hotspot: HotspotController) {
        val pkg = event.packageName?.toString().orEmpty()
        // Only interact with Android's Settings UI; never touch WhatsApp or other apps.
        if (!pkg.contains("settings", ignoreCase = true)) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val control = findHotspotControl(root) ?: return
        val now = SystemClock.elapsedRealtime()

        if (control.isCheckable) {
            if (control.isChecked == hotspot.targetOn) {
                hotspot.onSwitchState(control.isChecked)
            } else if (now - lastToggleTapAt >= 2_000L) {
                lastToggleTapAt = now
                // In many Settings rows the switch itself isn't clickable; its row is.
                val target = if (control.isClickable) control else clickableAncestor(control) ?: control
                val clicked = try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                if (clicked) hotspot.onSwitchTapped()
                else hotspot.onAutomationFailed("Android Settings did not allow the hotspot switch to be tapped")
            }
            return
        }

        // A labelled row usually opens the hotspot page. Tap it, then wait for the next
        // Settings event to find the actual on/off switch.
        if (control.isClickable && now - lastSettingsTapAt > 2_000L) {
            lastSettingsTapAt = now
            try { control.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { }
        }
    }

    /**
     * Finds the Wi-Fi hotspot switch, or a row that leads to it. USB, Bluetooth and Ethernet
     * tethering are skipped entirely, and a plain "Tethering" row is only ever opened.
     */
    private fun findHotspotControl(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").lowercase()
        if (label.isNotBlank()) {
            if (OTHER_TETHERING_WORDS.any(label::contains)) return null
            if (HOTSPOT_WORDS.any(label::contains)) {
                if (node.isCheckable) return node
                // The label is often a plain text view inside a clickable row. A label with no
                // clickable row nearby (such as the page title) is not a control.
                val row = if (node.isClickable) node else clickableAncestor(node)
                if (row != null) return findCheckableDescendant(row) ?: row
            } else if (TETHERING_WORDS.any(label::contains)) {
                val row = if (node.isClickable) node else clickableAncestor(node)
                if (row != null && !row.isCheckable && findCheckableDescendant(row) == null) return row
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findHotspotControl(child)
            if (match != null) return match
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

    private fun findCheckableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isCheckable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findCheckableDescendant(child)
            if (match != null) return match
        }
        return null
    }

    override fun onInterrupt() {
        // No ongoing interaction to cancel.
    }
}
