package com.abap.privatep2p.feature.pairing

import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.core.identity.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class PairingProtocolTest {
    private val now = 1_800_000_000L

    @Test
    fun invitationAndResponseRoundTripVerifyBothDeviceIdentities() {
        val inviterIdentity = softwareIdentity()
        val responderIdentity = softwareIdentity()
        val inviter = PairingCoordinator({ inviterIdentity }, { now })
        val responder = PairingCoordinator({ responderIdentity }, { now })

        val invitation = inviter.createInvitation("Phone A")
        val importedInvitation = responder.decodeInvitation(inviter.encodeInvitation(invitation))
        val inviterFingerprint = AndroidDeviceIdentityStore.fingerprint(importedInvitation.publicKey)

        val response = responder.createResponse(importedInvitation, "Phone B", inviterFingerprint)
        val importedResponse = inviter.decodeResponse(responder.encodeResponse(response))
        val inspectedPeer = inviter.inspectResponse(invitation, importedResponse)
        val confirmedPeer = inviter.verifyResponse(
            invitation,
            importedResponse,
            AndroidDeviceIdentityStore.fingerprint(responderIdentity.publicKeyEncoded)
        )

        assertEquals("Phone A", importedInvitation.displayName)
        assertEquals("Phone B", inspectedPeer.displayName)
        assertEquals(inspectedPeer.fingerprint, confirmedPeer.fingerprint)
        assertTrue(confirmedPeer.publicKey.contentEquals(responderIdentity.publicKeyEncoded))
    }

    @Test
    fun rejectsInvitationWithTamperedSignature() {
        val identity = softwareIdentity()
        val coordinator = PairingCoordinator({ identity }, { now })
        val invitation = coordinator.createInvitation("Phone A")
        val tampered = invitation.copy(
            signature = invitation.signature.copyOf().also {
                it[0] = (it[0].toInt() xor 0x01).toByte()
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.decodeInvitation(coordinator.encodeInvitation(tampered))
        }
    }

    @Test
    fun rejectsExpiredInvitation() {
        val identity = softwareIdentity()
        val creator = PairingCoordinator({ identity }, { now })
        val invitation = creator.createInvitation("Phone A", lifetimeSeconds = 300)
        val later = PairingCoordinator({ identity }, { now + 301 })

        assertThrows(IllegalArgumentException::class.java) {
            later.decodeInvitation(creator.encodeInvitation(invitation))
        }
    }

    @Test
    fun rejectsWrongConfirmedFingerprint() {
        val inviterIdentity = softwareIdentity()
        val responderIdentity = softwareIdentity()
        val inviter = PairingCoordinator({ inviterIdentity }, { now })
        val responder = PairingCoordinator({ responderIdentity }, { now })
        val invitation = inviter.createInvitation("Phone A")

        assertThrows(IllegalArgumentException::class.java) {
            responder.createResponse(invitation, "Phone B", "00:11:22")
        }
    }

    @Test
    fun rejectsResponseBoundToDifferentInvitation() {
        val inviterIdentity = softwareIdentity()
        val responderIdentity = softwareIdentity()
        val inviter = PairingCoordinator({ inviterIdentity }, { now })
        val responder = PairingCoordinator({ responderIdentity }, { now })
        val firstInvitation = inviter.createInvitation("Phone A")
        val secondInvitation = inviter.createInvitation("Phone A")
        val fingerprint = AndroidDeviceIdentityStore.fingerprint(firstInvitation.publicKey)
        val response = responder.createResponse(firstInvitation, "Phone B", fingerprint)

        assertThrows(IllegalArgumentException::class.java) {
            inviter.inspectResponse(secondInvitation, response)
        }
    }

    @Test
    fun invalidSignatureNeverVerifies() {
        val inviterIdentity = softwareIdentity()
        val responderIdentity = softwareIdentity()
        val inviter = PairingCoordinator({ inviterIdentity }, { now })
        val responder = PairingCoordinator({ responderIdentity }, { now })
        val invitation = inviter.createInvitation("Phone A")
        val fingerprint = AndroidDeviceIdentityStore.fingerprint(invitation.publicKey)
        val response = responder.createResponse(invitation, "Phone B", fingerprint)
        val tampered = response.copy(
            signature = response.signature.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
            }
        )

        assertFalse(
            runCatching { inviter.inspectResponse(invitation, tampered) }.isSuccess
        )
    }

    private fun softwareIdentity(): DeviceIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public.encoded
        return DeviceIdentity(
            publicKey,
            AndroidDeviceIdentityStore.fingerprint(publicKey)
        ) { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(message)
                sign()
            }
        }
    }
}
