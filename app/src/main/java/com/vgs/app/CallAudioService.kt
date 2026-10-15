package com.vgs.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper for testing call audio routing. It does not retrieve window content
 * or inspect UI text. Enabling accessibility does not guarantee Android will expose
 * call audio to AudioRecord; microphone-source diagnostics remain important.
 */
class CallAudioService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        Hub.log("Call audio helper is enabled")
    }
}
