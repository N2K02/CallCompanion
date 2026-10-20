package com.pcl.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Wire format over the Bluetooth RFCOMM socket:
 *   [1 byte type][4 byte length][payload]
 * type 1 = text command "NAME|arg1|arg2".
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7a1e5d2c-3b4f-4c8a-9e61-2f5d8c0b9a41")
    const val SERVICE_NAME = "PCL"
    const val TYPE_CMD = 1
    const val MAX_FRAME = 64 * 1024

    /**
     * Bumped whenever the phones start exchanging new messages, and sent in HELLO so mismatched
     * versions are reported instead of failing silently.
     *  - no number: PCL 2.1 and older
     *  - 3: HELLO carries versions; Gateway reports hotspot status (HOTSPOT_STATE)
     *  - 4: the Remote can ask the Gateway to start a WhatsApp call (WHATSAPP_CALL)
     *  - 5: the Wi-Fi hotspot workaround is gone (no HOTSPOT_STATE, no WHATSAPP_CALL_TRIGGER)
     */
    const val VERSION = 5
}

/**
 * Which phone may act on each command. A command arriving at the wrong role is ignored,
 * so a peer can't, for example, make a Remote place calls.
 * HELLO goes both ways and is in neither set. WHATSAPP_CALL_STATE goes from the Gateway to
 * the Remote; older Remotes also sent their own, which the Gateway ignores.
 */
object Commands {
    /** Sent by the Remote; handled only on the Gateway. */
    val GATEWAY_HANDLES = setOf(
        "ANSWER", "REJECT", "HANGUP", "DIAL", "SYNC_CONTACTS",
        "WHATSAPP_CHAT", "WHATSAPP_CALL", "NOTIF_DISMISS", "NOTIF_ACTION"
    )

    /** Sent by the Gateway; handled only on the Remote. */
    val REMOTE_HANDLES = setOf(
        "CALL", "CALL_RESULT", "CONTACTS_HASH", "CONTACTS_BEGIN", "CONTACTS", "CONTACTS_END",
        "CONTACTS_RESULT", "NOTIF", "NOTIF_V2", "CALL_NOTIF", "NOTIF_REMOVE",
        "WHATSAPP_CHAT_RESULT", "WHATSAPP_CALL_RESULT", "NOTIF_ACTION_RESULT"
    )
}

class FrameWriter(stream: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(stream, 8192))

    @Synchronized
    fun writeCmd(cmd: String) {
        val b = cmd.toByteArray(Charsets.UTF_8)
        writeFrame(Protocol.TYPE_CMD, b, b.size)
    }

    private fun writeFrame(type: Int, b: ByteArray, len: Int) {
        out.writeByte(type)
        out.writeInt(len)
        out.write(b, 0, len)
        out.flush()
    }
}

/** Tiny shared state between the service and the screen. */
object Hub {
    @Volatile
    var service: LinkService? = null
    var listener: (() -> Unit)? = null

    /** Bumped when new contacts arrive from the gateway, so the screen reloads them. */
    @Volatile
    var contactsVersion = 0

    private val main = Handler(Looper.getMainLooper())

    /** Writes to Logcat and to the in-app activity log. */
    fun log(msg: String) {
        android.util.Log.i("PCL", msg)
        CallLogger.log(msg)
        notifyUi()
    }

    fun notifyUi() {
        main.post { listener?.invoke() }
    }
}
