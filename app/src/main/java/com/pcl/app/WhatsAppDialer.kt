package com.pcl.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Gateway: works out how to start a WhatsApp voice call to a number the Remote picked.
 *
 * WhatsApp has no public call API, so there are two routes, best first:
 *  1. **Contact row.** For a saved contact that uses WhatsApp, WhatsApp adds a "Voice call"
 *     row to it in Android Contacts. Opening that row starts the call straight away.
 *  2. **Chat.** Otherwise WhatsApp opens the chat with the number, and the accessibility
 *     helper taps the chat's voice-call button ([WhatsappCallAccessibilityService]).
 */
object WhatsAppDialer {

    /** How to start one call. [digits] is the number in international form, without "+". */
    class Plan(val intent: Intent, val pkg: String, val viaChat: Boolean, val digits: String)

    /** Thrown with a message that can be shown to the user as it is. */
    class CannotCall(message: String) : Exception(message)

    private const val DATA_MIME_PREFIX = "vnd.android.cursor.item/vnd.com.whatsapp"
    private const val VOICE_MIME_SUFFIX = ".voip.call"

    fun installedApps(context: Context): List<String> = WhatsAppCalls.PACKAGES.filter { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @Throws(CannotCall::class)
    fun plan(context: Context, number: String): Plan {
        val apps = installedApps(context)
        if (apps.isEmpty()) throw CannotCall("WhatsApp isn't installed on the Gateway.")
        val international = internationalDigits(context, number)

        contactRow(context, number, international, apps)?.let { row ->
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row.id), row.mime)
                .setPackage(row.pkg)
            if (intent.resolveActivity(context.packageManager) != null) {
                return Plan(intent, row.pkg, viaChat = false, digits = row.digits ?: international ?: digitsOf(number))
            }
        }

        val digits = international ?: throw CannotCall(
            "Enter the number with its country code (for example +44…), or pick a saved contact."
        )
        for (pkg in apps) {
            for (link in listOf("whatsapp://send?phone=$digits", "https://wa.me/$digits")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                if (intent.resolveActivity(context.packageManager) != null) {
                    return Plan(intent, pkg, viaChat = true, digits = digits)
                }
            }
        }
        throw CannotCall("WhatsApp on the Gateway didn't accept the number.")
    }

    /**
     * The number in international form without "+" (what WhatsApp uses), or null when the
     * country can't be worked out. Local numbers use the Gateway SIM's country.
     */
    fun internationalDigits(context: Context, number: String): String? {
        val trimmed = number.trim()
        val digits = digitsOf(trimmed)
        if (trimmed.startsWith("+")) return digits.takeIf { it.length in 8..15 }
        if (digits.startsWith("00")) return digits.drop(2).takeIf { it.length in 8..15 }
        val tm = context.getSystemService(TelephonyManager::class.java)
        val country = listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.ROOT) ?: return null
        return PhoneNumberUtils.formatNumberToE164(trimmed, country)?.let(::digitsOf)
    }

    private fun digitsOf(s: String) = s.filter { it.isDigit() }

    private class Row(val id: Long, val mime: String, val pkg: String, val digits: String?)

    /** WhatsApp's own "Voice call" row for this number in the Gateway's contacts, if any. */
    private fun contactRow(context: Context, number: String, international: String?, apps: List<String>): Row? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val resolver = context.contentResolver

        // Contacts Android itself matches to this number, whatever format it was typed in.
        val contactIds = HashSet<Long>()
        try {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            resolver.query(lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                while (c.moveToNext()) contactIds += c.getLong(0)
            }
        } catch (_: Exception) {
        }

        val rows = ArrayList<Row>()
        fun read(withAccountType: Boolean) {
            val projection = mutableListOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.CONTACT_ID,
            )
            if (withAccountType) projection += ContactsContract.RawContacts.ACCOUNT_TYPE
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection.toTypedArray(),
                "${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf("$DATA_MIME_PREFIX%$VOICE_MIME_SUFFIX"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    // DATA1 is the WhatsApp ID: "<international number>@s.whatsapp.net".
                    val jidDigits = c.getString(2)?.substringBefore('@')?.let(::digitsOf)?.takeIf { it.isNotEmpty() }
                    val matches = c.getLong(3) in contactIds || (international != null && jidDigits == international)
                    if (!matches) continue
                    val account = if (withAccountType) c.getString(4) else null
                    val pkg = account?.takeIf { WhatsAppCalls.isWhatsApp(it) }
                        ?: if (mime.contains(".w4b.")) "com.whatsapp.w4b" else "com.whatsapp"
                    rows += Row(c.getLong(0), mime, pkg, jidDigits)
                }
            }
        }
        try {
            read(withAccountType = true)
        } catch (_: Exception) {
            rows.clear()
            try { read(withAccountType = false) } catch (_: Exception) { return null }
        }
        // Prefer WhatsApp over WhatsApp Business, and only apps that are installed.
        return apps.firstNotNullOfOrNull { app -> rows.firstOrNull { it.pkg == app } }
    }
}
