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
        const val EXTRA_ACTION_LABEL = "action_label"
        const val EXTRA_NOTIF_PACKAGE = "notification_package"
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
    private val localWhatsappCallStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val peerWhatsappCallStates = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                val label = intent.getStringExtra(EXTRA_ACTION_LABEL).orEmpty()
                val notifPackage = intent.getStringExtra(EXTRA_NOTIF_PACKAGE).orEmpty()
                if (running && role == ROLE_REMOTE && connected && key != null && index >= 0 &&
                    !((localWhatsappCallStates[notifPackage] == "ACTIVE" || peerWhatsappCallStates[notifPackage] == "ACTIVE") && isWhatsAppPackage(notifPackage) && isAnswerActionLabel(label))) {
                    send("NOTIF_ACTION", key, index.toString(), encodeText(label))
                } else if ((localWhatsappCallStates[notifPackage] == "ACTIVE" || peerWhatsappCallStates[notifPackage] == "ACTIVE") && isWhatsAppPackage(notifPackage) && isAnswerActionLabel(label)) {
                    Hub.log("Ignored stale WhatsApp Answer action: a WhatsApp call is already active on Remote")
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
        if (role == ROLE_GATEWAY) gatewayEnd(rejectRinging = true) else requestGateway("REJECT")
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("HANGUP")
    }

    fun dial(number: String) {
        if (role == ROLE_GATEWAY) gatewayDial(number) else requestGateway("DIAL", number)
    }

    /** Ask the Gateway to refresh the Remote's offline contact cache. */
    fun syncContacts() {
        if (role == ROLE_GATEWAY) {
            sendContacts()
        } else if (!connected) {
            Hub.log("Contact sync unavailable: Bluetooth is disconnected")
        } else {
            send("SYNC_CONTACTS")
            Hub.log("Requested contact sync from Gateway")
        }
    }

    /**
     * WhatsApp does not expose a stable public API to place a voice call directly.
     * This asks the Gateway to post a notification that opens the WhatsApp chat when
     * tapped on that phone; the user starts the voice call inside WhatsApp.
     */
    fun openWhatsAppChat(number: String) {
        if (role == ROLE_GATEWAY) {
            gatewayWhatsAppChat(number)
        } else if (!connected) {
            Hub.log("WhatsApp handoff unavailable: Bluetooth is disconnected")
        } else {
            send("WHATSAPP_CHAT", number)
            Hub.log("Requested WhatsApp chat handoff on Gateway")
        }
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

    private fun isWhatsAppPackage(pkg: String): Boolean = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    private fun isAnswerActionLabel(label: String): Boolean {
        val lower = label.trim().lowercase()
        return listOf("answer", "accept", "pick up", "rispondi", "contestar", "responder", "décrocher", "annehmen", "接听", "接聽", "応答").any(lower::contains)
    }

    /**
     * Suppress the Gateway's WhatsApp call copy whenever Remote has already
     * detected its own native WhatsApp call notification. Native WhatsApp UI
     * takes priority; mirroring remains a fallback if Remote has no detected call.
     */
    fun shouldSuppressWhatsAppMirror(pkg: String): Boolean =
        isWhatsAppPackage(pkg) && peerWhatsappCallStates[pkg] in setOf("RINGING", "ACTIVE")

    /** Observe and relay call state only; never auto-answer, reject, or mute a call. */
    fun updateLocalWhatsappCallState(pkg: String, state: String) {
        if (!isWhatsAppPackage(pkg) || state !in setOf("IDLE", "RINGING", "ACTIVE")) return
        if (state == "IDLE") localWhatsappCallStates.remove(pkg) else localWhatsappCallStates[pkg] = state
        Hub.log("Local WhatsApp call state detected for $pkg: $state")
        if (connected) send("WHATSAPP_CALL_STATE", encodeText(pkg), state)
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
            if (callState != CALL_IDLE) {
                Hub.log("Voice link unavailable during a SIM call; preserving Android call-audio routing")
                return
            }
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
        // Failed client connection attempts back off exponentially to reduce wakeups
        // and log churn when the peer is out of range or Bluetooth is unavailable.
        // A healthy session resets the delay so ordinary reconnects remain quick.
        var retryDelayMs = 3_000L
        while (alive(gen)) {
            var s: BluetoothSocket? = null
            var sessionStartedAt = 0L
            try {
                s = if (role == ROLE_GATEWAY) acceptOne(gen) else connectOnce()
                if (s != null && alive(gen)) {
                    sessionStartedAt = android.os.SystemClock.elapsedRealtime()
                    runSession(s, gen)
                }
            } catch (e: Exception) {
                if (alive(gen)) Hub.log("Link: ${e.message}")
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                }
            }
            if (!alive(gen)) break
            if (sessionStartedAt != 0L && android.os.SystemClock.elapsedRealtime() - sessionStartedAt >= 15_000L) {
                retryDelayMs = 3_000L
            }
            val waitMs = retryDelayMs
            setConn(false, if (role == ROLE_GATEWAY) "Disconnected – waiting again…"
                else "Disconnected – retrying in ${waitMs / 1000}s…")
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                break
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
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
            throw IOException("Can't reach gateway (is PCL started there?)")
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
        NotifListener.instance?.syncActiveNotifications()
        send("HELLO", role)
        localWhatsappCallStates.forEach { (pkg, state) -> send("WHATSAPP_CALL_STATE", encodeText(pkg), state) }
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
            peerWhatsappCallStates.clear()
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
                val previousCallState = callState
                callState = arg1.ifEmpty { CALL_IDLE }
                if (previousCallState == CALL_IDLE && callState != CALL_IDLE) {
                    stopVoiceLinkForPhoneCall()
                }
                if (role == ROLE_REMOTE && callState == CALL_RINGING) callControlError = null
                callNumber = p.getOrElse(2) { "" }
                callName = p.getOrElse(3) { "" }
                callDirection = p.getOrElse(4) { "" }
                if (role == ROLE_REMOTE) onRemoteCallUpdate()
            }
            // remote -> gateway
            "ANSWER" -> { gatewayAnswer(); sendCallResult() }
            "REJECT", "HANGUP" -> { gatewayEnd(rejectRinging = p[0] == "REJECT"); sendCallResult() }
            "DIAL" -> { gatewayDial(arg1); sendCallResult() }
            "SYNC_CONTACTS" -> {
                if (role == ROLE_GATEWAY) sendContacts()
                else Hub.log("Ignored contact-sync request received by Remote")
            }
            "WHATSAPP_CHAT" -> {
                if (role == ROLE_GATEWAY) {
                    val ok = gatewayWhatsAppChat(arg1)
                    send("WHATSAPP_CHAT_RESULT", if (ok) "OK" else "FAILED")
                } else {
                    Hub.log("Ignored WhatsApp handoff request received by Remote")
                }
            }
            "WHATSAPP_CHAT_RESULT" -> Hub.log(
                if (arg1 == "OK") "WhatsApp chat handoff notification created on Gateway"
                else "Could not create WhatsApp chat handoff on Gateway"
            )
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
                if (callState != CALL_IDLE) {
                    Hub.log("Rejected voice link request during a SIM call to preserve Android call-audio routing")
                    send("VOICE_STOP")
                } else if (has(Manifest.permission.RECORD_AUDIO)) {
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
            "CONTACTS_RESULT" -> {
                val detail = p.drop(2).joinToString("|")
                if (arg1 == "OK") Hub.log("Gateway contact sync completed: $detail contacts sent")
                else Hub.log("Contact sync failed: $detail")
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
                val pkg = decodeText(p.getOrElse(6 + count * 2) { "" })
                showMirroredCall(arg1, pkg, decodeText(p.getOrElse(2) { "" }), decodeText(p.getOrElse(3) { "" }), decodeText(p.getOrElse(4) { "" }), indices, labels)
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
                    val label = decodeText(p.getOrNull(3).orEmpty())
                    val notifPackage = key?.let { NotifListener.instance?.notificationPackage(it) }.orEmpty()
                    val duplicateWhatsappAnswer = isWhatsAppPackage(notifPackage) &&
                        peerWhatsappCallStates[notifPackage] == "ACTIVE" && isAnswerActionLabel(label)
                    val ok = !duplicateWhatsappAnswer && key != null && index >= 0 && (NotifListener.instance?.performAction(key, index) == true)
                    if (duplicateWhatsappAnswer) Hub.log("Ignored stale WhatsApp Answer action: Remote already has an active WhatsApp call in $notifPackage")
                    else if (!ok) Hub.log("Third-party call action failed or is no longer available")
                    send("NOTIF_ACTION_RESULT", if (ok) "OK" else "FAILED")
                }
            }
            "WHATSAPP_CALL_STATE" -> {
                val pkg = decodeText(arg1)
                val state = p.getOrNull(2).orEmpty()
                if (isWhatsAppPackage(pkg) && state in setOf("IDLE", "RINGING", "ACTIVE")) {
                    if (state == "IDLE") {
                        peerWhatsappCallStates.remove(pkg)
                    } else {
                        peerWhatsappCallStates[pkg] = state
                        if (role == ROLE_GATEWAY) {
                            // Remote may detect its native notification after the Gateway
                            // already mirrored one. Remove all WhatsApp call copies now.
                            NotifListener.instance?.suppressWhatsAppCallMirrors(pkg)
                        }
                    }
                    Hub.log("Peer WhatsApp call state for $pkg: $state")
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
            if (previousState == CALL_IDLE && newState != CALL_IDLE) {
                stopVoiceLinkForPhoneCall()
            }
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
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(phoneReceiver, f)
            receiverRegistered = true
            // Some devices do not redeliver the current phone state on registration.
            syncCurrentPhoneState()
        } catch (e: SecurityException) {
            Hub.log("Phone-state monitoring blocked by Android: ${e.message ?: "permission denied"}")
        } catch (e: RuntimeException) {
            Hub.log("Could not register phone-state monitoring: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun syncCurrentPhoneState() {
        try {
            val tm = getSystemService(TelephonyManager::class.java) ?: return
            val current = when (tm.callState) {
                TelephonyManager.CALL_STATE_RINGING -> CALL_RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            if (current != callState) {
                callState = current
                // A snapshot cannot reliably identify call direction or caller number.
                if (current != CALL_IDLE) callDirection = ""
                else { callDirection = ""; callNumber = ""; callName = "" }
                Hub.log("Initial phone state: $current (details may be unavailable)")
                sendCallState()
            }
        } catch (e: Exception) {
            Hub.log("Initial phone-state query unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sendCallState() {
        if (writer != null) send("CALL", callState, callNumber, callName, callDirection)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayAnswer() {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            if (tryCallNotificationFallback("answer")) return
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("Answer failed: 'Phone' permission missing on gateway; notification fallback unavailable")
            return
        }
        try {
            getSystemService(TelecomManager::class.java)!!.acceptRingingCall()
            callControlError = null
            Hub.log("Sent answer request through Android Telecom")
        } catch (e: Exception) {
            // OEM Phone apps may expose an Answer PendingIntent when Telecom control fails.
            // Invoke only the action the dialer itself supplied; never synthesize a private intent.
            val fallbackWorked = NotifListener.instance?.performCallAction("answer") == true
            callControlError = if (fallbackWorked) null else
                "FAILED: ${e.message ?: "Android rejected the answer request"}"
            Hub.log(if (fallbackWorked) "Answered via the Phone notification action fallback"
                else "Answer failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayEnd(rejectRinging: Boolean = false) {
        val fallbackKind = if (rejectRinging) "reject" else "end"
        if (Build.VERSION.SDK_INT < 28) {
            if (tryCallNotificationFallback(fallbackKind)) return
            callControlError = "FAILED: Ending calls needs Android 9 or a usable Phone notification action"
            Hub.log("End call unavailable: Android version too old and no notification fallback worked")
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            if (tryCallNotificationFallback(fallbackKind)) return
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("End call failed: 'Phone' permission missing and notification fallback unavailable")
            return
        }
        try {
            val ok = getSystemService(TelecomManager::class.java)!!.endCall()
            if (ok) {
                callControlError = null
                Hub.log("End-call request accepted by Android Telecom")
                return
            }
            val fallbackWorked = NotifListener.instance?.performCallAction(fallbackKind) == true
            callControlError = if (fallbackWorked) null else "FAILED: Android did not end the call"
            Hub.log(if (fallbackWorked) "Call control used the Phone notification action fallback"
                else "Android did not end the call and no matching notification action was available")
        } catch (e: Exception) {
            val fallbackWorked = NotifListener.instance?.performCallAction(fallbackKind) == true
            callControlError = if (fallbackWorked) null else
                "FAILED: ${e.message ?: "Android rejected the end-call request"}"
            Hub.log(if (fallbackWorked) "Call control used the Phone notification action fallback"
                else "End call failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopVoiceLinkForPhoneCall() {
        val wasRunning = audio.isRunning
        if (wasRunning) {
            audio.stop()
            runCatching { setForegroundAudioActive(false) }
            Hub.log("Voice link stopped to preserve Android-managed call audio")
        }
        // Stop the peer too, even if this side was not streaming when the call arrived.
        // If disconnected, neither side can be streaming through this socket.
        if (connected) send("VOICE_STOP")
    }

    private fun tryCallNotificationFallback(kind: String): Boolean {
        val worked = NotifListener.instance?.performCallAction(kind) == true
        if (worked) {
            callControlError = null
            Hub.log("Call control used the default Phone notification action fallback")
        }
        return worked
    }

    private fun gatewayWhatsAppChat(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (!nm.areNotificationsEnabled() ||
            nm.getNotificationChannel(CH_MIRROR)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Hub.log("WhatsApp handoff failed: enable PCL notifications on Gateway")
            return false
        }
        if (digits.length !in 7..15) {
            Hub.log("WhatsApp handoff failed: enter a valid number including its country code")
            return false
        }
        return try {
            val uri = Uri.parse("https://wa.me/$digits")
            val openChat = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                this,
                digits.hashCode(),
                openChat,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val name = lookupName(number)
            val display = if (name.isNotBlank()) "$name ($digits)" else digits
            val n = Notification.Builder(this, CH_MIRROR)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Continue in WhatsApp")
                .setContentText("Tap to open chat with $display, then start the voice call in WhatsApp.")
                .setStyle(Notification.BigTextStyle().bigText(
                    "Tap to open the WhatsApp chat with $display. Start the voice call from WhatsApp; PCL cannot place that call directly."
                ))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            nm.notify(42, n)
            Hub.log("Posted WhatsApp chat handoff for $display")
            true
        } catch (e: Exception) {
            Hub.log("WhatsApp handoff failed: ${e.message ?: e.javaClass.simpleName}")
            false
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
        val w = writer
        if (w == null || !connected) {
            Hub.log("Contact sync unavailable: Bluetooth is disconnected")
            return
        }
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – contacts not sent to Remote")
            send("CONTACTS_RESULT", "FAILED", "Contacts permission is not granted on Gateway")
            return
        }
        val list = ContactStore.readDevice(this)
        val chunks = ContactStore.toChunks(list)
        try {
            io.execute {
                try {
                    w.writeCmd("CONTACTS_BEGIN")
                    for (c in chunks) w.writeCmd("CONTACTS|$c")
                    w.writeCmd("CONTACTS_END")
                    w.writeCmd("CONTACTS_RESULT|OK|${list.size}")
                    Hub.log("Sent ${list.size} contacts to Remote")
                } catch (e: Exception) {
                    Hub.log("Contacts sync failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Hub.log("Could not queue contact sync: ${e.message}")
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
    fun mirrorCallPosted(key: String, pkg: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>) {
        // Keep the original field order; append the package so older peers can still parse actions.
        val fields = mutableListOf("CALL_NOTIF", encodeKey(key), encodeText(app), encodeText(title), encodeText(text.take(1200)))
        val count = minOf(3, actionIndices.size, actionLabels.size)
        fields += count.toString()
        for (i in 0 until count) { fields += actionIndices[i].toString(); fields += encodeText(actionLabels[i].take(40)) }
        fields += encodeText(pkg)
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
    private fun showMirroredCall(encKey: String, pkg: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>) {
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
                Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_ACTION).putExtra(EXTRA_KEY, encKey).putExtra(EXTRA_ACTION_INDEX, sourceIndex).putExtra(EXTRA_ACTION_LABEL, label).putExtra(EXTRA_NOTIF_PACKAGE, pkg),
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
            .setContentTitle(if (role == ROLE_GATEWAY) "PCL · Gateway" else "PCL · Remote")
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
