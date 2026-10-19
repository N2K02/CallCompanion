package com.pcl.app

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings. The first time it runs, it moves settings saved under the app's
 * old name ("callcompanion") so existing installs keep their role and phone choice.
 */
object Prefs {
    private const val NAME = "pcl"
    private const val LEGACY_NAME = "callcompanion"
    private const val MIGRATED = "migrated_from_callcompanion"

    /** "gateway" or "remote". */
    const val ROLE = "role"

    /** The other phone: the Gateway on a Remote, and the only allowed Remote on a Gateway. */
    const val PEER_DEVICE = "device"

    /** Gateway: mirror notifications to the Remote. */
    const val MIRROR = "mirror"

    /** True while the user wants the link running, so PCL restarts after a reboot, update or system kill. */
    const val LINK_ENABLED = "link_enabled"

    /** Remote: fingerprint of the contact list last received from the Gateway. */
    const val CONTACTS_HASH = "contacts_hash"

    @Synchronized
    fun of(context: Context): SharedPreferences {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MIGRATED, false)) {
            val legacy = app.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            for ((key, value) in legacy.all) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.putBoolean(MIGRATED, true).commit()
            legacy.edit().clear().commit()
            app.deleteSharedPreferences(LEGACY_NAME)
        }
        return prefs
    }
}
