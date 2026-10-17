package com.callcompanion.app

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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Streams microphone audio to the other phone and plays what comes back. */
class AudioBridge(
    private val context: Context,
    private val sender: (ByteArray, Int) -> Unit,
) {
    companion object {
        const val RATE = 8000
        const val FRAME_BYTES = 320 // 20 ms of 8 kHz 16-bit mono
    }

    @Volatile
    private var running = false

    @Volatile
    var state: String = "STOPPED"
        private set

    @Volatile
    private var readyWorkers = 0
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(10) // 200 ms at 20 ms per frame

    @Volatile
    var speakerOn = true
        private set

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        state = "STARTING"
        readyWorkers = 0
        queue.clear()
        val am = context.getSystemService(AudioManager::class.java)!!
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        AudioRoute.setSpeaker(context, speakerOn)
        recordThread = Thread({ recordLoop() }, "callcompanion-record").also { it.start() }
        playThread = Thread({ playLoop() }, "callcompanion-play").also { it.start() }
        Hub.log("Voice link ON")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        state = "STOPPED"
        readyWorkers = 0
        recordThread?.interrupt()
        playThread?.interrupt()
        recordThread = null
        playThread = null
        queue.clear()
        AudioRoute.clear(context)
        context.getSystemService(AudioManager::class.java)!!.mode = AudioManager.MODE_NORMAL
        Hub.log("Voice link OFF")
    }

    @Synchronized
    private fun workerReady() {
        if (!running || state == "FAILED") return
        readyWorkers++
        if (readyWorkers >= 2) {
            state = "RUNNING"
            Hub.log("Voice link audio capture and playback ready")
        }
    }

    @Synchronized
    private fun fail(message: String) {
        if (state != "STOPPED") state = "FAILED"
        Hub.log(message)
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (running) AudioRoute.setSpeaker(context, on)
    }

    fun onAudio(data: ByteArray) {
        if (!running) return
        if (data.size != FRAME_BYTES) return
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data) // discard oldest audio to keep latency bounded
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec: AudioRecord
        try {
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, FRAME_BYTES * 4)
            )
        } catch (e: Exception) {
            fail("Microphone error: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            fail("Microphone not available")
            rec.release()
            return
        }
        try {
            rec.startRecording()
            workerReady()
            while (running) {
                val buf = ByteArray(FRAME_BYTES)
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    try {
                        sender(buf, n)
                    } catch (e: Exception) {
                        fail("Audio transport failed: ${e.message ?: "Bluetooth write failed"}")
                        break
                    }
                } else if (n < 0) {
                    fail("Microphone read error $n")
                    break
                }
            }
        } catch (e: Exception) {
            fail("Microphone error: ${e.message}")
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
        }
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
            fail("Speaker error: ${e.message}")
            return
        }
        try {
            track.play()
            workerReady()
            while (running) {
                val data: ByteArray? = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (data != null) {
                    val written = track.write(data, 0, data.size)
                    if (written < 0) {
                        fail("Audio playback write failed: $written")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            fail("Playback error: ${e.message}")
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
    fun setSpeaker(context: Context, on: Boolean) {
        val am = context.getSystemService(AudioManager::class.java)!!
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val dev = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                setSpeakerLegacy(am, on)
            }
        } catch (e: Exception) {
            Hub.log("Speaker switch failed: ${e.message}")
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
