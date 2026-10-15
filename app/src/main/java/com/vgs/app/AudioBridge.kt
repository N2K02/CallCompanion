package com.vgs.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Streams microphone audio to the other phone and plays what comes back. */
class AudioBridge(
    private val context: Context,
    /** Microphone sources to try in order. */
    private val sources: () -> List<Int>,
    private val sender: (ByteArray, Int) -> Unit,
) {
    companion object {
        const val RATE = 8000
        const val FRAME_BYTES = 320 // 20 ms of 8 kHz 16-bit mono
    }

    @Volatile
    private var running = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var restoreNormalMode = false

    @Volatile
    var speakerOn = true
        private set

    @Volatile private var rxFrames = 0

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        queue.clear()
        rxFrames = 0
        val am = context.getSystemService(AudioManager::class.java)!!
        // Do not override MODE_IN_CALL: changing it during a SIM call can mute the call microphone.
        restoreNormalMode = am.mode == AudioManager.MODE_NORMAL
        if (restoreNormalMode) am.mode = AudioManager.MODE_IN_COMMUNICATION
        AudioRoute.setSpeaker(context, speakerOn)
        recordThread = Thread({ recordLoop() }, "vgs-record").also { it.start() }
        playThread = Thread({ playLoop() }, "vgs-play").also { it.start() }
        Hub.log("Voice link ON")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        recordThread?.interrupt()
        playThread?.interrupt()
        recordThread = null
        playThread = null
        queue.clear()
        AudioRoute.clear(context)
        if (restoreNormalMode) context.getSystemService(AudioManager::class.java)!!.mode = AudioManager.MODE_NORMAL
        restoreNormalMode = false
        Hub.log("Voice link OFF")
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (running) AudioRoute.setSpeaker(context, on)
    }

    fun onAudio(data: ByteArray) {
        if (!running) return
        rxFrames++
        if (queue.size > 25) queue.clear() // too much lag: drop and catch up
        queue.offer(data)
    }

    private fun sourceName(source: Int) = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice-communication"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice-recognition"
        MediaRecorder.AudioSource.MIC -> "mic"
        else -> "source $source"
    }

    /**
     * During a call Android can return a silent microphone for one source. If input stays
     * exactly zero for 2 seconds, release it and try the next source.
     */
    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val list = sources().distinct()
        var idx = 0
        while (running && idx < list.size) {
            val source = list[idx]
            val name = sourceName(source)
            val rec: AudioRecord
            try {
                rec = AudioRecord(
                    source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, FRAME_BYTES * 4)
                )
            } catch (e: Exception) {
                Hub.log("Microphone error ($name): ${e.message}")
                idx++
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Hub.log("Microphone source $name not available")
                rec.release()
                idx++
                continue
            }
            var tryNext = false
            try {
                rec.startRecording()
                Hub.log("Microphone: $name")
                var sent = 0
                var silent = 0
                var reports = 0
                var zeroRun = 0
                var lastReport = System.currentTimeMillis()
                while (running) {
                    val buf = ByteArray(FRAME_BYTES)
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var peak = 0
                        var i = 0
                        while (i + 1 < n) {
                            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                            peak = maxOf(peak, kotlin.math.abs(v))
                            i += 2
                        }
                        sent++
                        if (peak < 8) silent++
                        zeroRun = if (peak == 0) zeroRun + 1 else 0
                        if (idx < list.size - 1 && zeroRun >= 100) {
                            Hub.log("Microphone source $name is silent – trying another source")
                            tryNext = true
                            break
                        }
                        try {
                            sender(buf, n)
                        } catch (e: Exception) {
                            Hub.log("Audio send failed: ${e.message}")
                            return
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 3000 && reports < 10) {
                            val pct = if (sent == 0) 0 else silent * 100 / sent
                            Hub.log("Voice audio: sent $sent frames, received $rxFrames, silent mic $pct% ($name)")
                            if (pct >= 95) Hub.log("Microphone input is mostly silent; source fallback may be needed")
                            sent = 0
                            silent = 0
                            reports++
                            lastReport = now
                        }
                    } else if (n < 0) {
                        Hub.log("Microphone read error $n ($name)")
                        tryNext = idx < list.size - 1
                        break
                    }
                }
            } catch (e: Exception) {
                Hub.log("Microphone error ($name): ${e.message}")
                tryNext = idx < list.size - 1
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
            if (!tryNext || !running) return
            idx++
        }
        if (running) Hub.log("No usable microphone source remains")
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track: AudioTrack
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Hub.log("Speaker error: ${e.message}")
            return
        }
        try {
            track.play()
            while (running) {
                val data: ByteArray? = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (data != null) track.write(data, 0, data.size)
            }
        } catch (e: Exception) {
            Hub.log("Playback error: ${e.message}")
        } finally {
            try {
                track.stop()
            } catch (e: Exception) {
            }
            track.release()
        }
    }
}

object AudioRoute {
    fun setSpeaker(context: Context, on: Boolean, log: Boolean = true) {
        val am = context.getSystemService(AudioManager::class.java)!!
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val dev = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    val ear = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (ear != null) am.setCommunicationDevice(ear) else am.clearCommunicationDevice()
                }
            } else {
                setSpeakerLegacy(am, on)
            }
        } catch (e: Exception) {
            if (log) Hub.log("Speaker switch failed: ${e.message}")
        }
    }

    fun clear(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)!!
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else setSpeakerLegacy(am, false)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerLegacy(am: AudioManager, on: Boolean) {
        am.isSpeakerphoneOn = on
    }
}
