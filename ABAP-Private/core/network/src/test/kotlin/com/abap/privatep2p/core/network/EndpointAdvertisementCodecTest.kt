package com.abap.privatep2p.core.network

import com.abap.privatep2p.core.identity.DeviceIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EndpointAdvertisementCodecTest {
    private fun identity(): Pair<DeviceIdentity, KeyPair> {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        val identity = DeviceIdentity(pair.public.encoded, fingerprint) { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(message)
                sign()
            }
        }
        return identity to pair
    }

    private fun signedQr(payload: ByteArray, pair: KeyPair): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "ABAP-ENDPOINT-V2.${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
    }

    @Test fun signedEndpointCarriesAndVerifiesExplicitPinnedIdentity() {
        val (identity, pair) = identity()
        val qr = EndpointAdvertisementCodec.create(
            identity,
            listOf(DirectEndpoint("2001:4860:4860::8888"), DirectEndpoint("192.168.1.20")),
            nowEpochSeconds = 1_000,
            lifetimeSeconds = 3_600
        )
        val result = EndpointAdvertisementCodec.verify(qr, pair.public.encoded, nowEpochSeconds = 1_100)
        assertEquals(2, result.endpoints.size)
        assertTrue(result.endpoints.any { it.host == "2001:4860:4860:0:0:0:0:8888" || it.host == "2001:4860:4860::8888" })
        assertTrue(result.endpoints.any { it.host == "192.168.1.20" })
        assertEquals(4_600L, result.expiresAtEpochSeconds)
        assertArrayEquals(pair.public.encoded, result.peerPublicKeyEncoded)
        assertEquals(identity.fingerprint, result.peerFingerprint)
    }

    @Test fun rejectsEndpointQrFromDifferentPinnedDevice() {
        val (identity, _) = identity()
        val (_, otherPair) = identity()
        val qr = EndpointAdvertisementCodec.create(identity, listOf(DirectEndpoint("192.168.1.20")), 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.verify(qr, otherPair.public.encoded, 1_100)
        }
    }

    @Test fun rejectsFingerprintThatDoesNotMatchIncludedPublicKeyEvenWhenSigned() {
        val (identity, pair) = identity()
        val qr = EndpointAdvertisementCodec.create(identity, listOf(DirectEndpoint("192.168.1.20")), 1_000)
        val parts = qr.split('.')
        val payload = Base64.getUrlDecoder().decode(parts[1])
        val keyLength = ((payload[21].toInt() and 0xFF) shl 8) or (payload[22].toInt() and 0xFF)
        val fingerprintOffset = 23 + keyLength
        payload[fingerprintOffset] = (payload[fingerprintOffset].toInt() xor 0x01).toByte()
        val forgedFingerprintQr = signedQr(payload, pair)
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.verify(forgedFingerprintQr, identity.publicKeyEncoded, 1_001)
        }
    }

    @Test fun rejectsExpiredAdvertisement() {
        val (identity, pair) = identity()
        val qr = EndpointAdvertisementCodec.create(identity, listOf(DirectEndpoint("192.168.1.20")), 1_000, 60)
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.verify(qr, pair.public.encoded, 1_061)
        }
    }

    @Test fun rejectsTamperedPayload() {
        val (identity, pair) = identity()
        val qr = EndpointAdvertisementCodec.create(identity, listOf(DirectEndpoint("192.168.1.20")), 1_000)
        val parts = qr.split('.')
        val changedPayload = (if (parts[1].first() == 'A') "B" else "A") + parts[1].drop(1)
        val changed = "${parts[0]}.$changedPayload.${parts[2]}"
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.verify(changed, pair.public.encoded, 1_001)
        }
    }

    @Test fun rejectsHostnamesAndUnusableAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.create(identity().first, listOf(DirectEndpoint("peer.example.com")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.create(identity().first, listOf(DirectEndpoint("127.0.0.1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointAdvertisementCodec.create(identity().first, listOf(DirectEndpoint("169.254.1.2")))
        }
    }
}
