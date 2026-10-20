package com.pcl.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remote: the calls made and received through the Gateway, for the Calls tab.
 * Kept only on this phone (a private file), newest first, at most [MAX] entries.
 *
 * SIM calls are worked out from the Gateway's call states (ringing, in call, idle):
 * ringing then idle is missed (or declined, when declined from this phone), ringing then
 * in call is incoming, and in call without ringing is outgoing. WhatsApp calls on the
 * Gateway are worked out the same way by [WhatsAppCallTracker].
 */
object CallHistory {
    /** [WHATSAPP] is only in lists saved by PCL 2.8 and older: a WhatsApp call asked for from the Remote. */
    enum class Type { INCOMING, OUTGOING, MISSED, DECLINED, WHATSAPP }

    data class Record(
        val number: String,
        val name: String,
        val type: Type,
        /** When the call started, in epoch milliseconds. */
        val at: Long,
        /** Talk time in seconds; 0 for calls that weren't answered. */
        val durationSec: Long = 0,
        /** A WhatsApp call on the Gateway rather than a SIM call. */
        val whatsapp: Boolean = false,
    )

    private const val FILE = "call_history.json"
    private const val MAX = 100
    private const val PREF_UNSEEN_MISSED = "unseen_missed_calls"

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records

    /** Missed calls not yet seen in the Calls tab (the badge on its icon). */
    private val _unseenMissed = MutableStateFlow(0)
    val unseenMissed: StateFlow<Int> = _unseenMissed

    /** While a SIM call is connected: when it connected (elapsedRealtime), else 0. For the call timer. */
    @Volatile var connectedSince = 0L
        private set

    private var loaded = false
    private lateinit var app: Context

    // Tracker for the Gateway's SIM call states.
    private var lastState = LinkService.CALL_IDLE
    private var ringingNumber = ""
    private var ringingName = ""
    private var startedAt = 0L
    private var answered = false
    private var declinedHere = false
    private var lastDialNumber = ""
    private var lastDialName = ""
    private var lastDialAt = 0L

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        app = context.applicationContext
        loaded = true
        _records.value = try {
            val f = File(app.filesDir, FILE)
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Record(
                        number = o.optString("n"),
                        name = o.optString("m"),
                        type = runCatching { Type.valueOf(o.optString("t")) }.getOrNull() ?: return@mapNotNull null,
                        at = o.optLong("a"),
                        durationSec = o.optLong("d"),
                        whatsapp = o.optBoolean("w"),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        _unseenMissed.value = Prefs.of(app).getInt(PREF_UNSEEN_MISSED, 0)
    }

    @Synchronized
    fun add(record: Record) {
        if (!loaded) return
        val list = (listOf(record) + _records.value).take(MAX)
        _records.value = list
        save(list)
        if (record.type == Type.MISSED) setUnseen(_unseenMissed.value + 1)
    }

    private fun save(list: List<Record>) {
        try {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(JSONObject().put("n", r.number).put("m", r.name).put("t", r.type.name)
                    .put("a", r.at).put("d", r.durationSec).put("w", r.whatsapp))
            }
            File(app.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Hub.log("Could not save call history: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun setUnseen(count: Int) {
        _unseenMissed.value = count
        if (loaded) Prefs.of(app).edit().putInt(PREF_UNSEEN_MISSED, count).apply()
    }

    fun markMissedSeen() {
        if (_unseenMissed.value != 0) setUnseen(0)
    }

    @Synchronized
    fun clear() {
        if (!loaded) return
        _records.value = emptyList()
        save(emptyList())
        setUnseen(0)
    }

    /** The Remote asked the Gateway to dial: remembered because Android may not report the number. */
    @Synchronized
    fun onDialRequested(number: String, name: String) {
        lastDialNumber = number
        lastDialName = name
        lastDialAt = System.currentTimeMillis()
    }

    /** The Remote declined a ringing call, so its end is "declined", not "missed". */
    @Synchronized
    fun onDeclinedHere() {
        if (lastState == LinkService.CALL_RINGING) declinedHere = true
    }

    /**
     * Remote: the Gateway reported its SIM call state. Repeats of the same state are ignored.
     * Returns the call that just ended, if any.
     */
    @Synchronized
    fun onSimState(state: String, number: String, name: String): Record? {
        val previous = lastState
        if (state == previous) {
            // Ringing may be reported again once the caller's name is known.
            if (state == LinkService.CALL_RINGING) {
                if (number.isNotEmpty()) ringingNumber = number
                if (name.isNotEmpty()) ringingName = name
            }
            return null
        }
        lastState = state
        val now = System.currentTimeMillis()
        when (state) {
            LinkService.CALL_RINGING -> {
                ringingNumber = number
                ringingName = name
                startedAt = now
                answered = false
                declinedHere = false
                connectedSince = 0L
            }
            LinkService.CALL_OFFHOOK -> {
                connectedSince = SystemClock.elapsedRealtime()
                if (previous == LinkService.CALL_RINGING) {
                    answered = true
                } else {
                    // Outgoing: Android may not report the number, so use the last dial request.
                    val recentDial = now - lastDialAt < 90_000L
                    ringingNumber = number.ifEmpty { if (recentDial) lastDialNumber else "" }
                    ringingName = name.ifEmpty { if (recentDial) lastDialName else "" }
                    startedAt = now
                    answered = false
                }
            }
            else -> {
                val talk = if (connectedSince > 0) (SystemClock.elapsedRealtime() - connectedSince) / 1000 else 0
                val record = when {
                    previous == LinkService.CALL_RINGING ->
                        Record(ringingNumber, ringingName, if (declinedHere) Type.DECLINED else Type.MISSED, startedAt)
                    previous == LinkService.CALL_OFFHOOK && answered ->
                        Record(ringingNumber, ringingName, Type.INCOMING, startedAt, talk)
                    previous == LinkService.CALL_OFFHOOK ->
                        Record(ringingNumber, ringingName, Type.OUTGOING, startedAt, talk)
                    else -> null
                }
                connectedSince = 0L
                declinedHere = false
                record?.let(::add)
                return record
            }
        }
        return null
    }
}

/** Remote: starred contacts, shown first in the Calls tab. Kept on this phone only. */
object Favourites {
    private const val KEY = "favourite_contacts"
    private val _list = MutableStateFlow<List<Contact>>(emptyList())
    val list: StateFlow<List<Contact>> = _list
    private var app: Context? = null

    /** Numbers match whatever their format: compare the last 9 digits. */
    fun key(number: String): String = ContactStore.digits(number).takeLast(9)

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        _list.value = Prefs.of(ctx).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i < 0) null else Contact(line.substring(0, i), line.substring(i + 1))
            }
            .sortedBy { it.name.ifEmpty { it.number }.lowercase() }
    }

    fun isFavourite(number: String): Boolean {
        val k = key(number)
        return k.isNotEmpty() && _list.value.any { key(it.number) == k }
    }

    @Synchronized
    fun toggle(contact: Contact) {
        val ctx = app ?: return
        val k = key(contact.number)
        if (k.isEmpty()) return
        val current = _list.value
        val updated = if (current.any { key(it.number) == k }) current.filterNot { key(it.number) == k }
        else (current + contact).sortedBy { it.name.ifEmpty { it.number }.lowercase() }
        _list.value = updated
        Prefs.of(ctx).edit()
            .putStringSet(KEY, updated.map { "${it.name.replace('\t', ' ')}\t${it.number}" }.toSet())
            .apply()
    }
}
