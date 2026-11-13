package com.abap.privatep2p.service.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.abap.privatep2p.core.network.DirectSecureChannel
import java.io.Closeable
import kotlin.concurrent.thread

/**
 * Foreground-session PCM voice engine. Audio is carried only inside the authenticated,
 * encrypted peer channel; this class never opens a network socket itself.
 */
class AudioCallSession(
    private val channel: DirectSecureChannel,
    private val onFailure: (Throwable) -> Unit = {}
) : Closeable {
    private val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(FRAME_BYTES * 4)
    private val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer
    )
    private val player = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(minBuffer)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
        AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
    } else null
    private val noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
        NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
    } else null

    fun start() {
        check(!running) { "Voice session is already running" }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialised" }
        check(player.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not be initialised" }
        running = true
        recorder.startRecording()
        player.play()
        captureThread = thread(name = "abap-private-audio-capture", isDaemon = true) {
            val buffer = ByteArray(FRAME_BYTES)
            try {
                while (running) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) channel.send(
                        DirectSecureChannel.TYPE_AUDIO,
                        if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)
                    )
                }
            } catch (error: Throwable) {
                if (running) onFailure(error)
                close()
            }
        }
    }

    fun playIncoming(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    override fun close() {
        if (!running && recorder.state == AudioRecord.STATE_UNINITIALIZED) return
        running = false
        runCatching { recorder.stop() }
        runCatching { player.pause() }
        runCatching { player.flush() }
        runCatching { player.stop() }
        runCatching { recorder.release() }
        runCatching { player.release() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 640 // 20 ms, mono, PCM16
    }
}
