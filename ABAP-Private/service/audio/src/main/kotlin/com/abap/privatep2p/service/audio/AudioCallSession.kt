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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Full-duplex speech session using 20 ms Opus frames inside the authenticated,
 * encrypted peer channel. A bounded playout queue drops stale audio rather than
 * allowing latency to grow without limit.
 */
class AudioCallSession(
    private val channel: DirectSecureChannel,
    private val onFailure: (Throwable) -> Unit = {}
) : Closeable {
    private val minBuffer = AudioRecord.getMinBufferSize(
        OpusAudioCodec.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).also {
        check(it > 0) { "This device does not support the requested microphone format" }
    }.coerceAtLeast(OpusAudioCodec.FRAME_BYTES * 4)

    private val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        OpusAudioCodec.SAMPLE_RATE,
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
                .setSampleRate(OpusAudioCodec.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(minBuffer)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val playoutQueue = ArrayBlockingQueue<ByteArray>(PLAYOUT_QUEUE_FRAMES)
    private var captureThread: Thread? = null
    private var playoutThread: Thread? = null

    private val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
        AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
    } else null
    private val noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
        NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
    } else null

    fun start() {
        check(running.compareAndSet(false, true)) { "Voice session is already running" }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialised" }
        check(player.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not be initialised" }
        try {
            val decoder = OpusAudioCodec()
            val encoder = OpusAudioCodec()
            recorder.startRecording()
            player.play()
            playoutThread = thread(name = "abap-private-opus-playout", isDaemon = true) {
                try {
                    while (running.get()) {
                        val packet = playoutQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        val pcm = decoder.decode(packet)
                        player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    }
                } catch (error: Throwable) {
                    if (running.get()) onFailure(error)
                    close()
                }
            }
            captureThread = thread(name = "abap-private-opus-capture", isDaemon = true) {
                val pcm = ByteArray(OpusAudioCodec.FRAME_BYTES)
                var filled = 0
                try {
                    while (running.get()) {
                        val count = recorder.read(
                            pcm,
                            filled,
                            pcm.size - filled,
                            AudioRecord.READ_BLOCKING
                        )
                        if (count < 0) error("Microphone read failed: $count")
                        filled += count
                        if (filled == pcm.size) {
                            val packet = encoder.encode(pcm)
                            channel.send(DirectSecureChannel.TYPE_AUDIO, packet)
                            filled = 0
                        }
                    }
                } catch (error: Throwable) {
                    if (running.get()) onFailure(error)
                    close()
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Called by the single network receive loop. Never blocks that loop on audio playback. */
    fun playIncoming(opusPacket: ByteArray) {
        if (!running.get() || opusPacket.isEmpty() || opusPacket.size > MAX_PACKET_BYTES) return
        if (!playoutQueue.offer(opusPacket.copyOf())) {
            playoutQueue.poll()
            playoutQueue.offer(opusPacket.copyOf())
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        playoutQueue.clear()
        runCatching { recorder.stop() }
        runCatching { player.pause() }
        runCatching { player.flush() }
        runCatching { player.stop() }
        runCatching { recorder.release() }
        runCatching { player.release() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        captureThread?.interrupt()
        playoutThread?.interrupt()
    }

    companion object {
        private const val PLAYOUT_QUEUE_FRAMES = 12 // 240 ms hard upper bound
        private const val MAX_PACKET_BYTES = 1_275
    }
}
