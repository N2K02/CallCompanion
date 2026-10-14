package com.vgs.app

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.WindowManager

/** A WhatsApp call seen on the GATEWAY through its call notification. */
class AppCall(
    val key: String,
    val app: String,
    val ringing: Boolean,
    val name: String,
    val answer: PendingIntent?,
    val decline: PendingIntent?,
    val hangUp: PendingIntent?,
    val buttons: String,
) {
    /** True when WhatsApp says it is a video call (the remote only does the voice part). */
    var video: Boolean = false
}

/**
 * WhatsApp calls on the gateway. WhatsApp has no calling API, so:
 *  - incoming / ongoing calls are read from WhatsApp's call notification, and its
 *    Answer / Decline / Hang up buttons are pressed for the remote;
 *  - outgoing calls are started through the "WhatsApp voice call" entry that WhatsApp
 *    adds to each contact in the phone's contacts.
 */
object AppCalls {
    private val APPS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
    )
    private val VOIP_MIME = mapOf(
        "vnd.android.cursor.item/vnd.com.whatsapp.voip.call" to "com.whatsapp",
        "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call" to "com.whatsapp.w4b",
    )

    // Notification.CallStyle extras (Android 12+), read by name so older SDKs work too.
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val EXTRA_CALL_PERSON = "android.callPerson"
    private const val EXTRA_ANSWER = "android.answerIntent"
    private const val EXTRA_DECLINE = "android.declineIntent"
    private const val EXTRA_HANG_UP = "android.hangUpIntent"

    // Button titles in a few languages, lower case.
    private val ANSWER_WORDS = listOf(
        "answer", "accept", "opnemen", "beantwoord", "aannemen", "annehmen", "répondre", "accepter",
        "contestar", "responder", "atender", "aceptar", "rispondi", "accetta"
    )
    private val DECLINE_WORDS = listOf(
        "decline", "reject", "ignore", "weiger", "negeer", "ablehnen", "refuser", "rechazar",
        "recusar", "rifiuta"
    )
    private val HANG_UP_WORDS = listOf(
        "hang up", "end", "ophangen", "beëindig", "auflegen", "beenden", "raccrocher", "colgar",
        "finalizar", "desligar", "termina", "chiudi"
    )

    fun isCall(sbn: StatusBarNotification): Boolean =
        sbn.packageName in APPS && sbn.notification?.category == Notification.CATEGORY_CALL

    @Suppress("DEPRECATION")
    fun parse(sbn: StatusBarNotification): AppCall? {
        val n = sbn.notification ?: return null
        val ex = n.extras
        val actions = n.actions ?: emptyArray()
        val titles = actions.map { it.title?.toString() ?: "" }

        fun byWord(words: List<String>): PendingIntent? =
            actions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase() ?: ""
                words.any { t.contains(it) }
            }?.actionIntent

        fun extraPi(k: String): PendingIntent? = ex?.get(k) as? PendingIntent

        var answer = extraPi(EXTRA_ANSWER) ?: byWord(ANSWER_WORDS)
        var decline = extraPi(EXTRA_DECLINE) ?: byWord(DECLINE_WORDS)
        val hangUp = extraPi(EXTRA_HANG_UP) ?: byWord(HANG_UP_WORDS)

        val ringing = when (ex?.getInt(EXTRA_CALL_TYPE, 0) ?: 0) {
            1, 3 -> true // incoming, screening
            2 -> false // ongoing
            else -> answer != null ||
                (!n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) &&
                    n.fullScreenIntent != null && actions.size >= 2)
        }
        // Unknown language: WhatsApp shows [Decline, Answer] while ringing.
        if (ringing && actions.size >= 2) {
            if (decline == null) decline = actions.first().actionIntent
            if (answer == null) answer = actions.last().actionIntent
        }
        val ongoingHangUp = hangUp ?: if (!ringing && actions.size == 1) actions[0].actionIntent else null

        val person = if (Build.VERSION.SDK_INT >= 28) (ex?.get(EXTRA_CALL_PERSON) as? Person)?.name else null
        val name = person?.toString()?.takeIf { it.isNotBlank() }
            ?: ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: ""
        // The notification text says "Incoming video call" / "voice call" (not the title: that is the contact's name).
        val blob = listOfNotNull(
            ex?.getCharSequence(Notification.EXTRA_TEXT),
            ex?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ex?.getCharSequence(Notification.EXTRA_SUB_TEXT),
            n.tickerText,
        ).joinToString(" ") + " " + titles.joinToString(" ")
        val video = Regex("vid[eé]o", RegexOption.IGNORE_CASE).containsMatchIn(blob)
        return AppCall(
            key = sbn.key,
            app = APPS[sbn.packageName] ?: sbn.packageName,
            ringing = ringing,
            name = name,
            answer = answer,
            decline = decline,
            hangUp = ongoingHangUp,
            buttons = titles.joinToString(", ").ifEmpty { "none" },
        ).also { it.video = video }
    }

    /** Presses a notification button. Returns false if it no longer works. */
    @Suppress("DEPRECATION")
    fun press(ctx: Context, pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val o = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, o.toBundle())
        } else {
            pi.send()
        }
        true
    } catch (e: Exception) {
        Hub.log("Button press failed: ${e.message}")
        false
    }

    /** Can VGS open WhatsApp's call screen while it runs in the background? */
    fun canOpenFromBackground(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Starts a WhatsApp voice call to [number] on this (gateway) phone.
     * Returns null on success, otherwise a message for the log.
     */
    fun startWhatsAppCall(ctx: Context, number: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "WhatsApp call failed: Contacts permission missing on gateway"
        }
        val installed = VOIP_MIME.values.filter { isInstalled(ctx, it) }
        if (installed.isEmpty()) return "WhatsApp call failed: WhatsApp is not installed on the gateway"

        val digits = number.filter { it.isDigit() }
        if (digits.length < 6) return "WhatsApp call failed: '$number' is not a full phone number"
        val tail = digits.takeLast(9)

        var match: Pair<Long, String>? = null
        var rows = 0
        val byContact = HashMap<Long, Pair<Long, String>>()
        try {
            val mimes = VOIP_MIME.keys.toTypedArray()
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1, ContactsContract.Data.CONTACT_ID
                ),
                "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                mimes,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    rows++
                    val entry = c.getLong(0) to (c.getString(1) ?: "")
                    byContact.putIfAbsent(c.getLong(3), entry)
                    val jid = c.getString(2) ?: continue // e.g. 31612345678@s.whatsapp.net
                    if (match == null && jid.substringBefore('@').filter { it.isDigit() }.endsWith(tail)) {
                        match = entry
                    }
                }
            }
            // Second chance: find the contact by its phone number, then use its WhatsApp entry.
            // Works when the number is saved in another format than WhatsApp's own.
            if (match == null && byContact.isNotEmpty()) {
                ctx.contentResolver.query(
                    android.net.Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
                    ),
                    arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
                )?.use { c ->
                    while (match == null && c.moveToNext()) match = byContact[c.getLong(0)]
                }
            }
        } catch (e: Exception) {
            return "WhatsApp call failed: couldn't read contacts (${e.message})"
        }
        val (id, mime) = match
            ?: return if (rows == 0) {
                "WhatsApp call failed: the gateway's Contacts has no WhatsApp entries at all. " +
                    "In WhatsApp allow the Contacts permission, and check WhatsApp contact sync is on (Settings › Accounts)"
            } else {
                "WhatsApp call failed: $number isn't a WhatsApp contact on the gateway ($rows WhatsApp contacts checked). " +
                    "Save it in Contacts there, then open WhatsApp once so it syncs"
            }

        Hub.log("WhatsApp: found $number in Contacts ($rows WhatsApp entries), opening the call…")
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id), mime)
            .setPackage(VOIP_MIME[mime])
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(ctx, i)
    }

    private const val LAUNCH_CHANNEL = "vgs_wa_launch"

    /**
     * Android blocks apps in the background from opening other apps' screens, silently.
     * With "Display over other apps" allowed we show a 1-pixel overlay for a moment (which
     * counts as a visible window) and open WhatsApp from it. Without it we post a
     * notification the user can tap on the gateway.
     */
    private fun launch(ctx: Context, i: Intent): String? {
        val main = Handler(Looper.getMainLooper())
        if (canOpenFromBackground(ctx)) {
            main.post {
                val wm = ctx.getSystemService(WindowManager::class.java)
                var overlay: View? = null
                try {
                    val v = View(ctx)
                    wm.addView(
                        v,
                        WindowManager.LayoutParams(
                            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT
                        )
                    )
                    overlay = v
                } catch (e: Exception) {
                    Hub.log("WhatsApp: overlay window failed (${e.message}), trying anyway")
                }
                try {
                    ctx.startActivity(i)
                    Hub.log("WhatsApp: call screen requested")
                } catch (e: Exception) {
                    Hub.log("WhatsApp call failed: ${e.message}")
                }
                overlay?.let { v ->
                    main.postDelayed({ try { wm.removeView(v) } catch (e: Exception) {} }, 4000)
                }
            }
            return null
        }
        return try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(LAUNCH_CHANNEL, "Start WhatsApp call", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                7701,
                Notification.Builder(ctx, LAUNCH_CHANNEL)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Tap to start the WhatsApp call")
                    .setContentText("Allow 'Display over other apps' for VGS to skip this tap")
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .build()
            )
            Hub.log("⚠️ 'Display over other apps' is OFF on the gateway, so Android won't let VGS open WhatsApp by itself. Tap the 'Start the WhatsApp call' notification on the gateway, or allow it in the WhatsApp calls card")
            null
        } catch (e: Exception) {
            "WhatsApp call failed: ${e.message}"
        }
    }

    /** Contacts that have WhatsApp, read from the entries WhatsApp adds to this phone's contacts. */
    fun whatsAppContacts(ctx: Context): List<Contact> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                VOIP_MIME.keys.toTypedArray(),
                ContactsContract.Data.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val jid = c.getString(1) ?: continue // e.g. 31612345678@s.whatsapp.net
                    val digits = jid.substringBefore('@').filter { it.isDigit() }
                    if (digits.length < 6 || !seen.add(digits)) continue
                    out.add(Contact(ContactStore.clean(c.getString(0) ?: ""), "+$digits"))
                }
            }
        } catch (e: Exception) {
            Hub.log("WhatsApp contacts read error: ${e.message}")
        }
        return out
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
