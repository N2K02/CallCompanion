package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs on both phones.
 *  - GATEWAY (phone with the SIM): accepts the selected Remote over Bluetooth, reports
 *    SIM call state, and answers/rejects/dials on request.
 *  - REMOTE (companion phone): connects to the selected Gateway, rings on incoming calls,
 *    and sends answer/reject/dial commands.
 */
class LinkService : Service() {

    companion object {
        const val ROLE_GATEWAY = "gateway"
        const val ROLE_REMOTE = "remote"

        const val ACTION_START = "com.pcl.app.START"
        const val ACTION_STOP = "com.pcl.app.STOP"
        const val ACTION_ANSWER = "com.pcl.app.ANSWER"
        const val ACTION_REJECT = "com.pcl.app.REJECT"
        const val ACTION_HANGUP = "com.pcl.app.HANGUP"
        const val ACTION_AUDIO_DOWN = "com.pcl.app.AUDIO_DOWN"
        const val ACTION_AUDIO_UP = "com.pcl.app.AUDIO_UP"
        const val ACTION_AUDIO_SPEAKER = "com.pcl.app.AUDIO_SPEAKER"
        const val ACTION_NOTIF_DISMISSED = "com.pcl.app.NOTIF_DISMISSED"
        const val ACTION_NOTIF_ACTION = "com.pcl.app.NOTIF_ACTION"
        /** Remote: "Call back" on a missed-call notification. */
        const val ACTION_CALL_BACK = "com.pcl.app.CALL_BACK"
        /** Remote: "Call back" on a missed WhatsApp call notification. */
        const val ACTION_WHATSAPP_CALL_BACK = "com.pcl.app.WHATSAPP_CALL_BACK"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        /** MainActivity: which tab to open ("calls"). */
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_KEY = "key"
        const val EXTRA_ACTION_INDEX = "action_index"
        const val EXTRA_ACTION_LABEL = "action_label"
        const val EXTRA_NOTIF_PACKAGE = "notification_package"
        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_REASON = "reason"

        const val CALL_IDLE = "IDLE"
        const val CALL_RINGING = "RINGING"
        const val CALL_OFFHOOK = "OFFHOOK"

        private const val CH_STATUS = "pcl_status"
        private const val CH_CALLS = "pcl_calls"
        private const val CH_MIRROR = "pcl_mirror"
        // Old channels, deleted on start: the "vgs_" ones from before the rename, and the
        // hotspot channel from when PCL switched the Gateway's Wi-Fi hotspot for calls.
        private val LEGACY_CHANNELS = listOf("vgs_status", "vgs_calls", "vgs_mirror", "vgs_hotspot", "pcl_hotspot")
        private const val TAG_MIRROR = "mirror"
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
        private const val ID_WHATSAPP_HANDOFF = 42
        private const val ID_MISSED = 46
        private const val ID_MISSED_WHATSAPP = 47
        private const val TRAFFIC_SAMPLE_MS = 5_000L
        private const val CALL_CONTROL_TIMEOUT_MS = 15_000L
        /** Remote: stop ringing for a mirrored call after this long, whatever happens. */
        private const val MIRROR_RING_LIMIT_MS = 75_000L
        /** Remote: a SIM call connecting this soon after this phone asked to dial is this phone's. */
        private const val DIAL_WINDOW_MS = 60_000L
        /** Remote: how long to wait for the Gateway to say whether a WhatsApp call started. */
        private const val WHATSAPP_CALL_TIMEOUT_MS = 25_000L
        /** Upper bound for a contact sync in characters; protects memory from a misbehaving peer. */
        private const val MAX_CONTACT_CHARS = 8_000_000

        /** Restarts the link with the saved role and phone, if the user left it running. */
        fun startSaved(context: Context, reason: String) {
            val prefs = Prefs.of(context)
            if (!prefs.getBoolean(Prefs.LINK_ENABLED, false)) return
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, LinkService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROLE, prefs.getString(Prefs.ROLE, ROLE_REMOTE))
                .putExtra(EXTRA_DEVICE, prefs.getString(Prefs.PEER_DEVICE, null))
                .putExtra(EXTRA_REASON, reason)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("PCL", "Could not restart the link $reason", e)
            }
        }
    }

    @Volatile var role: String = ROLE_REMOTE
        private set
    @Volatile var connectionState: String = "Stopped"
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var gatewayVolume = 0
        private set
    @Volatile var gatewayMaxVolume = 1
        private set
    @Volatile var gatewayAudioRoute = "UNKNOWN"
        private set

    private val audioRequestSequence = AtomicLong()
    private val pendingAudioRequests = ConcurrentHashMap<String, Runnable>()
    private val audioTimeoutHandler = Handler(Looper.getMainLooper())

    private fun sendAudioRequest(command: String, value: String) {
        if (role != ROLE_REMOTE || !connected) {
            Hub.log("Audio command unavailable: Gateway disconnected")
            return
        }
        val id = audioRequestSequence.incrementAndGet().toString()
        val timeout = Runnable {
            if (pendingAudioRequests.remove(id) != null)
                Hub.log("Audio command timed out: $command")
        }
        pendingAudioRequests[id] = timeout
        audioTimeoutHandler.postDelayed(timeout, 5_000L)
        Hub.log("Remote audio request: $command #$id")
        send(command, value, id)
    }

    fun requestGatewayVolume(level: Int) {
        sendAudioRequest("AUDIO_VOLUME", level.coerceAtLeast(0).toString())
    }

    fun requestGatewayRoute(route: String) {
        // The Gateway owns the authoritative call state; never silently drop a
        // request because the Remote has not received the latest call update.
        if (role == ROLE_REMOTE && connected && route in setOf("SPEAKER", "EARPIECE"))
            sendAudioRequest("AUDIO_ROUTE", route)
    }

    fun refreshGatewayAudio() {
        if (role == ROLE_REMOTE && connected) send("AUDIO_QUERY")
    }

    private fun gatewayStream(audio: AudioManager): Int =
        when {
            callState != CALL_IDLE || audio.mode == AudioManager.MODE_IN_CALL ->
                AudioManager.STREAM_VOICE_CALL
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ->
                AudioManager.STREAM_VOICE_CALL
            localWhatsappCallStates.values.any { it == WhatsAppCalls.ACTIVE } ->
                AudioManager.STREAM_MUSIC
            else -> AudioManager.STREAM_VOICE_CALL
        }

    private fun gatewayAudioStep(raw: String, requestId: String = "") {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val delta = raw.toIntOrNull() ?: return
        if (delta !in -1..1 || delta == 0) return
        val stream = gatewayStream(audio)
        try {
            val before = audio.getStreamVolume(stream)
            audio.adjustStreamVolume(stream, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            Hub.log("Gateway audio step: stream=$stream, $before -> ${audio.getStreamVolume(stream)}")
        } catch (e: Exception) {
            Hub.log("Gateway audio step failed: ${e.message ?: e.javaClass.simpleName}")
        } finally { gatewayAudioStatus() }
    }

    private fun gatewayAudioStatus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val device = if (android.os.Build.VERSION.SDK_INT >= 31) audio.communicationDevice else null
        val route = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            else -> "UNKNOWN"
        }
        send("AUDIO_STATUS", audio.getStreamVolume(gatewayStream(audio)).toString(),
            audio.getStreamMaxVolume(gatewayStream(audio)).toString(), route)
    }

    private fun gatewaySetAudioVolume(raw: String, requestId: String = "") {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val level = raw.toIntOrNull() ?: return
        val stream = gatewayStream(audio)
        try {
            val before = audio.getStreamVolume(stream)
            val target = level.coerceIn(0, audio.getStreamMaxVolume(stream))
            audio.setStreamVolume(stream, target, 0)
            val actual = audio.getStreamVolume(stream)
            Hub.log("Gateway volume: stream=$stream, mode=${audio.mode}, $before -> $actual / ${audio.getStreamMaxVolume(stream)}")
            send("AUDIO_RESULT", "VOLUME", if (actual == target) "APPLIED" else "NOT_APPLIED", requestId)
        } catch (e: Exception) {
            Hub.log("Gateway volume failed: ${e.javaClass.simpleName}")
            send("AUDIO_RESULT", "VOLUME", "ERROR", requestId)
        } finally {
            gatewayAudioStatus()
        }
    }

    private fun gatewaySetAudioRoute(route: String, requestId: String = "") {
        if (route !in setOf("SPEAKER", "EARPIECE")) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        val active = callState != CALL_IDLE ||
            localWhatsappCallStates.values.any { it != WhatsAppCalls.IDLE } ||
            audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION
        if (!active) {
            Hub.log("Gateway audio route not applied: no active communication")
            send("AUDIO_RESULT", "ROUTE", "NO_CALL", requestId)
            gatewayAudioStatus()
            return
        }
        try {
            Hub.log("Gateway audio route requested: $route; mode=${audio.mode}")
            val accepted = if (Build.VERSION.SDK_INT >= 31) {
                val type = if (route == "SPEAKER") AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val target = audio.availableCommunicationDevices.firstOrNull { it.type == type }
                target != null && audio.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = route == "SPEAKER"
                true
            }
            val actual = if (Build.VERSION.SDK_INT >= 31) {
                when (audio.communicationDevice?.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                    else -> "UNKNOWN"
                }
            } else {
                @Suppress("DEPRECATION")
                if (audio.isSpeakerphoneOn) "SPEAKER" else "EARPIECE"
            }
            val outcome = if (accepted && actual == route) "APPLIED" else "NOT_APPLIED"
            Hub.log("Gateway audio route: requested=$route, actual=$actual, outcome=$outcome")
            send("AUDIO_RESULT", "ROUTE", outcome, requestId)
        } catch (e: Exception) {
            Hub.log("Gateway audio route failed: ${e.javaClass.simpleName}")
            send("AUDIO_RESULT", "ROUTE", "ERROR", requestId)
        } finally {
            gatewayAudioStatus()
        }
    }

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

    /**
     * Remote: the last WhatsApp call request, as "PENDING: …", "OK: …", "TAP: …" or
     * "FAILED: …" followed by text for Home. null when there's nothing to show.
     */
    @Volatile var whatsappCallStatus: String? = null
        private set
    private var whatsappCallRequest = 0

    /** Gateway: a WhatsApp call started for the Remote that WhatsApp hasn't shown yet. */
    @Volatile private var pendingWhatsAppCall: WhatsAppDialer.Plan? = null
    @Volatile var downloadBytesPerSecond: Long = 0L
        private set
    @Volatile var uploadBytesPerSecond: Long = 0L
        private set
    private val rxTotal = AtomicLong(0L)
    private val txTotal = AtomicLong(0L)
    private var lastRxSample = 0L
    private var lastTxSample = 0L
    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficSampler = object : Runnable {
        override fun run() {
            if (!running) return
            val rx = rxTotal.get()
            val tx = txTotal.get()
            // Sampled every few seconds and shown per second; an idle link wakes the phone
            // rarely, and the notification is only touched when the numbers change.
            val down = if (connected) (rx - lastRxSample).coerceAtLeast(0L) * 1000L / TRAFFIC_SAMPLE_MS else 0L
            val up = if (connected) (tx - lastTxSample).coerceAtLeast(0L) * 1000L / TRAFFIC_SAMPLE_MS else 0L
            lastRxSample = rx; lastTxSample = tx
            if (down != downloadBytesPerSecond || up != uploadBytesPerSecond) {
                downloadBytesPerSecond = down
                uploadBytesPerSecond = up
                updateStatus()
            }
            trafficHandler.postDelayed(this, TRAFFIC_SAMPLE_MS)
        }
    }

    private val localWhatsappCallStates = ConcurrentHashMap<String, String>()
    private val peerWhatsappCallStates = ConcurrentHashMap<String, String>()

    /** Protocol version and app version the other phone reported in HELLO; null until HELLO arrives. */
    @Volatile var peerProtocol: Int? = null
        private set
    @Volatile var peerAppVersion: String = ""
        private set
    @Volatile private var helloReceived = false

    /** A warning to show when the two phones run different PCL versions, or null when they match. */
    val versionWarning: String?
        get() {
            if (!connected || !helloReceived) return null
            val theirs = peerProtocol
            val named = if (peerAppVersion.isNotEmpty()) " (${peerAppVersion})" else ""
            return when {
                theirs == Protocol.VERSION -> null
                theirs == null || theirs < Protocol.VERSION ->
                    "The other phone runs an older PCL$named. Update it so both phones run the same version; some features won't work until then."
                else -> "This phone runs an older PCL than the other phone$named. Update this phone from the Update tab."
            }
        }

    private val appVersion: String by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private var callControlRequest = 0

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

    /** Remote: the mirrored Gateway call (WhatsApp etc.) that is ringing here, by its encoded key. */
    @Volatile private var ringingMirrorKey: String? = null

    /** Remote: a Gateway call (WhatsApp etc.) shown on this phone, with the Gateway notification's buttons. */
    class MirrorCall(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val ringing: Boolean,
        /** When it connected (epoch ms), or null while it rings. */
        val connectedAt: Long?,
        /** Each button's index on the Gateway, and its label. */
        val buttons: List<Pair<Int, String>>,
    )

    /** Remote: mirrored Gateway calls on screen, by encoded key. */
    private val mirroredCalls = ConcurrentHashMap<String, MirrorCall>()

    /** Remote: the Gateway call (WhatsApp etc.) for the Calls tab: a ringing one first. */
    val liveMirrorCall: MirrorCall?
        get() = mirroredCalls.values.sortedByDescending { it.ringing }.firstOrNull()

    private val stopMirrorRingingLater = Runnable { stopMirrorRinging(null) }

    /** Remote: follows the Gateway's WhatsApp calls, for whose call it is and for Recents. */
    private val whatsappTracker by lazy {
        WhatsAppCallTracker(
            trafficHandler,
            numberFor = ::numberForName,
            onTakenByGateway = ::onWhatsAppTakenByGateway,
            onEnded = ::onWhatsAppCallEnded,
        )
    }

    /** Remote: this phone answered or placed the current SIM call, so it shows it until it ends. */
    @Volatile private var simCallHere = false
    private var simDialAt = 0L

    /** Remote: the current SIM call was answered or placed on the Gateway, so this phone stays quiet. */
    @Volatile var simCallOnGateway = false
        private set
    private var contactBuf: StringBuilder? = null

    val isActive: Boolean get() = running

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.service = this
        CallHistory.init(this)
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
                Prefs.of(this).edit().putBoolean(Prefs.LINK_ENABLED, true).apply()
                begin(
                    intent.getStringExtra(EXTRA_ROLE) ?: ROLE_REMOTE,
                    intent.getStringExtra(EXTRA_DEVICE),
                    intent.getStringExtra(EXTRA_REASON)
                )
            }
            null -> {
                // Android restarted the service after killing it (START_STICKY).
                val prefs = Prefs.of(this)
                if (!running && prefs.getBoolean(Prefs.LINK_ENABLED, false)) {
                    begin(
                        prefs.getString(Prefs.ROLE, ROLE_REMOTE) ?: ROLE_REMOTE,
                        prefs.getString(Prefs.PEER_DEVICE, null),
                        "after Android stopped it"
                    )
                } else if (!running) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Prefs.of(this).edit().putBoolean(Prefs.LINK_ENABLED, false).apply()
                shutdown()
                stopSelf()
            }
            ACTION_ANSWER -> if (running) answer() else stopSelf()
            ACTION_REJECT -> if (running) reject() else stopSelf()
            ACTION_HANGUP -> if (running) hangUp() else stopSelf()
            ACTION_AUDIO_DOWN, ACTION_AUDIO_UP -> if (running && role == ROLE_REMOTE && connected) {
                val delta = if (intent.action == ACTION_AUDIO_UP) 1 else -1
                Hub.log("Remote audio step: $delta")
                sendAudioRequest("AUDIO_STEP", delta.toString())
            }
            ACTION_AUDIO_SPEAKER -> if (running && role == ROLE_REMOTE && connected) {
                val target = if (gatewayAudioRoute == "SPEAKER") "EARPIECE" else "SPEAKER"
                Hub.log("Remote audio route: $target")
                requestGatewayRoute(target)
            }
            ACTION_CALL_BACK -> {
                nm.cancel(ID_MISSED)
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                if (running && number.isNotEmpty()) dial(number, intent.getStringExtra(EXTRA_NAME).orEmpty())
                else if (!running) stopSelf()
            }
            ACTION_NOTIF_DISMISSED -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                key?.let(::stopMirrorRinging)
                if (running && key != null) send("NOTIF_DISMISS", key) else if (!running) stopSelf()
            }
            ACTION_NOTIF_ACTION -> pressMirrorButton(
                intent.getStringExtra(EXTRA_KEY),
                intent.getIntExtra(EXTRA_ACTION_INDEX, -1),
                intent.getStringExtra(EXTRA_ACTION_LABEL).orEmpty(),
                intent.getStringExtra(EXTRA_NOTIF_PACKAGE).orEmpty(),
            )
            ACTION_WHATSAPP_CALL_BACK -> {
                nm.cancel(ID_MISSED_WHATSAPP)
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                if (running && number.isNotEmpty()) startWhatsAppCall(number, intent.getStringExtra(EXTRA_NAME).orEmpty())
                else if (!running) stopSelf()
            }
        }
        Hub.notifyUi()
        return if (running) START_STICKY else START_NOT_STICKY
    }

    private fun begin(newRole: String, device: String?, reason: String?) {
        if (running) shutdown()
        role = newRole
        deviceAddress = device
        running = true
        connectionState = "Starting…"
        rxTotal.set(0L); txTotal.set(0L); lastRxSample = 0L; lastTxSample = 0L
        downloadBytesPerSecond = 0L; uploadBytesPerSecond = 0L
        try {
            goForeground()
        } catch (e: Exception) {
            // Android can refuse a foreground service started from the background.
            running = false
            connectionState = "Stopped"
            Hub.log("Android didn't allow PCL to start ${reason ?: ""}: ${e.message ?: e.javaClass.simpleName}. Open PCL and press Start.")
            stopSelf()
            return
        }
        if (reason != null) Hub.log("Link restarted $reason")
        trafficHandler.removeCallbacks(trafficSampler)
        trafficHandler.postDelayed(trafficSampler, TRAFFIC_SAMPLE_MS)
        if (role == ROLE_GATEWAY) registerPhoneReceiver()
        startWorker()
    }

    // ---------------------------------------------------------------- public actions (UI)

    fun answer() {
        stopSimRinging()
        if (role == ROLE_GATEWAY) {
            gatewayAnswer()
        } else {
            simCallHere = true
            requestGateway("ANSWER")
        }
    }

    /**
     * Remote: press a button of a mirrored Gateway call (Answer, Decline, Hang up), from its
     * notification or the Calls tab. The Gateway presses the same button on its own notification.
     */
    fun pressMirrorButton(key: String?, index: Int, label: String, pkg: String) {
        // Whichever button it is, this phone stops ringing.
        key?.let(::stopMirrorRinging)
        if (WhatsAppCalls.isWhatsApp(pkg)) whatsappTracker.onButton(pkg, label)
        val staleAnswer = WhatsAppCalls.isWhatsApp(pkg) && WhatsAppCalls.isAnswer(label) &&
            peerWhatsappCallStates[pkg] == WhatsAppCalls.ACTIVE
        when {
            staleAnswer -> Hub.log("Ignored stale WhatsApp Answer action: a WhatsApp call is already active")
            !running || role != ROLE_REMOTE || !connected ->
                Hub.log("Third-party call action unavailable: Bluetooth link is not connected")
            key == null || index < 0 -> Hub.log("Third-party call action unavailable: the notification is no longer valid")
            else -> send("NOTIF_ACTION", key, index.toString(), encodeText(label))
        }
        Hub.notifyUi()
    }

    fun reject() {
        stopSimRinging()
        if (role == ROLE_REMOTE) CallHistory.onDeclinedHere()
        if (role == ROLE_GATEWAY) gatewayEnd(rejectRinging = true) else requestGateway("REJECT")
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("HANGUP")
    }

    /** [name] is only used for the Remote's call history. */
    fun dial(number: String, name: String = "") {
        if (role == ROLE_GATEWAY) {
            gatewayDial(number)
        } else {
            CallHistory.onDialRequested(number, name)
            simDialAt = SystemClock.elapsedRealtime()
            requestGateway("DIAL", number)
        }
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
     * Remote: asks the Gateway to start a WhatsApp voice call to [number] in its own WhatsApp.
     * The call runs on the Gateway; Home shows whether it started.
     */
    fun startWhatsAppCall(number: String, name: String = "") {
        if (role != ROLE_REMOTE) return
        if (!connected) {
            whatsappCallStatus = "FAILED: Bluetooth is disconnected."
            Hub.notifyUi()
            return
        }
        whatsappTracker.onRequested(number, name)
        val theirs = peerProtocol
        if (theirs == null || theirs < 4) {
            // An older Gateway can only post a "Continue in WhatsApp" notification.
            send("WHATSAPP_CHAT", number)
            whatsappCallStatus = "FAILED: The Gateway runs an older PCL that can't start calls. " +
                "Update it; for now a \"Continue in WhatsApp\" notification is waiting on the Gateway."
            Hub.notifyUi()
            return
        }
        whatsappCallStatus = "PENDING: Asking the Gateway to start the WhatsApp call…"
        send("WHATSAPP_CALL", number)
        Hub.log("Asked the Gateway to start a WhatsApp call to ${logCaller(number)}")
        val request = ++whatsappCallRequest
        trafficHandler.postDelayed({
            if (request == whatsappCallRequest && whatsappCallStatus?.startsWith("PENDING:") == true) {
                whatsappCallStatus = "FAILED: No answer from the Gateway."
                Hub.notifyUi()
            }
        }, WHATSAPP_CALL_TIMEOUT_MS)
        Hub.notifyUi()
    }

    /** Gateway: starts the WhatsApp call the Remote asked for and reports back. */
    private fun gatewayWhatsAppCall(number: String) {
        fun reply(code: String, text: String) {
            Hub.log("WhatsApp call for the Remote: $text")
            if (connected) send("WHATSAPP_CALL_RESULT", code, encodeText(text))
        }
        val busy = localWhatsappCallStates.values.any { it == WhatsAppCalls.ACTIVE || it == WhatsAppCalls.RINGING }
        when {
            busy -> return reply("FAILED", "The Gateway is already in a WhatsApp call.")
            callState != CALL_IDLE -> return reply("FAILED", "The Gateway is in a phone call.")
            pendingWhatsAppCall != null -> return reply("FAILED", "The Gateway is still starting the previous call.")
        }
        val plan = try {
            WhatsAppDialer.plan(this, number)
        } catch (e: WhatsAppDialer.CannotCall) {
            return reply("FAILED", e.message ?: "WhatsApp can't call this number.")
        }
        val name = lookupName(number)
        val display = if (name.isNotBlank()) name else "+${plan.digits}"
        val helper = WhatsappCallAccessibilityService.instance
        if (helper == null) {
            // Android only lets PCL open WhatsApp from the background through the helper.
            showWhatsAppCallNotification(plan, display)
            return reply(
                "TAP",
                "Turn on \"PCL WhatsApp call helper\" in the Gateway's Accessibility settings so it can start " +
                    "calls by itself. For now, tap \"Start WhatsApp call\" in the Gateway's notifications."
            )
        }
        pendingWhatsAppCall = plan
        try {
            helper.startWhatsAppCall(plan, name)
        } catch (e: Exception) {
            pendingWhatsAppCall = null
            helper.stopWhatsAppCall()
            showWhatsAppCallNotification(plan, display)
            return reply("FAILED", "Android didn't let PCL open WhatsApp (${e.message ?: e.javaClass.simpleName}).")
        }
        reply("PENDING", "Starting the WhatsApp call on the Gateway…")
        trafficHandler.postDelayed({
            if (pendingWhatsAppCall !== plan) return@postDelayed
            pendingWhatsAppCall = null
            WhatsappCallAccessibilityService.instance?.stopWhatsAppCall()
            val keyguard = getSystemService(KeyguardManager::class.java)
            val why = when {
                keyguard?.isDeviceLocked == true ->
                    "the Gateway is locked with a PIN, pattern or password. Unlock it and try again."
                plan.viaChat ->
                    "WhatsApp opened the chat, but its call button wasn't found. The number may not use WhatsApp."
                else -> "WhatsApp didn't start it."
            }
            showWhatsAppCallNotification(plan, display)
            reply("FAILED", "No call started: $why A \"Start WhatsApp call\" notification is waiting on the Gateway.")
        }, WhatsappCallAccessibilityService.CALL_WINDOW_MS + 2_000L)
    }

    /** Gateway: one tap on this notification starts the call (the manual route). */
    private fun showWhatsAppCallNotification(plan: WhatsAppDialer.Plan, display: String) {
        try {
            val open = Intent(this, WhatsAppCallActivity::class.java)
                .putExtra(WhatsAppCallActivity.EXTRA_TARGET, plan.intent)
            val pending = PendingIntent.getActivity(
                this, plan.digits.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = if (plan.viaChat) "Tap to open the chat with $display, then tap the call button."
            else "Tap to call $display in WhatsApp."
            nm.notify(ID_WHATSAPP_HANDOFF, Notification.Builder(this, CH_MIRROR)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Start WhatsApp call")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build())
        } catch (e: Exception) {
            Hub.log("Could not show the WhatsApp call notification: ${e.message ?: e.javaClass.simpleName}")
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
        val request = ++callControlRequest
        trafficHandler.postDelayed({
            // No answer at all: say so instead of waiting forever.
            if (request == callControlRequest && callControlError?.startsWith("PENDING:") == true) {
                callControlError = "FAILED: No reply from the Gateway. Check that both phones run the same PCL version."
                Hub.log("Call control: no reply from the Gateway")
                Hub.notifyUi()
            }
        }, CALL_CONTROL_TIMEOUT_MS)
        Hub.notifyUi()
    }

    /** Observe and relay call state only; never auto-answer, reject, or mute a call. */
    fun updateLocalWhatsappCallState(pkg: String, state: String) {
        // Only the Gateway has WhatsApp.
        if (role != ROLE_GATEWAY || !WhatsAppCalls.isWhatsApp(pkg) || state !in WhatsAppCalls.STATES) return
        val previous = localWhatsappCallStates[pkg] ?: WhatsAppCalls.IDLE
        // Notification updates often repeat the same state; only real changes are relayed.
        if (previous == state) return
        if (state == WhatsAppCalls.IDLE) localWhatsappCallStates.remove(pkg) else localWhatsappCallStates[pkg] = state
        Hub.log("Local WhatsApp call state detected for $pkg: $state")
        // Gateway: WhatsApp now shows the call PCL started for the Remote.
        if (role == ROLE_GATEWAY && state == WhatsAppCalls.ACTIVE) {
            pendingWhatsAppCall?.let {
                pendingWhatsAppCall = null
                WhatsappCallAccessibilityService.instance?.stopWhatsAppCall()
                nm.cancel(ID_WHATSAPP_HANDOFF)
                Hub.log("WhatsApp call for the Remote started")
                if (connected) send(
                    "WHATSAPP_CALL_RESULT", "OK",
                    encodeText("WhatsApp is calling on the Gateway. Talk through the Gateway or its Bluetooth headset.")
                )
            }
        }
        // The Remote uses this to follow the call: whose it is, and for its Recents.
        if (connected) sendWhatsAppState(pkg, state)
        Hub.notifyUi()
    }

    private fun sendWhatsAppState(pkg: String, state: String) {
        send("WHATSAPP_CALL_STATE", encodeText(pkg), state)
    }

    // ---------------------------------------------------------------- connection

    private fun startWorker() {
        val gen = ++generation
        worker = Thread({ workerLoop(gen) }, "pcl-link").also { it.start() }
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
                    sessionStartedAt = SystemClock.elapsedRealtime()
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
            if (sessionStartedAt != 0L && SystemClock.elapsedRealtime() - sessionStartedAt >= 15_000L) {
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
    private fun deviceName(a: BluetoothAdapter, address: String): String =
        try { a.getRemoteDevice(address).name } catch (_: Exception) { null } ?: address

    /** Gateway: waits for the selected Remote. Connections from any other paired device are refused. */
    @SuppressLint("MissingPermission")
    private fun acceptOne(gen: Int): BluetoothSocket? {
        val a = adapter()
        val allowed = deviceAddress
            ?: throw IOException("No Remote phone selected – choose it on the Home screen")
        setConn(false, "Waiting for ${deviceName(a, allowed)}…")
        val ss = a.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
        serverSocket = ss
        try {
            while (alive(gen)) {
                val s = ss.accept()
                if (!alive(gen)) {
                    s.close()
                    return null
                }
                val from = s.remoteDevice.address
                if (from.equals(allowed, ignoreCase = true)) return s
                Hub.log("Refused Bluetooth connection from ${deviceName(a, from)}: it isn't the selected Remote")
                try {
                    s.close()
                } catch (e: Exception) {
                }
            }
            return null
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
        helloReceived = false
        peerProtocol = null
        peerAppVersion = ""
        send("HELLO", role, Protocol.VERSION.toString(), appVersion)
        if (role == ROLE_GATEWAY) {
            // Replay every WhatsApp call state, IDLE included: the Remote may have missed
            // changes while the link was down. States go before the call notifications.
            for (pkg in WhatsAppCalls.PACKAGES) sendWhatsAppState(pkg, localWhatsappCallStates[pkg] ?: WhatsAppCalls.IDLE)
            sendCallState()
            sendContactsFingerprint()
            NotifListener.instance?.syncActiveNotifications()
        }
        val input = DataInputStream(BufferedInputStream(s.inputStream, 16384))
        try {
            while (alive(gen)) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > Protocol.MAX_FRAME) throw IOException("bad data from peer")
                val data = ByteArray(len)
                input.readFully(data)
                rxTotal.addAndGet(len.toLong() + 5L)
                when (type) {
                    Protocol.TYPE_CMD -> handleCommand(String(data, Charsets.UTF_8))
                }
            }
        } finally {
            if (writer === w) writer = null
            if (socket === s) socket = null
            connected = false
            peerWhatsappCallStates.clear()
            contactBuf = null
            helloReceived = false
            if (role == ROLE_REMOTE) {
                if (callControlError?.startsWith("PENDING:") == true) callControlError = "FAILED: Connection lost before Gateway confirmation"
                if (whatsappCallStatus?.startsWith("PENDING:") == true) whatsappCallStatus = "FAILED: Connection lost before the Gateway answered."
                stopRinging()
                callState = CALL_IDLE
                nm.cancel(ID_INCOMING)
                // Mirrored calls can't be answered or ended without the link; the Gateway
                // sends the ones still going again when it reconnects.
                ringingMirrorKey = null
                mirroredCalls.keys.forEach { nm.cancel(TAG_MIRROR, it.hashCode()) }
                mirroredCalls.clear()
                whatsappTracker.reset()
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
        trafficHandler.removeCallbacks(trafficSampler)
        downloadBytesPerSecond = 0L; uploadBytesPerSecond = 0L
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
        if (msg.toByteArray(Charsets.UTF_8).size > Protocol.MAX_FRAME) {
            // The peer would drop the whole link on an oversized frame.
            Hub.log("Not sent: ${parts.firstOrNull()} message is too large")
            return
        }
        try {
            io.execute {
                try {
                    w.writeCmd(msg)
                    txTotal.addAndGet(msg.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
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
        val cmd = p[0]
        val arg1 = p.getOrElse(1) { "" }
        // Each command is only meaningful on one side of the link.
        if ((cmd in Commands.GATEWAY_HANDLES && role != ROLE_GATEWAY) ||
            (cmd in Commands.REMOTE_HANDLES && role != ROLE_REMOTE)
        ) {
            Hub.log("Ignored $cmd: the ${if (role == ROLE_GATEWAY) "Gateway" else "Remote"} doesn't handle it")
            return
        }
        when (cmd) {
            "HELLO" -> {
                // Older versions sent only the role; they have no protocol number.
                peerProtocol = p.getOrNull(2)?.toIntOrNull()
                peerAppVersion = p.getOrNull(3).orEmpty().take(20)
                helloReceived = true
                Hub.log("Peer is the $arg1 phone (PCL ${peerAppVersion.ifEmpty { "older version" }})")
                versionWarning?.let { Hub.log("Version mismatch: $it") }
            }
            // Sent by older PCL versions, which switched the Gateway's Wi-Fi hotspot for calls.
            "HOTSPOT_STATE", "WHATSAPP_CALL_TRIGGER" -> Unit
            // gateway -> remote
            "CALL" -> {
                val previousState = callState
                callState = arg1.ifEmpty { CALL_IDLE }
                if (callState == CALL_RINGING) callControlError = null
                callNumber = p.getOrElse(2) { "" }
                callName = p.getOrElse(3) { "" }
                callDirection = p.getOrElse(4) { "" }
                CallHistory.onSimState(callState, callNumber, callName)
                    ?.takeIf { it.type == CallHistory.Type.MISSED }
                    ?.let(::showMissedCall)
                onRemoteCallUpdate(previousState)
            }
            // remote -> gateway
            "ANSWER" -> { gatewayAnswer(); sendCallResult() }
            "REJECT", "HANGUP" -> { gatewayEnd(rejectRinging = cmd == "REJECT"); sendCallResult() }
            "DIAL" -> { gatewayDial(arg1); sendCallResult() }
            "SYNC_CONTACTS" -> sendContacts()
            "AUDIO_QUERY" -> gatewayAudioStatus()
            "AUDIO_VOLUME" -> gatewaySetAudioVolume(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_STEP" -> gatewayAudioStep(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_ROUTE" -> gatewaySetAudioRoute(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_RESULT" -> {
                val id = p.getOrNull(3).orEmpty()
                pendingAudioRequests.remove(id)?.let(audioTimeoutHandler::removeCallbacks)
                Hub.log("Gateway audio result: ${arg1} — ${p.getOrNull(2).orEmpty()}")
            }
            "AUDIO_STATUS" -> {
                gatewayVolume = (arg1.toIntOrNull() ?: 0).coerceAtLeast(0)
                gatewayMaxVolume = (p.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                gatewayAudioRoute = p.getOrNull(3).orEmpty().takeIf { it in setOf("SPEAKER", "EARPIECE") } ?: "UNKNOWN"
            }
            "WHATSAPP_CHAT" -> {
                val ok = gatewayWhatsAppChat(arg1)
                send("WHATSAPP_CHAT_RESULT", if (ok) "OK" else "FAILED")
            }
            "WHATSAPP_CALL" -> gatewayWhatsAppCall(arg1)
            "WHATSAPP_CALL_RESULT" -> {
                val text = decodeText(p.getOrElse(2) { "" })
                // A late PENDING must not hide a result that already arrived.
                if (arg1 != "PENDING" || whatsappCallStatus?.startsWith("PENDING:") == true) {
                    whatsappCallStatus = "$arg1: $text"
                }
                Hub.log(if (arg1 == "OK") "Gateway started the WhatsApp call" else "WhatsApp call on Gateway: $text")
            }
            "WHATSAPP_CHAT_RESULT" -> Hub.log(
                if (arg1 == "OK") "WhatsApp chat handoff notification created on Gateway"
                else "Could not create WhatsApp chat handoff on Gateway"
            )
            "CALL_RESULT" -> {
                val detail = p.drop(2).joinToString("|")
                callControlError = if (arg1 == "FAILED") "FAILED: $detail" else null
                Hub.log(if (arg1 == "FAILED") "Gateway call control failed: $detail" else "Gateway accepted call-control request")
            }
            // contacts: gateway -> remote
            "CONTACTS_HASH" -> {
                val have = Prefs.of(this).getString(Prefs.CONTACTS_HASH, null)
                if (have == arg1) {
                    Hub.log("Contacts are up to date with the Gateway")
                } else {
                    send("SYNC_CONTACTS")
                    Hub.log("Gateway contacts changed; syncing ${p.getOrElse(2) { "" }} contacts")
                }
            }
            "CONTACTS_BEGIN" -> contactBuf = StringBuilder()
            "CONTACTS" -> {
                val buf = contactBuf
                val chunk = msg.substringAfter('|')
                if (buf != null && buf.length + chunk.length > MAX_CONTACT_CHARS) {
                    contactBuf = null
                    Hub.log("Contact sync stopped: the list is too large")
                } else {
                    buf?.append(chunk)
                }
            }
            "CONTACTS_END" -> {
                val data = contactBuf?.toString() ?: ""
                contactBuf = null
                ContactStore.saveSynced(this, data)
                Prefs.of(this).edit().putString(Prefs.CONTACTS_HASH, ContactStore.fingerprint(data)).apply()
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
                val reportedState = p.getOrNull(7 + count * 2).orEmpty().takeIf { it in WhatsAppCalls.STATES }
                showMirroredCall(arg1, pkg, decodeText(p.getOrElse(2) { "" }), decodeText(p.getOrElse(3) { "" }), decodeText(p.getOrElse(4) { "" }), indices, labels, reportedState)
            }
            "NOTIF_CLEAR_ORDINARY" -> if (role == ROLE_REMOTE) clearOrdinaryMirrorsLocal()
            "NOTIF_REMOVE" -> {
                nm.cancel(TAG_MIRROR, arg1.hashCode())
                ordinaryMirrors.remove(arg1.hashCode())
                mirroredCalls.remove(arg1)
                stopMirrorRinging(arg1)
            }
            "NOTIF_DISMISS" -> {
                val key = decodeKey(arg1)
                if (key != null) NotifListener.instance?.dismiss(key)
            }
            "NOTIF_ACTION" -> {
                val key = decodeKey(arg1)
                val index = p.getOrNull(2)?.toIntOrNull() ?: -1
                val label = decodeText(p.getOrNull(3).orEmpty())
                val ok = key != null && index >= 0 && (NotifListener.instance?.performAction(key, index) == true)
                if (ok) Hub.log("Remote pressed \"$label\" on a call notification")
                else Hub.log("Third-party call action failed or is no longer available")
                send("NOTIF_ACTION_RESULT", if (ok) "OK" else "FAILED")
            }
            "WHATSAPP_CALL_STATE" -> {
                val pkg = decodeText(arg1)
                val state = p.getOrNull(2).orEmpty()
                // Only the Gateway has WhatsApp; older Remotes may still send their own state.
                if (role == ROLE_REMOTE && WhatsAppCalls.isWhatsApp(pkg) && state in WhatsAppCalls.STATES) {
                    val previous = peerWhatsappCallStates[pkg] ?: WhatsAppCalls.IDLE
                    if (state == WhatsAppCalls.IDLE) peerWhatsappCallStates.remove(pkg) else peerWhatsappCallStates[pkg] = state
                    if (previous != state) Hub.log("Gateway WhatsApp call: ${state.lowercase()}")
                    whatsappTracker.onState(pkg, state)
                    // The Gateway's WhatsApp call ended, so the "calling" line is done.
                    if (peerWhatsappCallStates.isEmpty() && whatsappCallStatus?.startsWith("OK:") == true) {
                        whatsappCallStatus = null
                    }
                }
            }
            "NOTIF_ACTION_RESULT" -> Hub.log(if (arg1 == "OK") "Third-party call action sent to the app" else "Third-party call action failed")
            else -> Hub.log("Unknown command: $cmd")
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

    @Volatile private var simCallStartedMs = 0L

    fun simCallStartedAt(): Long = simCallStartedMs

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
            if (newState == CALL_RINGING && previousState == CALL_IDLE) { simCallStartedMs = System.currentTimeMillis(); callDirection = "INCOMING"; callNumber = ""; callName = "" }
            else if (newState == CALL_OFFHOOK && previousState == CALL_IDLE) { simCallStartedMs = System.currentTimeMillis(); callDirection = "OUTGOING" }
            else if (newState == CALL_IDLE) { simCallStartedMs = 0L; callDirection = "" }
            val gotNumber = !num.isNullOrEmpty() && num != callNumber
            if (!num.isNullOrEmpty()) {
                callNumber = num
                callName = lookupName(num)
            }
            val changed = newState != callState
            if (changed && newState == CALL_RINGING) callControlError = null
            callState = newState
            if (changed || gotNumber) {
                Hub.log("Phone call: $newState (${logCaller()})")
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
            @Suppress("DEPRECATION")
            val current = when (tm.callState) {
                TelephonyManager.CALL_STATE_RINGING -> CALL_RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            if (current != callState) {
                callState = current
                // A snapshot cannot reliably identify call direction or caller number.
                callDirection = ""
                if (current == CALL_IDLE) { callNumber = ""; callName = "" }
                Hub.log("Initial phone state: $current (details may be unavailable)")
                sendCallState()
            }
        } catch (e: Exception) {
            Hub.log("Initial phone-state query unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Gateway: enrich an active SIM call using metadata from the default dialer's notification. */
    private fun numbersMatch(first: String, second: String): Boolean {
        val a = ContactStore.digits(first)
        val b = ContactStore.digits(second)
        return a.isNotBlank() && b.isNotBlank() &&
            (a == b || (a.length >= 10 && b.length >= 10 &&
                (a.endsWith(b) || b.endsWith(a))))
    }

    fun updateCallerFromNotification(number: String, name: String) {
        if (role != ROLE_GATEWAY || callState == CALL_IDLE) return
        val safeNumber = number.trim().take(80)
        val safeName = name.trim().take(120)
        if (safeNumber.isEmpty() && safeName.isEmpty()) return
        if (safeNumber.isNotEmpty() && callNumber.isNotEmpty() &&
            !numbersMatch(callNumber, safeNumber)) return
        var changed = false
        if (callNumber.isEmpty() && safeNumber.isNotEmpty()) {
            callNumber = safeNumber
            changed = true
        }
        if (callName.isEmpty() && safeName.isNotEmpty()) {
            callName = safeName
            changed = true
        }
        if (callNumber.isEmpty() && callName.isNotBlank()) {
            val matched = resolveUniqueContactNumber(callName)
            if (matched.isNotBlank()) {
                callNumber = matched
                changed = true
                Hub.log("Caller number inferred from a unique contact match")
            }
        }
        if (changed) {
            Hub.log("Caller details recovered from phone notification")
            sendCallState()
            Hub.notifyUi()
        }
    }

    /**
     * Resolve a caller label from the default dialer's notification.
     * Prefer an exact full-name match, then a uniquely matching normalized label.
     * Never infer a number from a partial name or an ambiguous set of contacts.
     */
    private fun resolveUniqueContactNumber(name: String): String {
        if (name.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return ""
        fun normalize(value: String): String = java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ")
            .lowercase(java.util.Locale.ROOT)
        val target = normalize(name)
        if (target.length < 2 || target in setOf("unknown", "unknown number",
                "private number", "incoming call", "ongoing call", "phone",
                "restricted", "withheld", "no caller id")) return ""
        val contacts = ContactStore.readDevice(this)
        val exact = contacts.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else contacts.filter {
            normalize(it.name) == target
        }
        val numbers = matches.map { ContactStore.digits(it.number) }
            .filter { it.isNotBlank() }.distinct()
        if (numbers.size > 1) Hub.log("Caller label matches multiple contact numbers; leaving number unknown")
        return if (numbers.size == 1) matches.first().number else ""
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
            nm.notify(ID_WHATSAPP_HANDOFF, n)
            Hub.log("Posted WhatsApp chat handoff for ${logCaller(digits)}")
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
            Hub.log("Dialing ${logCaller(n)}")
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

    /**
     * How a caller appears in logs: never the full number or the contact name, because the
     * activity log can be copied and shared, and Logcat is readable over USB debugging.
     */
    private fun logCaller(number: String = callNumber): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length >= 2 -> "number ending ${digits.takeLast(2)}"
            callName.isNotEmpty() -> "a saved contact"
            else -> "unknown number"
        }
    }

    fun display(): String = when {
        callName.isNotEmpty() && callNumber.isNotEmpty() -> "$callName ($callNumber)"
        callName.isNotEmpty() -> callName
        callNumber.isNotEmpty() -> callNumber
        else -> "Unknown number"
    }

    // ---------------------------------------------------------------- remote: ringing

    /**
     * Remote: the Gateway's SIM call changed. This phone rings for every incoming call, but
     * once connected it only shows calls it answered or placed itself; a call answered or
     * placed on the Gateway is left to the Gateway.
     */
    private fun onRemoteCallUpdate(previous: String) {
        when (callState) {
            CALL_RINGING -> {
                if (previous != CALL_RINGING) {
                    simCallHere = false
                    simCallOnGateway = false
                    Hub.log("Incoming call (${logCaller()})")
                }
                showIncoming()
                startRinging()
            }
            CALL_OFFHOOK -> {
                stopSimRinging()
                if (previous == CALL_IDLE) {
                    // Placed from here if this phone asked to dial just now (or answered it
                    // before a link drop).
                    simCallHere = simCallHere || SystemClock.elapsedRealtime() - simDialAt < DIAL_WINDOW_MS
                }
                if (simCallHere) {
                    simCallOnGateway = false
                    showOngoingCall()
                    if (previous != CALL_OFFHOOK) Hub.log("Call in progress on gateway (${logCaller()})")
                } else {
                    simCallOnGateway = true
                    nm.cancel(ID_INCOMING)
                    if (previous != CALL_OFFHOOK) {
                        Hub.log(if (previous == CALL_RINGING) "Call answered on the Gateway" else "Call placed on the Gateway")
                    }
                }
            }
            else -> {
                simCallHere = false
                simCallOnGateway = false
                stopSimRinging()
                nm.cancel(ID_INCOMING)
                if (previous != CALL_IDLE) Hub.log("Call ended")
            }
        }
        updateStatus()
    }

    private fun showOngoingCall() {
        // Reuse the incoming-call notification ID so the Remote has one call notification,
        // updated in place when the Gateway call is answered or connects.
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call in progress")
            .setContentText(display())
            .setSubText("on the Gateway")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(CallHistory.connectedSince > 0)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - CallHistory.connectedSince).coerceAtLeast(0))
            .setContentIntent(openCallsPi())
            .addAction(action(android.R.drawable.ic_media_previous, "Volume −", actionPi(ACTION_AUDIO_DOWN, 31)))
            .addAction(action(android.R.drawable.ic_media_next, "Volume +", actionPi(ACTION_AUDIO_UP, 32)))
            .addAction(action(android.R.drawable.ic_lock_silent_mode_off, "Speaker / Earpiece", actionPi(ACTION_AUDIO_SPEAKER, 33)))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "End call", actionPi(ACTION_HANGUP, 13)))
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (e: Exception) {
            Hub.log("Could not show ongoing-call notification: ${e.message ?: e.javaClass.simpleName}")
        }
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

    /** The SIM call stopped ringing; keep ringing if a mirrored WhatsApp call still is. */
    private fun stopSimRinging() {
        if (ringingMirrorKey == null) stopRinging()
    }

    /** A mirrored call stopped ringing ([encKey] null: whichever one is ringing). */
    private fun stopMirrorRinging(encKey: String?) {
        val ringing = ringingMirrorKey ?: return
        if (encKey != null && encKey != ringing) return
        ringingMirrorKey = null
        trafficHandler.removeCallbacks(stopMirrorRingingLater)
        if (callState != CALL_RINGING) stopRinging()
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

    /**
     * Gateway, on connect: send only a fingerprint of the contact list. The Remote asks for
     * the full list only when it differs from its saved copy, instead of on every reconnect.
     */
    private fun sendContactsFingerprint() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – Remote keeps its saved contacts")
            return
        }
        val list = ContactStore.readDevice(this)
        val data = ContactStore.toChunks(list).joinToString("")
        send("CONTACTS_HASH", ContactStore.fingerprint(data), list.size.toString())
    }

    /** Gateway: send the full contact list (on request from the Remote). */
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
                    val frames = listOf("CONTACTS_BEGIN") + chunks.map { "CONTACTS|$it" } +
                        listOf("CONTACTS_END", "CONTACTS_RESULT|OK|${list.size}")
                    for (frame in frames) {
                        w.writeCmd(frame)
                        txTotal.addAndGet(frame.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
                    }
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

    /** Gateway: linked to the Remote, so call notifications can be relayed. */
    fun canRelay(): Boolean = running && connected && role == ROLE_GATEWAY

    /** Gateway: also send other notifications (messages etc.), as set in Settings. */
    fun shouldMirror(): Boolean = canRelay() && Prefs.of(this).getBoolean(Prefs.MIRROR, false)

    private fun encodeKey(k: String): String = encodeText(k)
    private fun decodeKey(k: String): String? = try { decodeText(k) } catch (_: Exception) { null }
    private fun encodeText(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decodeText(value: String): String = try { String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8) } catch (_: Exception) { "" }

    private val ordinaryMirrors = ConcurrentHashMap.newKeySet<Int>()

    fun clearOrdinaryMirrors() {
        if (role == ROLE_GATEWAY && connected) send("NOTIF_CLEAR_ORDINARY")
    }

    private fun clearOrdinaryMirrorsLocal() {
        ordinaryMirrors.forEach { nm.cancel(TAG_MIRROR, it) }
        ordinaryMirrors.clear()
    }

    /** Gateway: forward a notification with delimiter-safe fields. */
    fun mirrorPosted(key: String, app: String, title: String, text: String) {
        send("NOTIF_V2", encodeKey(key), encodeText(app), encodeText(title), encodeText(text.take(2000)))
    }

    /** Gateway: forward a third-party call notification and its available action buttons. */
    fun mirrorCallPosted(key: String, pkg: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>, state: String? = null) {
        // Keep the original field order; append the package so older peers can still parse actions.
        val fields = mutableListOf("CALL_NOTIF", encodeKey(key), encodeText(app), encodeText(title), encodeText(text.take(1200)))
        val count = minOf(3, actionIndices.size, actionLabels.size)
        fields += count.toString()
        for (i in 0 until count) { fields += actionIndices[i].toString(); fields += encodeText(actionLabels[i].take(40)) }
        fields += encodeText(pkg)
        fields += (state ?: "")
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
        try { nm.notify(TAG_MIRROR, id, n); ordinaryMirrors.add(id) } catch (_: Exception) { }
    }

    /**
     * Remote: mirror a Gateway call notification (WhatsApp etc.) with its buttons. While it
     * rings, this phone rings like for a SIM call; once answered from here it shows a call
     * timer. A call the Gateway took (answered or started there) isn't shown here at all.
     * The buttons act on the Gateway, where the call and its audio stay.
     */
    private fun showMirroredCall(encKey: String, pkg: String, app: String, title: String, text: String, actionIndices: List<Int>, actionLabels: List<String>, reportedState: String? = null) {
        val id = encKey.hashCode()
        val whatsapp = WhatsAppCalls.isWhatsApp(pkg)
        if (whatsapp) {
            whatsappTracker.onCaller(pkg, title)
            if (!whatsappTracker.shownHere(pkg)) {
                hideMirroredCall(encKey)
                return
            }
        }
        val state = reportedState ?: WhatsAppCalls.stateFromActionLabels(actionLabels)
        val ringing = state == WhatsAppCalls.RINGING
        val now = System.currentTimeMillis()
        val connectedAt = when {
            ringing -> null
            whatsapp -> whatsappTracker.connectedAt(pkg)
            else -> mirroredCalls[encKey]?.connectedAt ?: if (state == WhatsAppCalls.ACTIVE) now else null
        }
        val buttons = (0 until minOf(3, actionIndices.size, actionLabels.size)).map { actionIndices[it] to actionLabels[it] }
        mirroredCalls[encKey] = MirrorCall(encKey, pkg, app, title.ifEmpty { app }, ringing, connectedAt, buttons)

        val deletePi = PendingIntent.getService(this, id, Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_DISMISSED).putExtra(EXTRA_KEY, encKey), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // The calls channel is silent: PCL plays the ringtone itself while it rings.
        val builder = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call).setSubText("$app · on the Gateway")
            .setContentTitle(title.ifEmpty { app }).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_CALL)
            .setWhen(connectedAt ?: now).setShowWhen(true).setUsesChronometer(connectedAt != null)
            .setOngoing(true)
            .setContentIntent(openCallsPi()).setDeleteIntent(deletePi)
        // On a locked phone, a ringing call opens the Calls tab with Answer and Decline.
        if (ringing) builder.setFullScreenIntent(openCallsPi(), true)
        for ((sourceIndex, label) in buttons) {
            val lower = label.lowercase()
            val iconId = when {
                WhatsAppCalls.isAnswer(label) || lower.contains("join") -> android.R.drawable.sym_action_call
                WhatsAppCalls.isDecline(label) || WhatsAppCalls.isEnd(label) -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.ic_menu_view
            }
            val pi = PendingIntent.getService(this, id xor (sourceIndex + 0x341),
                Intent(this, LinkService::class.java).setAction(ACTION_NOTIF_ACTION).putExtra(EXTRA_KEY, encKey).putExtra(EXTRA_ACTION_INDEX, sourceIndex).putExtra(EXTRA_ACTION_LABEL, label).putExtra(EXTRA_NOTIF_PACKAGE, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(action(iconId, label, pi))
        }
        if (!ringing && connected) {
            builder.addAction(action(android.R.drawable.ic_media_previous, "Volume −", actionPi(ACTION_AUDIO_DOWN, 31)))
            builder.addAction(action(android.R.drawable.ic_media_next, "Volume +", actionPi(ACTION_AUDIO_UP, 32)))
            builder.addAction(action(android.R.drawable.ic_lock_silent_mode_off, "Speaker / Earpiece", actionPi(ACTION_AUDIO_SPEAKER, 33)))
        }
        try { nm.notify(TAG_MIRROR, id, builder.build()) } catch (_: Exception) { }

        if (ringing) {
            if (ringingMirrorKey != encKey) {
                ringingMirrorKey = encKey
                startRinging()
                Hub.log("Ringing for a $app call on the Gateway")
                // Never ring forever if the Gateway's notification update is lost.
                trafficHandler.removeCallbacks(stopMirrorRingingLater)
                trafficHandler.postDelayed(stopMirrorRingingLater, MIRROR_RING_LIMIT_MS)
            }
        } else {
            stopMirrorRinging(encKey)
        }
    }

    private fun hideMirroredCall(encKey: String) {
        nm.cancel(TAG_MIRROR, encKey.hashCode())
        mirroredCalls.remove(encKey)
        stopMirrorRinging(encKey)
    }

    /** Remote: the Gateway answered or started this WhatsApp call itself, so this phone lets go. */
    private fun onWhatsAppTakenByGateway(pkg: String) {
        mirroredCalls.values.filter { it.pkg == pkg }.forEach { hideMirroredCall(it.key) }
        Hub.log("WhatsApp call taken on the Gateway; not shown here")
        Hub.notifyUi()
    }

    /** Remote: a Gateway WhatsApp call ended: add it to Recents, and say so if it was missed. */
    private fun onWhatsAppCallEnded(call: CallHistory.Record) {
        CallHistory.add(call)
        if (call.type == CallHistory.Type.MISSED) showMissedCall(call)
        Hub.notifyUi()
    }

    /** A synced (or this phone's) contact's number by exact name, or "". */
    private fun numberForName(name: String): String =
        if (name.isBlank()) "" else ContactStore.all(this).firstOrNull { it.name.equals(name, ignoreCase = true) }?.number.orEmpty()

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        // Channels from before the rename; Android keeps them until deleted.
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
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

    /** Opens PCL on the Calls tab, where the live call has its buttons. */
    private fun openCallsPi(): PendingIntent = PendingIntent.getActivity(
        this, 23,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TAB, "calls"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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

    private fun formatTrafficRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    private fun statusNotification(): Notification {
        val text = if (connected) "$connectionState · Down ${formatTrafficRate(downloadBytesPerSecond)} / Up ${formatTrafficRate(uploadBytesPerSecond)}" else "$connectionState · Down/Up rates unavailable while disconnected"
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (role == ROLE_GATEWAY) "PCL · Gateway" else "PCL · Remote")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPi())
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Quit", actionPi(ACTION_STOP, 10)))
            .build()
    }

    private fun goForeground() {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID_STATUS, n)
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

    /** Remote: a Gateway call (SIM or WhatsApp) rang and nobody answered. */
    private fun showMissedCall(call: CallHistory.Record) {
        val who = call.name.ifEmpty { call.number.ifEmpty { "Unknown number" } }
        val open = openCallsPi()
        val builder = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(if (call.whatsapp) "Missed WhatsApp call" else "Missed call")
            .setContentText(who)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setWhen(call.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (call.number.isNotEmpty()) {
            val callBack = PendingIntent.getService(
                this, if (call.whatsapp) 24 else 22,
                Intent(this, LinkService::class.java)
                    .setAction(if (call.whatsapp) ACTION_WHATSAPP_CALL_BACK else ACTION_CALL_BACK)
                    .putExtra(EXTRA_NUMBER, call.number).putExtra(EXTRA_NAME, call.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(action(android.R.drawable.sym_action_call, "Call back", callBack))
        }
        try {
            nm.notify(if (call.whatsapp) ID_MISSED_WHATSAPP else ID_MISSED, builder.build())
        } catch (e: Exception) {
            Hub.log("Could not show missed-call notification: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun showIncoming() {
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(display())
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openCallsPi())
            .setFullScreenIntent(openCallsPi(), true)
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
