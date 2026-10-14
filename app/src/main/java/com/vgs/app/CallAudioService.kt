package com.vgs.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing by itself. Android keeps the microphone available during calls for apps that
 * have an enabled accessibility service, which lets the gateway's voice link hear the caller.
 * It never reads the screen (see res/xml/call_audio_service.xml).
 */
class CallAudioService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        Hub.log("Call audio helper is on")
    }
}
