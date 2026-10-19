package com.pcl.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opt-in accessibility helper:
 * - Remote: observes WhatsApp call-button clicks and relays a hint over PCL Bluetooth.
 * - Gateway: while a hotspot attempt is pending, makes a best-effort attempt to
 *   navigate/toggle the hotspot control in Android Settings. OEM settings layouts vary,
 *   so it never clicks unrelated controls and leaves a manual settings notification.
 */
class WhatsappCallAccessibilityService : AccessibilityService() {

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, WhatsappCallAccessibilityService::class.java)
                .flattenToString()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }

    private var lastTriggerAt = 0L
    private var lastSettingsTapAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val service = Hub.service
        if (service?.role == LinkService.ROLE_GATEWAY && service.isHotspotAutomationPending) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                tryAutomateHotspot(event)
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val node = try { event.source } catch (_: Exception) { null }
        val label = buildString {
            append(event.text.joinToString(" "))
            node?.text?.let { append(" "); append(it) }
            node?.contentDescription?.let { append(" "); append(it) }
        }.trim().lowercase()
        if (label.isBlank()) return

        val isCallAction = listOf(
            "video call", "voice call", "start call", "answer", "accept", "join call",
            "appel vidéo", "appel video", "appel vocal", "répondre", "repondre", "décrocher", "decrocher",
            "llamada de video", "llamada de voz", "responder", "videochiamata", "chiamata vocale",
            "rispondi", "contestar", "接听", "接聽", "応答"
        ).any { label.contains(it) }
        if (!isCallAction) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < 4_000L) return
        lastTriggerAt = now
        Hub.log("WhatsApp call button detected on Remote; requesting Gateway hotspot")
        service?.onWhatsAppCallUiAction("START")
    }

    private fun tryAutomateHotspot(event: AccessibilityEvent) {
        val service = Hub.service ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (service.isHotspotAutomationExpired(now)) {
            service.finishHotspotAutomation(false, "No hotspot switch was found; use the notification to open settings")
            return
        }
        val pkg = event.packageName?.toString().orEmpty()
        // Only interact with Android's Settings UI; never touch WhatsApp or other apps.
        if (!pkg.contains("settings", ignoreCase = true)) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val control = findHotspotControl(root) ?: return

        if (control.isCheckable) {
            if (control.isChecked) {
                service.finishHotspotAutomation(true, "Hotspot was already enabled")
            } else {
                val clicked = try { control.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                if (clicked) {
                    service.finishHotspotAutomation(true, "Requested hotspot activation through Android Settings")
                } else {
                    service.finishHotspotAutomation(false, "Android Settings did not allow the hotspot switch to be tapped")
                }
            }
            return
        }

        // A labelled clickable row usually opens the hotspot subpage. Tap it once,
        // then wait for the next settings event to locate the actual on/off switch.
        if (control.isClickable && now - lastSettingsTapAt > 2_000L) {
            lastSettingsTapAt = now
            try { control.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { }
        }
    }

    private fun findHotspotControl(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = listOfNotNull(
            root.text?.toString(),
            root.contentDescription?.toString()
        ).joinToString(" ").lowercase()
        val mentionsHotspot = listOf(
            "hotspot", "wi-fi hotspot", "wifi hotspot", "mobile hotspot",
            "personal hotspot", "tethering", "point d'accès", "point d’acces",
            "zona wifi", "punto di accesso", "热点"
        ).any { label.contains(it) }

        if (mentionsHotspot) {
            if (root.isCheckable) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val match = findCheckableDescendant(child)
                if (match != null) return match
            }
            if (root.isClickable) return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findHotspotControl(child)
            if (match != null) return match
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
