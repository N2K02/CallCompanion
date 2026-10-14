package com.vgs.app

import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android calls this on the gateway when the paired remote phone appears nearby (even after a
 * reboot), so the gateway can start by itself. Set up with "Start with the remote" on the setup page.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RemotePresenceService : CompanionDeviceService() {
    @Deprecated("Android 13+ also calls the AssociationInfo version, which forwards here")
    override fun onDeviceAppeared(address: String) {
        val prefs = getSharedPreferences("vgs", MODE_PRIVATE)
        if (prefs.getString("role", null) != LinkService.ROLE_GATEWAY) return
        if (!prefs.getBoolean("auto_start", false)) return
        if (Hub.service?.isActive == true) return
        Hub.log("Remote phone is nearby – starting the gateway")
        try {
            startForegroundService(
                Intent(this, LinkService::class.java)
                    .setAction(LinkService.ACTION_START)
                    .putExtra(LinkService.EXTRA_ROLE, LinkService.ROLE_GATEWAY)
                    .putExtra(LinkService.EXTRA_BACKGROUND, true)
            )
        } catch (e: Exception) {
            Hub.log("Couldn't start the gateway from the background: ${e.message}")
        }
    }

    @Deprecated("Replaced by the AssociationInfo version on Android 13+")
    override fun onDeviceDisappeared(address: String) {}
}
