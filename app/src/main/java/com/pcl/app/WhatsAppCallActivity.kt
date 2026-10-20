package com.pcl.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Gateway: an invisible step before WhatsApp. It wakes the screen and gets past a lock screen
 * that needs no PIN (swipe, or Smart Lock keeping the phone unlocked), then opens WhatsApp.
 * A PIN, pattern or password can't be skipped; WhatsApp then can't start the call.
 */
class WhatsAppCallActivity : Activity() {

    companion object {
        const val EXTRA_TARGET = "target"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val target = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TARGET)
        }
        if (target == null) {
            finish()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open(target)
                override fun onDismissError() = finish()
                override fun onDismissCancelled() = finish()
            })
        } else {
            // Unlocked: open straight away. Locked with a PIN: try anyway; WhatsApp may still
            // show its call screen over the lock screen.
            open(target)
        }
    }

    private fun open(target: Intent) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Hub.log("Could not open WhatsApp: ${e.message ?: e.javaClass.simpleName}")
        }
        finish()
    }
}
