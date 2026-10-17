package com.vgs.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.FileOutputStream
import java.io.IOException

/**
 * App-side bridge to the shell process. Only the gateway binds this service.
 * Shizuku permission is requested by the gateway setup checklist, never as an Android runtime permission.
 */
class UplinkInjectionClient(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var remote: IVgsUplinkInjectionService? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var writerFd: ParcelFileDescriptor? = null
    @Volatile private var output: FileOutputStream? = null

    val isInjecting: Boolean get() = output != null

    fun bind(): Boolean {
        if (!isShizukuReadyAndGranted()) return false
        if (connection != null) return true

        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, UplinkInjectionService::class.java.name)
        ).daemon(false)
            .processNameSuffix("VgsUplinkInjection")
            .debuggable(false)
            .version(1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                remote = binder?.let { IVgsUplinkInjectionService.Stub.asInterface(it) }
                Hub.log(if (remote != null) "Shizuku uplink service connected" else "Shizuku returned an empty uplink service")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                stop()
                remote = null
                connection = null
                Hub.log("Shizuku service disconnected; uplink injection stopped")
            }
        }
        connection = conn
        return try {
            Shizuku.bindUserService(args, conn)
            true
        } catch (e: Exception) {
            connection = null
            Hub.log("Could not bind Shizuku uplink service: ${e.message}")
            false
        }
    }

    fun start(): Boolean {
        if (isInjecting) return true
        val service = remote ?: run {
            bind()
            Hub.log("Shizuku uplink service is connecting; stop and start the uplink test again in a moment")
            return false
        }
        return try {
            if (!service.isPstnCallAudioInterceptable()) {
                Hub.log("This gateway does not expose telephony audio injection routes")
                return false
            }
            val fd = service.startUplinkInjection()
                ?: throw IOException("Android did not create an uplink audio pipe")
            writerFd = fd
            output = FileOutputStream(fd.fileDescriptor)
            Hub.log("Experimental cellular uplink injection started")
            true
        } catch (e: Exception) {
            closePipe()
            Hub.log("Uplink injection unavailable: ${e.message}")
            false
        }
    }

    @Synchronized
    fun writePcm(data: ByteArray) {
        val stream = output ?: throw IOException("uplink injection is not active")
        stream.write(data)
    }

    @Synchronized
    fun stop() {
        closePipe()
        runCatching { remote?.stopUplinkInjection() }
    }

    fun unbind() {
        stop()
        val conn = connection ?: return
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, UplinkInjectionService::class.java.name)
        ).daemon(false)
            .processNameSuffix("VgsUplinkInjection")
            .debuggable(false)
            .version(1)
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        connection = null
        remote = null
    }

    @Synchronized
    private fun closePipe() {
        runCatching { output?.close() }
        output = null
        runCatching { writerFd?.close() }
        writerFd = null
    }

    companion object {
        fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun isShizukuReadyAndGranted(): Boolean = try {
            isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        fun requestPermission(requestCode: Int = 4207) {
            if (isShizukuAvailable() && !isShizukuReadyAndGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }
}
