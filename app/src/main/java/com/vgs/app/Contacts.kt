package com.vgs.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import java.io.File

data class Contact(val name: String, val number: String) {
    /** What appears inside the dial box after picking a suggestion. */
    fun label(): String = if (name.isEmpty()) number else "$number  ·  $name"
}

/**
 * Contacts for the dial box: the phone's own contacts plus the list synced
 * from the gateway phone (saved to a file so it works offline).
 */
object ContactStore {
    private const val FILE = "synced_contacts.tsv"

    fun digits(s: String): String = s.filter { it.isDigit() }

    fun clean(s: String): String =
        s.replace('|', ' ').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun readDevice(ctx: Context): List<Contact> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = clean(c.getString(0) ?: "")
                    val num = clean(c.getString(1) ?: "")
                    val d = digits(num)
                    if (d.isEmpty()) continue
                    if (!seen.add(name.lowercase() + "#" + d)) continue
                    out.add(Contact(name, num))
                }
            }
        } catch (e: Exception) {
            Hub.log("Contacts read error: ${e.message}")
        }
        return out
    }

    /** Serialize for sending over Bluetooth: "name\tnumber\n" lines, chunked. */
    fun toChunks(list: List<Contact>, maxChars: Int = 12000): List<String> {
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (c in list) {
            sb.append(clean(c.name)).append('\t').append(clean(c.number)).append('\n')
            if (sb.length >= maxChars) {
                chunks.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    fun parse(data: String): List<Contact> =
        data.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i < 0) null else {
                val num = line.substring(i + 1).trim()
                if (digits(num).isEmpty()) null else Contact(line.substring(0, i).trim(), num)
            }
        }

    fun saveSynced(ctx: Context, data: String) {
        try {
            File(ctx.filesDir, FILE).writeText(data)
        } catch (e: Exception) {
            Hub.log("Could not save contacts: ${e.message}")
        }
    }

    fun loadSynced(ctx: Context): List<Contact> = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) parse(f.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Contacts for the phone (SIM) list: the gateway's contacts once they have been received,
     * otherwise this phone's own contacts. One entry per phone number.
     */
    fun all(ctx: Context): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>()
        val gateway = loadSynced(ctx)
        for (c in if (gateway.isNotEmpty()) gateway else readDevice(ctx)) {
            val d = digits(c.number)
            val key = if (d.length > 9) d.takeLast(9) else d // treat +230 5xxx and 5xxx as the same
            if (seen.add(key)) out.add(c)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Suggestions for the dial box: matches name or digits as you type. */
    fun search(all: List<Contact>, query: String, limit: Int = 6): List<Contact> {
        val q = query.substringBefore("·").trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val qd = digits(q)
        return all.asSequence().filter { c ->
            c.name.lowercase().contains(q) || (qd.isNotEmpty() && digits(c.number).contains(qd))
        }.take(limit).toList()
    }
}
