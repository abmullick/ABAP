package com.abap.privatep2p.service.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful mono Opus codec for 20 ms speech frames.
 * The encoder and decoder instances must each be confined to one thread.
 */
class OpusAudioCodec(
    private val sampleRate: Int = SAMPLE_RATE
) {
    private val frameSamples = sampleRate * FRAME_MILLIS / 1000
    private val encoder = OpusEncoder(sampleRate, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
    private val decoder = OpusDecoder(sampleRate, CHANNELS)

    init {
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
    }

    fun encode(pcm16le: ByteArray, validBytes: Int = pcm16le.size): ByteArray {
        require(validBytes == frameSamples * BYTES_PER_SAMPLE) {
            "Opus encoder requires exactly one 20 ms PCM frame"
        }
        val pcm = ShortArray(frameSamples)
        ByteBuffer.wrap(pcm16le, 0, validBytes).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(pcm)
        val packet = ByteArray(MAX_PACKET_BYTES)
        val length = encoder.encode(pcm, 0, frameSamples, packet, 0, packet.size)
        require(length in 1..packet.size) { "Opus encoder returned an invalid packet length" }
        return packet.copyOf(length)
    }

    fun decode(packet: ByteArray): ByteArray {
        require(packet.isNotEmpty() && packet.size <= MAX_PACKET_BYTES) { "Invalid Opus packet size" }
        val pcm = ShortArray(frameSamples)
        val samples = decoder.decode(packet, 0, packet.size, pcm, 0, frameSamples, false)
        require(samples in 1..frameSamples) { "Opus decoder returned an invalid sample count" }
        return ByteBuffer.allocate(samples * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(pcm, 0, samples)
            .let { buffer -> buffer.position(0); ByteArray(samples * BYTES_PER_SAMPLE).also(buffer::get) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MILLIS = 20
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_BYTES = SAMPLE_RATE * FRAME_MILLIS / 1000 * BYTES_PER_SAMPLE
        private const val MAX_PACKET_BYTES = 1_275
        private val SUPPORTED_SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
    }
}
