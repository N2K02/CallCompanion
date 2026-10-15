package com.vgs.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import rikka.shizuku.Shizuku

/** VGS client for an optional shell-privileged cellular-call capture helper. */
object VgsCallAudioAccess {
    private const val REQUEST_CODE = 4826
    private const val USER_SERVICE_VERSION = 1

    @Volatile private var appContext: Context? = null
    @Volatile private var frameSink: ((ByteArray) -> Unit)? = null
    @Volatile private var stateSink: ((String) -> Unit)? = null
    @Volatile private var agent: IVgsPrivilegedAudioAgent? = null
    @Volatile private var captureRequested = false
    @Volatile private var binding = false
    @Volatile private var initialized = false

    private val listener = object : IVgsCallAudioListener.Stub() {
        override fun onPcmFrame(pcm: ByteArray) { frameSink?.invoke(pcm) }
        override fun onCaptureState(state: String) { stateSink?.invoke(state) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            agent = IVgsPrivilegedAudioAgent.Stub.asInterface(service)
            binding = false
            stateSink?.invoke("Privileged helper connected (shell process)")
            if (captureRequested) startRemoteCapture()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            agent = null
            binding = false
            stateSink?.invoke("ERROR: privileged helper disconnected")
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext!!.packageName, VgsPrivilegedCallAudioAgent::class.java.name)
        ).daemon(true).processNameSuffix("vgs-call-audio").version(USER_SERVICE_VERSION)
    }

    @Synchronized
    fun initialize(context: Context, frameSink: (ByteArray) -> Unit, stateSink: (String) -> Unit) {
        appContext = context.applicationContext
        this.frameSink = frameSink
        this.stateSink = stateSink
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky {
            this.stateSink?.invoke("Shizuku server detected")
            bindIfAllowed()
        }
        Shizuku.addBinderDeadListener {
            agent = null
            binding = false
            this.stateSink?.invoke("ERROR: Shizuku stopped; helper unavailable")
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) return@addRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                this.stateSink?.invoke("Shizuku permission granted")
                bindIfAllowed()
            } else this.stateSink?.invoke("ERROR: Shizuku permission denied")
        }
    }

    fun requestPermission(context: Context) {
        if (appContext == null) initialize(context, frameSink = {}, stateSink = { Hub.log("Call-audio helper: $it") })
        if (!Shizuku.pingBinder()) {
            stateSink?.invoke("Shizuku is not running on this gateway. Start it here, then try again; the Huawei remote does not need it.")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            stateSink?.invoke("Shizuku permission already granted")
            bindIfAllowed()
        } else Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Returns false if the privileged helper is not yet authorised/available. */
    fun startCapture(): Boolean {
        captureRequested = true
        if (!Shizuku.pingBinder()) {
            stateSink?.invoke("ERROR: Shizuku is not running on the gateway")
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            stateSink?.invoke("ERROR: grant VGS permission in Shizuku setup first")
            return false
        }
        val current = agent
        if (current != null) return try {
            current.startCapture(listener)
            true
        } catch (e: RemoteException) {
            stateSink?.invoke("ERROR: helper start failed: ${e.message}")
            false
        }
        bindIfAllowed()
        stateSink?.invoke("Connecting to privileged helper…")
        return true
    }

    fun stopCapture() {
        captureRequested = false
        try { agent?.stopCapture() } catch (e: Exception) {
            stateSink?.invoke("Helper stop failed: ${e.message}")
        }
    }

    private fun bindIfAllowed() {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ||
            agent != null || binding || appContext == null
        ) return
        try {
            binding = true
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            binding = false
            stateSink?.invoke("ERROR: could not bind privileged helper: ${e.message}")
        }
    }

    private fun startRemoteCapture() {
        try { agent?.startCapture(listener) } catch (e: Exception) {
            stateSink?.invoke("ERROR: could not start capture: ${e.message}")
        }
    }
}
