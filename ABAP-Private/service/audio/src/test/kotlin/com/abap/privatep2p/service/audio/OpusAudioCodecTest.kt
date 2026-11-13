package com.abap.privatep2p.service.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OpusAudioCodecTest {
    @Test
    fun encodesAndDecodesOneTwentyMillisecondSpeechFrame() {
        val codec = OpusAudioCodec()
        val pcm = ByteArray(OpusAudioCodec.FRAME_BYTES)
        for (sample in 0 until OpusAudioCodec.SAMPLE_RATE * OpusAudioCodec.FRAME_MILLIS / 1_000) {
            val value = (sin(2.0 * PI * 440.0 * sample / OpusAudioCodec.SAMPLE_RATE) * 4_000).toInt().toShort()
            pcm[sample * 2] = value.toByte()
            pcm[sample * 2 + 1] = (value.toInt() shr 8).toByte()
        }

        val packet = codec.encode(pcm)
        val decoded = codec.decode(packet)

        assertTrue(packet.isNotEmpty())
        assertTrue(packet.size < pcm.size)
        assertTrue(decoded.size > 0)
        assertTrue(decoded.size <= pcm.size)
    }
}
