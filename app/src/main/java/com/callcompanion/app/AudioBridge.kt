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
import java.util.concurrent.LinkedBlockingQueue
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
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var speakerOn = true
        private set

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
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
        recordThread?.interrupt()
        playThread?.interrupt()
        recordThread = null
        playThread = null
        queue.clear()
        AudioRoute.clear(context)
        context.getSystemService(AudioManager::class.java)!!.mode = AudioManager.MODE_NORMAL
        Hub.log("Voice link OFF")
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (running) AudioRoute.setSpeaker(context, on)
    }

    fun onAudio(data: ByteArray) {
        if (!running) return
        if (queue.size > 25) queue.clear() // too much lag: drop and catch up
        queue.offer(data)
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
            Hub.log("Microphone error: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Hub.log("Microphone not available")
            rec.release()
            return
        }
        try {
            rec.startRecording()
            while (running) {
                val buf = ByteArray(FRAME_BYTES)
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    try {
                        sender(buf, n)
                    } catch (e: Exception) {
                        break
                    }
                } else if (n < 0) {
                    Hub.log("Microphone read error $n")
                    break
                }
            }
        } catch (e: Exception) {
            Hub.log("Microphone error: ${e.message}")
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
