package com.pcl.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the link after the phone reboots or PCL is updated, if it was running before.
 * Pressing Stop or Quit turns this off until Start is pressed again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "after the phone restarted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "after PCL was updated"
            else -> return
        }
        LinkService.startSaved(context, reason)
    }
}
