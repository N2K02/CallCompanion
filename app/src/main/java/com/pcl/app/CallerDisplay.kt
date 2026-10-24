package com.pcl.app

import java.util.Locale

/**
 * Remote caller identity presentation shared by live calls, Recents and notifications.
 * A dialer's generic label must never mask an actual phone number.
 */
internal object CallerDisplay {
    private val placeholders = setOf(
        "-", "unknown", "unknown number", "unknown caller", "private number",
        "private caller", "no caller id", "restricted", "withheld", "withheld number",
        "unavailable", "number unavailable", "anonymous", "blocked",
        "incoming call", "ongoing call", "call in progress", "phone",
    )

    private fun clean(value: String): String = value.trim().takeUnless {
        it.lowercase(Locale.ROOT) in placeholders
    }.orEmpty()

    fun name(value: String): String = clean(value)

    fun number(value: String): String = clean(value)

    fun title(name: String, number: String): String =
        name(name).ifEmpty { number(number).ifEmpty { "-" } }

    fun callDisplay(name: String, number: String): String {
        val cleanName = name(name)
        val cleanNumber = number(number)
        return if (cleanName.isNotEmpty() && cleanNumber.isNotEmpty()) {
            "$cleanName ($cleanNumber)"
        } else title(cleanName, cleanNumber)
    }
}
