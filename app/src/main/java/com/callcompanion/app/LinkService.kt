package com.callcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Base64
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Runs on both phones.
 *  - GATEWAY (phone with SIM/internet): listens for the remote over Bluetooth, reports
 *    SIM call state, answers/rejects/dials on request.
 *  - REMOTE (watch-mode phone): connects to the gateway, rings on incoming calls,
 *    sends answer/reject/dial commands.
 * Both sides can open a two-way voice link (mic <-> speaker) over the same socket.
 */
class LinkService : Service() {

    companion object {
        const val ROLE_GATEWAY = "gateway"
        const val ROLE_REMOTE = "remote"

        const val ACTION_START = "com.callcompanion.app.START"
        const val ACTION_STOP = "com.callcompanion.app.STOP"
        const val ACTION_ANSWER = "com.callcompanion.app.ANSWER"
        const val ACTION_REJECT = "com.callcompanion.app.REJECT"
        const val ACTION_NOTIF_DISMISSED = "com.callcompanion.app.NOTIF_DISMISSED"
        const val ACTION_NOTIF_ACTION = "com.callcompanion.app.NOTIF_ACTION"
        const val EXTRA_KEY = "key"
        const val EXTRA_ACTION_INDEX = "action_index"
        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"

        const val CALL_IDLE = "IDLE"
        const val CALL_RINGING = "RINGING"
        const val CALL_OFFHOOK = "OFFHOOK"

        private const val CH_STATUS = "vgs_status"
        private const val CH_CALLS = "vgs_calls"
        private const val CH_MIRROR = "vgs_mirror"
        private const val TAG_MIRROR = "mirror"
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
    }

    @Volatile var role: String = ROLE_REMOTE
        private set
    @Volatile var connectionState: String = "Stopped"
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var callState: String = CALL_IDLE
        private set
    @Volatile var callNumber: String = ""
        private set
    @Volatile var callName: String = ""
        private set
    @Volatile var callDirection: String = ""
        private set
    @Volatile var callControlError: String? = null
        private set

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var deviceAddress: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var writer: FrameWriter? = null
    private var worker: Thread? = null
    private val io = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false
    private var ringtone: Ringtone? = null
    private var contactBuf: StringBuilder? = null

    val isActive: Boolean get() = running
    val voiceActive: Boolean get() = audio.isRunning
    val voiceState: String get() = audio.state
    val speakerOn: Boolean get() = audio.speakerOn

    private val audio: AudioBridge by lazy {
        AudioBridge(this) { buf, n ->
            val w = writer ?: throw IOException("not connected")
            w.writeAudio(buf, n)
        }
    }

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.service = this
        createChannels()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdownNow()
        Hub.service = null
        Hub.notifyUi()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running) shutdown()
                role = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_REMOTE
                deviceAddress = intent.getStringExtra(EXTRA_DEVICE)
                running = true
                connectionState = "Starting…"
                goForeground()
                if (role == ROLE_GATEWAY) registerPhoneReceiver()
                startWorker()
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_ANSWER -> if (running) answer() else stopSelf()
            ACTION_REJECT -> if (running) reject() else stopSelf()
            ACTION_NOTIF_DISMISSED -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                if (running && key != null) send("NOTIF_DISMISS", key) else if (!running) stopSelf()
            }
            ACTION_NOTIF_ACTION -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                val index = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
                if (running && role == ROLE_REMOTE && connected && key != null && index >= 0) {
                    send("NOTIF_ACTION", key, index.toString())
                } else {
                    Hub.log("Third-party call action unavailable: Bluetooth link is not connected")
                }
            }
        }
        Hub.notifyUi()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- public actions (UI)

    fun answer() {
        stopRinging()
        if (role == ROLE_GATEWAY) gatewayAnswer() else requestGateway("ANSWER")
    }

    fun reject() {
        stopRinging()
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("REJECT")
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("HANGUP")
    }

    fun dial(number: String) {
        if (role == ROLE_GATEWAY) gatewayDial(number) else requestGateway("DIAL", number)
    }

    private fun requestGateway(command: String, argument: String = "") {
        if (!connected) {
            callControlError = "FAILED: Bluetooth is disconnected"
            Hub.log("Call control failed: Bluetooth is disconnected")
            Hub.notifyUi()
            return
        }
        callControlError = "PENDING: Waiting for Gateway confirmation"
        if (command == "DIAL") send(command, argument) else send(command)
        Hub.notifyUi()
    }

    fun setGatewaySpeaker(on: Boolean) {
        send("SPEAKER", if (on) "1" else "0")
    }

    fun setLocalSpeaker(on: Boolean) {
        audio.setSpeaker(on)
        Hub.notifyUi()
    }

    fun toggleVoice() {
        if (audio.isRunning) {
            audio.stop()
            setForegroundAudioActive(false)
            send("VOICE_STOP")
        } else {
            if (!connected) {
                Hub.log("Not connected yet")
                return
            }
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                Hub.log("Microphone permission missing")
                return
            }
            try {
                setForegroundAudioActive(true)
                audio.start()
                if (audio.state == "STARTING") send("VOICE_START")
            } catch (e: Exception) {
                Hub.log("Could not start voice link: ${e.message ?: e.javaClass.simpleName}")
                runCatching { setForegroundAudioActive(false) }
            }
        }
        updateStatus()
    }

    // ---------------------------------------------------------------- connection

    private fun startWorker() {
        val gen = ++generation
        worker = Thread({ workerLoop(gen) }, "vgs-link").also { it.start() }
    }

    private fun alive(gen: Int) = running && gen == generation

    private fun workerLoop(gen: Int) {
        while (alive(gen)) {
            var s: BluetoothSocket? = null
            try {
                s = if (role == ROLE_GATEWAY) acceptOne(gen) else connectOnce()
                if (s != null && alive(gen)) runSession(s, gen)
            } catch (e: Exception) {
                if (alive(gen)) Hub.log("Link: ${e.message}")
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                }
            }
            if (!alive(gen)) break
            setConn(false, if (role == ROLE_GATEWAY) "Disconnected – waiting again…" else "Disconnected – retrying in 3s…")
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun adapter(): BluetoothAdapter {
        val a = getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is OFF – turn it on")
        return a
    }

    @SuppressLint("MissingPermission")
    private fun acceptOne(gen: Int): BluetoothSocket? {
        val a = adapter()
        setConn(false, "Waiting for remote phone…")
        val ss = a.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
        serverSocket = ss
        try {
            val s = ss.accept()
            if (!alive(gen)) {
                s.close()
                return null
            }
            return s
        } finally {
            try {
                ss.close()
            } catch (e: Exception) {
            }
            serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnce(): BluetoothSocket {
        val a = adapter()
        val addr = deviceAddress ?: throw IOException("No gateway phone selected")
        val dev = a.getRemoteDevice(addr)
        setConn(false, "Connecting to ${dev.name ?: addr}…")
        val s = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        socket = s
        try {
            s.connect()
        } catch (e: IOException) {
            throw IOException("Can't reach gateway (is CallCompanion started there?)")
        }
        return s
    }

    @SuppressLint("MissingPermission")
    private fun runSession(s: BluetoothSocket, gen: Int) {
        socket = s
        val w = FrameWriter(s.outputStream)
        writer = w
        val peer = try {
            s.remoteDevice.name ?: s.remoteDevice.address
        } catch (e: Exception) {
            "peer"
        }
        setConn(true, "Connected to $peer")
        if (role == ROLE_GATEWAY) NotifListener.instance?.syncActiveNotifications()
        send("HELLO", role)
        if (role == ROLE_GATEWAY) {
            sendCallState()
            sendContacts()
        }
        val input = DataInputStream(BufferedInputStream(s.inputStream, 16384))
        try {
            while (alive(gen)) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > Protocol.MAX_FRAME) throw IOException("bad data from peer")
                val data = ByteArray(len)
                input.readFully(data)
                when (type) {
                    Protocol.TYPE_CMD -> handleCommand(String(data, Charsets.UTF_8))
                    Protocol.TYPE_AUDIO -> audio.onAudio(data)
                }
            }
        } finally {
            if (writer === w) writer = null
            if (socket === s) socket = null
            connected = false
            audio.stop()
            setForegroundAudioActive(false)
            if (role == ROLE_REMOTE) {
                if (callControlError?.startsWith("PENDING:") == true) callControlError = "FAILED: Connection lost before Gateway confirmation"
                stopRinging()
                callState = CALL_IDLE
                nm.cancel(ID_INCOMING)
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
    }

    private fun shutdown() {
        if (!running && worker == null) return
        running = false
        generation++
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        closeSocket()
        worker?.interrupt()
        worker = null
        writer = null
        connected = false
        audio.stop()
        stopRinging()
        if (receiverRegistered) {
            try {
                unregisterReceiver(phoneReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        nm.cancel(ID_INCOMING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        connectionState = "Stopped"
        callState = CALL_IDLE
        Hub.log("Stopped")
    }

    private fun send(vararg parts: String) {
        val w = writer
        if (w == null) {
            Hub.log("Not connected – command not sent")
            return
        }
        val msg = parts.joinToString("|") { it.replace('|', '/') }
        try {
            io.execute {
                try {
                    w.writeCmd(msg)
                } catch (e: Exception) {
                    Hub.log("Send failed: ${e.message}")
                    closeSocket()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun handleCommand(msg: String) {
        val p = msg.split("|")
        val arg1 = p.getOrElse(1) { "" }
        when (p[0]) {
            "HELLO" -> Hub.log("Peer is the $arg1 phone")
            // gateway -> remote
            "CALL" -> {
                callState = arg1.ifEmpty { CALL_IDLE }
                if (role == ROLE_REMOTE && callState == CALL_RINGING) callControlError = null
                callNumber = p.getOrElse(2) { "" }
                callName = p.getOrElse(3) { "" }
                callDirection = p.getOrElse(4) { "" }
                if (role == ROLE_REMOTE) onRemoteCallUpdate()
            }
            // remote -> gateway
            "ANSWER" -> { gatewayAnswer(); sendCallResult() }
            "REJECT", "HANGUP" -> { gatewayEnd(); sendCallResult() }
            "DIAL" -> { gatewayDial(arg1); sendCallResult() }
            "CALL_RESULT" -> {
                val result = arg1
                val detail = p.drop(2).joinToString("|")
                callControlError = if (result == "FAILED") "FAILED: $detail" else null
                Hub.log(if (result == "FAILED") "Gateway call control failed: $detail" else "Gateway accepted call-control request")
            }
            "SPEAKER" -> {
                AudioRoute.setSpeaker(this, arg1 == "1")
                Hub.log("Speaker ${if (arg1 == "1") "ON" else "OFF"} (requested by remote)")
            }
            // both directions
            "VOICE_START" -> {
                if (has(Manifest.permission.RECORD_AUDIO)) {
                    try {
                        setForegroundAudioActive(true)
                        audio.start()
                    } catch (e: Exception) {
                        Hub.log("Could not start received voice link request: ${e.message}")
                        runCatching { setForegroundAudioActive(false) }
                    }
                } else {
                    Hub.log("Peer started voice link but microphone permission is missing")
                }
                updateStatus()
            }
            "VOICE_STOP" -> {
                audio.stop()
                setForegroundAudioActive(false)
                updateStatus()
            }
            // contacts: gateway -> remote
            "CONTACTS_BEGIN" -> contactBuf = StringBuilder()
            "CONTACTS" -> contactBuf?.append(msg.substringAfter('|'))
            "CONTACTS_END" -> {
                val data = contactBuf?.toString() ?: ""
                contactBuf = null
                ContactStore.saveSynced(this, data)
                Hub.contactsVersion++
                Hub.log("Received ${ContactStore.parse(data).size} contacts from gateway")
            }
            // notifications: gateway -> remote
            "NOTIF" -> showMirrored(arg1, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.drop(4).joinToString("/"))
            "NOTIF_V2" -> showMirrored(arg1, decodeText(p.getOrElse(2) { "" }), decodeText(p.getOrElse(3) { "" }), decodeText(p.getOrElse(4) { "" }))
            "CALL_NOTIF" -> {
                val count = (p.getOrElse(5) { "0" }.toIntOrNull() ?: 0).coerceIn(0, 3)
                val indices = ArrayList<Int>()
                val labels = ArrayList<String>()
                for (i in 0 until count) {
                    val index = p.getOrNull(6 + i * 2)?.toIntOrNull() ?: continue
                    val label = decodeText(p.getOrNull(7 + i * 2).orEmpty()).take(40)
                    if (label.isNotBlank()) { indices.add(index); labels.add(label) }
                }
                showMirroredCall(arg1, decodeText(p.getOrElse(2) { "" }), decodeText(p.getOrElse(3) { "" }), decodeText(p.getOrElse(4) { "" }), indices, labels)
            }
            "NOTIF_REMOVE" -> nm.cancel(TAG_MIRROR, arg1.hashCode())
            "NOTIF_DISMISS" -> {
                val key = decodeKey(arg1)
                if (key != null) NotifListener.instance?.dismiss(key)
            }
            "NOTIF_ACTION" -> {
                if (role == ROLE_GATEWAY) {
                    val key = decodeKey(arg1)
                    val index = p.getOrNull(2)?.toIntOrNull() ?: -1
                    val ok = key != null && index >= 0 && (NotifListener.instance?.performAction(key, index) == true)
                    if (!ok) Hub.log("Third-party call action failed or is no longer available")
                    send("NOTIF_ACTION_RESULT", if (ok) "OK" else "FAILED")
                }
            }
            "NOTIF_ACTION_RESULT" -> Hub.log(if (arg1 == "OK") "Third-party call action sent to the app" else "Third-party call action failed")
            else -> Hub.log("Unknown command: ${p[0]}")
        }
        Hub.notifyUi()
    }

    private fun sendCallResult() {
        val error = callControlError
        send("CALL_RESULT", if (error == null) "OK" else "FAILED", error?.removePrefix("FAILED: ") ?: "Android accepted the request")
    }

    private fun setConn(isConnected: Boolean, text: String) {
        connected = isConnected
        connectionState = text
        Hub.log(text)
        updateStatus()
    }

    // ---------------------------------------------------------------- gateway: SIM calls

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val previousState = callState
            val newState = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> CALL_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            if (newState == CALL_RINGING) callDirection = "INCOMING"
            else if (newState == CALL_OFFHOOK && previousState == CALL_IDLE) callDirection = "OUTGOING"
            else if (newState == CALL_IDLE) callDirection = ""
            val gotNumber = !num.isNullOrEmpty() && num != callNumber
            if (!num.isNullOrEmpty()) {
                callNumber = num
                callName = lookupName(num)
            }
            val changed = newState != callState
            if (changed && newState == CALL_RINGING) callControlError = null
            callState = newState
            if (changed || gotNumber) {
                Hub.log("Phone call: $newState ${display()}")
                sendCallState()
            }
            if (newState == CALL_IDLE) {
                callNumber = ""
                callName = ""
            }
            Hub.notifyUi()
        }
    }

    private fun registerPhoneReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)
        receiverRegistered = true
    }

    private fun sendCallState() {
        if (writer != null) send("CALL", callState, callNumber, callName, callDirection)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayAnswer() {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("Answer failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            getSystemService(TelecomManager::class.java)!!.acceptRingingCall()
            callControlError = null
            Hub.log("Answered the call on this phone")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the answer request"}"
            Hub.log("Answer failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayEnd() {
        if (Build.VERSION.SDK_INT < 28) {
            callControlError = "FAILED: Ending calls needs Android 9 or newer on Gateway"
            Hub.log("Ending calls needs Android 9+ on the gateway")
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("End call failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            val ok = getSystemService(TelecomManager::class.java)!!.endCall()
            callControlError = if (ok) null else "FAILED: Android did not end the call"
            Hub.log(if (ok) "Call ended" else "No call to end")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the end-call request"}"
            Hub.log("End call failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun gatewayDial(number: String) {
        val n = number.trim()
        if (n.isEmpty()) { callControlError = "FAILED: Empty phone number"; return }
        if (!has(Manifest.permission.CALL_PHONE)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("Dial failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            callNumber = n
            callName = lookupName(n)
            callDirection = "OUTGOING"
            getSystemService(TelecomManager::class.java)!!
                .placeCall(Uri.fromParts("tel", n, null), Bundle())
            callControlError = null
            Hub.log("Dialing $n")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the dial request"}"
            Hub.log("Dial failed: ${e.message}")
        }
    }

    private fun lookupName(number: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun display(): String = when {
        callName.isNotEmpty() && callNumber.isNotEmpty() -> "$callName ($callNumber)"
        callName.isNotEmpty() -> callName
        callNumber.isNotEmpty() -> callNumber
        else -> "Unknown number"
    }

    // ---------------------------------------------------------------- remote: ringing

    private fun onRemoteCallUpdate() {
        when (callState) {
            CALL_RINGING -> {
                Hub.log("Incoming call: ${display()}")
                showIncoming()
                startRinging()
            }
            CALL_OFFHOOK -> {
                stopRinging()
                nm.cancel(ID_INCOMING)
                Hub.log("Call in progress on gateway: ${display()}")
            }
            else -> {
                stopRinging()
                nm.cancel(ID_INCOMING)
                Hub.log("Call ended")
            }
        }
        updateStatus()
    }

    private fun startRinging() {
        try {
            if (ringtone == null) {
                val r = RingtoneManager.getRingtone(
                    this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                )
                if (r != null && Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r?.play()
                ringtone = r
            }
        } catch (e: Exception) {
            Hub.log("Ringtone error: ${e.message}")
        }
        try {
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        ringtone = null
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = getSystemService(Vibrator::class.java)

    // ---------------------------------------------------------------- contacts sync

    private fun sendContacts() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – contacts not sent to remote")
            return
        }
        val w = writer ?: return
        val list = ContactStore.readDevice(this)
        val chunks = ContactStore.toChunks(list)
        try {
            io.execute {
                try {
                    w.writeCmd("CONTACTS_BEGIN")
                    for (c in chunks) w.writeCmd("CONTACTS|$c")
                    w.writeCmd("CONTACTS_END")
                    Hub.log("Sent ${list.size} contacts to remote")
                } catch (e: Exception) {
                    Hub.log("Contacts sync failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- notification mirroring

    fun shouldMirror(): Boolean =
        running && connected && role == ROLE_GATEWAY &&
            getSharedPreferences("callcompanion", MODE_PRIVATE).getBoolean("mirror", true)

    private fun encodeKey(k: String): String = encodeText(k)
    private fun decodeKey(k: String): String? = try { decodeText(k) } catch (_: Exception) { null }
    private fun encodeText(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decodeText(value: String): String = try { String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8) } catch (_: Exception) { "" }

    /** Gateway: forward a notification with delimiter-safe fields. */
    fun mirrorPosted(key: String, app: String, title: String, text: String) {
        send("NOTIF_V2", encodeKey(key), encodeText(app), encodeText(title), encodeText(text.take(2000)))
    }

    /** Gateway: forward a third-party call notification and its available action buttons. */
    fun mirrorCallPosted(key: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>) {
        val fields = mutableListOf("CALL_NOTIF", encodeKey(key), encodeText(app), encodeText(title), encodeText(text.take(1200)))
        val count = minOf(3, actionIndices.size, actionLabels.size)
        fields += count.toString()
        for (i in 0 until count) { fields += actionIndices[i].toString(); fields += encodeText(actionLabels[i].take(40)) }
        send(*fields.toTypedArray())
    }

    /** Gateway: the notification was cleared on this phone, clear the copy too. */
    fun mirrorRemoved(key: String) {
        if (running && connected && role == ROLE_GATEWAY) send("NOTIF_REMOVE", encodeKey(key))
    }

    /** Remote: show a copy of a gateway notification. */
    private fun showMirrored(encKey: String, app: String, title: String, text: String) {
        val id = encKey.hashCode()
        val deletePi = PendingIntent.getService(this, id, Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_DISMISSED).putExtra(EXTRA_KEY, encKey), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_MIRROR)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setSubText(app)
            .setContentTitle(title.ifEmpty { app }).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setWhen(System.currentTimeMillis())
            .setShowWhen(true).setAutoCancel(true).setContentIntent(openAppPi()).setDeleteIntent(deletePi).build()
        try { nm.notify(TAG_MIRROR, id, n) } catch (_: Exception) { }
    }

    /** Remote: mirror a third-party call notification with its original available actions. */
    private fun showMirroredCall(encKey: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>) {
        val id = encKey.hashCode()
        val deletePi = PendingIntent.getService(this, id, Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_DISMISSED).putExtra(EXTRA_KEY, encKey), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CH_MIRROR)
            .setSmallIcon(android.R.drawable.sym_action_call).setSubText(app)
            .setContentTitle(title.ifEmpty { app }).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_CALL)
            .setWhen(System.currentTimeMillis()).setShowWhen(true).setOngoing(true)
            .setContentIntent(openAppPi()).setDeleteIntent(deletePi)
        for (i in 0 until minOf(3, actionIndices.size, actionLabels.size)) {
            val sourceIndex = actionIndices[i]
            val label = actionLabels[i]
            val lower = label.lowercase()
            val iconId = when {
                lower.contains("answer") || lower.contains("accept") || lower.contains("join") -> android.R.drawable.sym_action_call
                lower.contains("decline") || lower.contains("reject") || lower.contains("end") -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.ic_menu_view
            }
            val pi = PendingIntent.getService(this, id xor (sourceIndex + 0x341),
                Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_ACTION).putExtra(EXTRA_KEY, encKey).putExtra(EXTRA_ACTION_INDEX, sourceIndex),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(action(iconId, label, pi))
        }
        try { nm.notify(TAG_MIRROR, id, builder.build()) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW)
        )
        val calls = NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        calls.setSound(null, null)
        calls.enableVibration(false)
        nm.createNotificationChannel(calls)
        nm.createNotificationChannel(
            NotificationChannel(CH_MIRROR, "Notifications from gateway phone", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppPi(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req,
        Intent(this, LinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()

    private fun statusNotification(): Notification {
        val text = if (audio.isRunning) "$connectionState · voice link ON" else connectionState
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (role == ROLE_GATEWAY) "CallCompanion · Gateway" else "CallCompanion · Remote")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPi())
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Quit", actionPi(ACTION_STOP, 10)))
            .build()
    }

    private fun setForegroundAudioActive(active: Boolean) {
        if (!running) return
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (active) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ID_STATUS, statusNotification(), type)
        } else {
            updateStatus()
        }
    }

    private fun goForeground() {
        val n = statusNotification()
        when {
            Build.VERSION.SDK_INT >= 30 ->
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else -> startForeground(ID_STATUS, n)
        }
    }

    private fun updateStatus() {
        if (running) {
            try {
                nm.notify(ID_STATUS, statusNotification())
            } catch (e: Exception) {
            }
        }
        Hub.notifyUi()
    }

    private fun showIncoming() {
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(display())
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openAppPi())
            .setFullScreenIntent(openAppPi(), true)
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Reject", actionPi(ACTION_REJECT, 11)))
            .addAction(action(android.R.drawable.sym_action_call, "Answer", actionPi(ACTION_ANSWER, 12)))
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (e: Exception) {
        }
    }

    private fun has(perm: String) =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
