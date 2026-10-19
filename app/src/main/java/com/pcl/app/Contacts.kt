package com.pcl.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
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

    private fun clean(s: String): String =
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

    /** Short fingerprint of serialized contacts, so the phones can tell whether a resync is needed. */
    fun fingerprint(data: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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

    /** Local + synced contacts, one entry per phone number. */
    fun all(ctx: Context): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>()
        for (c in readDevice(ctx) + loadSynced(ctx)) {
            // Deduplicate formatting variants of the same full number without
            // dropping country-code digits; truncating to the last 9 digits can merge
            // unrelated contacts in countries with longer international numbers.
            val key = digits(c.number)
            if (key.isNotEmpty() && seen.add(key)) out.add(c)
        }
        return out.sortedBy { it.name.lowercase() }
    }
}

/** Suggestion list for the dial box: matches name or digits as you type. */
class ContactAdapter(private val ctx: Context) : BaseAdapter(), Filterable {
    @Volatile
    var all: List<Contact> = emptyList()
    private var shown: List<Contact> = emptyList()

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): Any = shown[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView
            ?: LayoutInflater.from(ctx).inflate(android.R.layout.simple_list_item_2, parent, false)
        val c = shown[position]
        v.findViewById<TextView>(android.R.id.text1).text = c.name.ifEmpty { c.number }
        v.findViewById<TextView>(android.R.id.text2).text = c.number
        return v
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.substringBefore("·")?.trim()?.lowercase() ?: ""
            val qd = ContactStore.digits(q)
            val res = if (q.isEmpty()) emptyList() else all.filter { c ->
                c.name.lowercase().contains(q) ||
                    (qd.isNotEmpty() && ContactStore.digits(c.number).contains(qd))
            }.take(50)
            val r = FilterResults()
            r.values = res
            r.count = res.size
            return r
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            shown = (results?.values as? List<Contact>) ?: emptyList()
            if (shown.isNotEmpty()) notifyDataSetChanged() else notifyDataSetInvalidated()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Contact)?.label() ?: ""
    }
}
