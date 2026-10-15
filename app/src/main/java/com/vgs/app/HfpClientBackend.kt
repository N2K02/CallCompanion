package com.vgs.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Experimental HFP Hands-Free (HF) client backend.
 *
 * Android's HEADSET_CLIENT profile is hidden/system-level on many releases. We use reflection
 * deliberately so the app can still build against the public SDK. This does NOT grant the
 * privileged permissions needed by every OEM build. The backend reports failure clearly and
 * never falls back to AudioTrack injection or an acoustic relay.
 *
 * The gateway remains the normal Android HFP Audio Gateway (AG) during its SIM call. VGS's
 * existing RFCOMM socket continues to carry companion commands, contacts and notifications.
 */
class HfpClientBackend(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        // BluetoothProfile.HEADSET_CLIENT is hidden in the public SDK. Value in AOSP.
        private const val PROFILE_HEADSET_CLIENT = 16
    }

    private val main = Handler(Looper.getMainLooper())
    private val profileTimeout = Runnable {
        if (!closed && proxy == null) {
            onStatus("HFP client profile did not become available; this firmware may disable HFP HF support")
        }
    }
    @Volatile private var proxy: BluetoothProfile? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var pendingCallState: String = LinkService.CALL_IDLE
    @Volatile private var closed = false
    private var adapter: BluetoothAdapter? = null
    @Volatile private var audioRequested = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            if (closed || profile != PROFILE_HEADSET_CLIENT) return
            main.removeCallbacks(profileTimeout)
            proxy = service
            onStatus("HFP client profile is available; trying to connect to gateway…")
            connectDevice()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_HEADSET_CLIENT) {
                proxy = null
                onStatus("HFP client profile disconnected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(gatewayAddress: String?) {
        closed = false
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("HFP unavailable: Bluetooth Connect permission is missing")
            return
        }
        if (gatewayAddress.isNullOrBlank()) {
            onStatus("HFP unavailable: gateway device address is missing")
            return
        }
        try {
            val a = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (a == null || !a.isEnabled) {
                onStatus("HFP unavailable: Bluetooth is off or unsupported")
                return
            }
            adapter = a
            device = a.getRemoteDevice(gatewayAddress)
            onStatus("Checking Android HFP Hands-Free client support…")
            val requested = a.getProfileProxy(context, listener, PROFILE_HEADSET_CLIENT)
            if (!requested) {
                onStatus("HFP client profile is not exposed by this phone's Bluetooth stack")
            } else {
                // Some stacks accept the request but never bind because the profile is disabled.
                main.removeCallbacks(profileTimeout)
                main.postDelayed(profileTimeout, 4000L)
            }
        } catch (e: SecurityException) {
            onStatus("HFP blocked by Android/OEM Bluetooth permissions: ${e.message ?: "permission denied"}")
        } catch (e: Exception) {
            onStatus("HFP probe failed: ${e.javaClass.simpleName}: ${e.message ?: "unsupported"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice() {
        val p = proxy ?: return
        val d = device ?: return
        try {
            val state = invokeDeviceMethod(p, "getConnectionState", d) as? Int
            if (state == BluetoothProfile.STATE_CONNECTED) {
                onStatus("HFP client connected to gateway")
                applyCallAudioState()
                return
            }
            val result = invokeDeviceMethod(p, "connect", d) as? Boolean
            onStatus(
                if (result == true) "HFP connection requested; waiting for Bluetooth stack"
                else "HFP connect request was rejected by Android/OEM firmware"
            )
            if (result == true) scheduleConnectionCheck()
        } catch (e: Exception) {
            onStatus("HFP connect unavailable: ${rootMessage(e)}")
        }
    }

    private fun scheduleConnectionCheck(attempt: Int = 0) {
        if (closed || attempt >= 10) {
            if (!closed && pendingCallState == LinkService.CALL_OFFHOOK) {
                onStatus("HFP did not connect in time for the active call")
            }
            return
        }
        main.postDelayed({
            if (closed) return@postDelayed
            val p = proxy
            val d = device
            if (p == null || d == null) return@postDelayed
            try {
                val state = invokeDeviceMethod(p, "getConnectionState", d) as? Int
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("HFP client connected to gateway")
                    applyCallAudioState()
                } else {
                    scheduleConnectionCheck(attempt + 1)
                }
            } catch (_: Exception) {
                scheduleConnectionCheck(attempt + 1)
            }
        }, 1000L)
    }

    /** Called from the companion's gateway call-state messages. */
    fun onCellularCallState(state: String) {
        pendingCallState = state
        applyCallAudioState()
    }

    private fun applyCallAudioState() {
        val p = proxy ?: return
        val d = device ?: return
        if (closed) return
        try {
            val state = invokeDeviceMethod(p, "getConnectionState", d) as? Int
            if (state != BluetoothProfile.STATE_CONNECTED) return
            when (pendingCallState) {
                LinkService.CALL_OFFHOOK -> {
                    if (!audioRequested) {
                        val result = invokeProfileMethod(p, "connectAudio", d) as? Boolean
                        audioRequested = result == true
                        onStatus(
                            if (audioRequested) "HFP call audio connection requested; waiting for SCO state"
                            else "HFP audio request rejected; cellular audio may not be available"
                        )
                    }
                }
                LinkService.CALL_IDLE -> {
                    if (audioRequested) {
                        try { invokeProfileMethod(p, "disconnectAudio", d) } catch (_: Exception) {}
                    }
                    audioRequested = false
                    onStatus("HFP connected; no active cellular call")
                }
                // Do not open audio while ringing. VGS's Answer command answers through gateway Telecom.
            }
        } catch (e: Exception) {
            onStatus("HFP audio control unavailable: ${rootMessage(e)}")
        }
    }

    private fun invokeDeviceMethod(target: Any, name: String, d: BluetoothDevice): Any? =
        invokeProfileMethod(target, name, d)

    /**
     * HFP client API signatures differ across Android generations: most device operations take
     * BluetoothDevice, while older branches exposed connectAudio/disconnectAudio with no args.
     * Prefer the current per-device form and retain the legacy zero-argument fallback.
     */
    private fun invokeProfileMethod(target: Any, name: String, d: BluetoothDevice): Any? {
        val methods = target.javaClass.methods.toList() + target.javaClass.declaredMethods.toList()
        val method = methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 &&
                BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
          ?: throw NoSuchMethodException("$name(BluetoothDevice) or $name()")
        try { method.isAccessible = true } catch (_: Exception) {}
        return if (method.parameterTypes.isEmpty()) method.invoke(target) else method.invoke(target, d)
    }

    private fun rootMessage(t: Throwable): String {
        var x = t
        while (x.cause != null && x.cause !== x) x = x.cause!!
        return "${x.javaClass.simpleName}: ${x.message ?: "unsupported"}"
    }

    fun stop() {
        closed = true
        main.removeCallbacksAndMessages(null)
        audioRequested = false
        val a = adapter
        val p = proxy
        if (a != null && p != null) {
            try {
                device?.let { d ->
                    try { invokeDeviceMethod(p, "disconnectAudio", d) } catch (_: Exception) {}
                    try { invokeDeviceMethod(p, "disconnect", d) } catch (_: Exception) {}
                }
                a.closeProfileProxy(PROFILE_HEADSET_CLIENT, p)
            } catch (_: Exception) {}
        }
        proxy = null
        device = null
        adapter = null
    }
}
