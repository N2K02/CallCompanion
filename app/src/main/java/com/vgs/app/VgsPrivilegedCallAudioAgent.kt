package com.vgs.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.RemoteException
import androidx.annotation.Keep
import kotlin.math.max

/** Independent VGS capture agent, instantiated by Shizuku in a shell-UID process. */
@Keep
class VgsPrivilegedCallAudioAgent : IVgsPrivilegedAudioAgent.Stub {
    @Volatile private var capturing = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var callback: IVgsCallAudioListener? = null

    @Keep constructor() : super()
    @Keep constructor(context: Context) : this()

    override fun startCapture(listener: IVgsCallAudioListener) {
        synchronized(this) {
            callback = listener
            if (capturing) {
                notifyState("CAPTURING: VOICE_CALL already active")
                return
            }
            capturing = true
            captureThread = Thread({ captureLoop() }, "vgs-privileged-call-audio").also { it.start() }
        }
    }

    override fun stopCapture() {
        capturing = false
        try { recorder?.stop() } catch (_: Exception) {}
        captureThread?.interrupt()
    }

    override fun isCapturing(): Boolean = capturing

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var local: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("unsupported 8 kHz PCM input ($minBuffer)")
            local = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, FRAME_BYTES * 8)
            )
            recorder = local
            if (local.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("VOICE_CALL AudioRecord did not initialise")
            local.startRecording()
            if (local.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("VOICE_CALL source did not start")
            notifyState("CAPTURING: VOICE_CALL at 8 kHz PCM mono")
            val frame = ByteArray(FRAME_BYTES)
            while (capturing) {
                val count = local.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val chunk = if (count == frame.size) frame.copyOf() else frame.copyOf(count)
                    try { callback?.onPcmFrame(chunk) } catch (e: RemoteException) {
                        throw IllegalStateException("VGS receiver disconnected", e)
                    }
                } else if (count < 0) throw IllegalStateException("AudioRecord.read failed: $count")
            }
        } catch (e: Exception) {
            if (capturing) notifyState("ERROR: VOICE_CALL capture failed: ${e.message}")
        } finally {
            capturing = false
            try { local?.stop() } catch (_: Exception) {}
            local?.release()
            if (recorder === local) recorder = null
            captureThread = null
            notifyState("STOPPED")
        }
    }

    private fun notifyState(message: String) {
        try { callback?.onCaptureState(message) } catch (_: Exception) {}
    }

    private companion object {
        const val SAMPLE_RATE = 8000
        const val FRAME_BYTES = 320
    }
}
