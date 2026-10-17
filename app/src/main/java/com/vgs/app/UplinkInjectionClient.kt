package com.shizucallcompanion.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * App-side bridge to the shell process. Only the gateway binds this service.
 * Shizuku permission is requested by the gateway setup checklist, never as an Android runtime permission.
 */
class UplinkInjectionClient(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var remote: IShizuUplinkInjectionService? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var writerFd: ParcelFileDescriptor? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var downlinkReadFd: ParcelFileDescriptor? = null
    @Volatile private var downlinkThread: Thread? = null

    val isInjecting: Boolean get() = output != null
    val isCapturingDownlink: Boolean get() = downlinkReadFd != null

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
                remote = binder?.let { IShizuUplinkInjectionService.Stub.asInterface(it) }
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

    fun startDownlinkCapture(onFrame: (ByteArray) -> Unit): Boolean {
        if (isCapturingDownlink) return true
        val service = remote ?: run {
            bind()
            Hub.log("Shizuku audio service is connecting; retry caller-audio capture in a moment")
            return false
        }
        return try {
            val fd = service.startDownlinkCapture()
                ?: throw IOException("Android did not create a downlink audio pipe")
            downlinkReadFd = fd
            downlinkThread = Thread({
                try {
                    FileInputStream(fd.fileDescriptor).use { input ->
                        val frame = ByteArray(320)
                        while (downlinkReadFd === fd) {
                            var offset = 0
                            while (offset < frame.size) {
                                val count = input.read(frame, offset, frame.size - offset)
                                if (count < 0) return@Thread
                                if (count == 0) continue
                                offset += count
                            }
                            onFrame(frame.copyOf())
                        }
                    }
                } catch (e: Exception) {
                    if (downlinkReadFd === fd) Hub.log("Caller-audio capture stopped: ${e.message}")
                } finally {
                    synchronized(this) {
                        if (downlinkReadFd === fd) {
                            downlinkReadFd = null
                            downlinkThread = null
                            runCatching { fd.close() }
                            runCatching { remote?.stopDownlinkCapture() }
                        }
                    }
                }
            }, "vgs-downlink-reader").apply { isDaemon = true; start() }
            Hub.log("Experimental cellular caller-audio capture started")
            true
        } catch (e: Exception) {
            stopDownlinkCapture()
            Hub.log("Caller-audio capture unavailable: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stopDownlinkCapture() {
        val fd = downlinkReadFd
        downlinkReadFd = null
        downlinkThread?.interrupt()
        downlinkThread = null
        runCatching { fd?.close() }
        runCatching { remote?.stopDownlinkCapture() }
    }

    @Synchronized
    fun stop() {
        stopDownlinkCapture()
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
        fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= 33

        fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun isShizukuReadyAndGranted(): Boolean = try {
            isPlatformSupported() && isShizukuAvailable() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        fun requestPermission(requestCode: Int = 4207) {
            if (isPlatformSupported() && isShizukuAvailable() && !isShizukuReadyAndGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }
}
