package com.pcl.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.provider.Settings
import android.content.ComponentName
import android.content.Context

/**
 * Optional, user-enabled accessibility observer on the Remote phone.
 * It only examines click events originating in WhatsApp and sends a lightweight
 * call-start hint over the existing PCL Bluetooth link. It never clicks controls.
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
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

        val action = when {
            listOf("video call", "voice call", "start call", "answer", "accept", "join call")
                .any { label.contains(it) } -> "START"
            else -> return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < 4_000L) return
        lastTriggerAt = now
        Hub.log("WhatsApp call button detected on Remote; requesting Gateway Wi-Fi hotspot prompt")
        Hub.service?.onWhatsAppCallUiAction(action)
    }

    override fun onInterrupt() {
        // No ongoing interaction to cancel.
    }
}
