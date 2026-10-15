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
    @Volatile private var proxy: BluetoothProfile? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var pendingCallState: String = LinkService.CALL_IDLE
    @Volatile private var closed = false
    private var adapter: BluetoothAdapter? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            if (closed || profile != PROFILE_HEADSET_CLIENT) return
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
        } catch (e: Exception) {
            onStatus("HFP connect unavailable: ${rootMessage(e)}")
        }
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
                    val result = invokeDeviceMethod(p, "connectAudio", d)
                    onStatus(
                        if (result == true) "HFP call audio connection requested"
                        else "HFP audio request rejected; cellular audio may not be available"
                    )
                }
                LinkService.CALL_IDLE -> {
                    try { invokeDeviceMethod(p, "disconnectAudio", d) } catch (_: Exception) {}
                    onStatus("HFP connected; no active cellular call")
                }
                // Do not open audio while ringing. VGS's Answer command answers through gateway Telecom.
            }
        } catch (e: Exception) {
            onStatus("HFP audio control unavailable: ${rootMessage(e)}")
        }
    }

    private fun invokeDeviceMethod(target: Any, name: String, d: BluetoothDevice): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 &&
                BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: target.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 &&
                BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: throw NoSuchMethodException("$name(BluetoothDevice)")
        try { method.isAccessible = true } catch (_: Exception) {}
        return method.invoke(target, d)
    }

    private fun rootMessage(t: Throwable): String {
        var x = t
        while (x.cause != null && x.cause !== x) x = x.cause!!
        return "${x.javaClass.simpleName}: ${x.message ?: "unsupported"}"
    }

    fun stop() {
        closed = true
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
