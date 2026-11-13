package com.abap.privatep2p.feature.pairing

import android.graphics.Bitmap
import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.core.identity.DeviceIdentity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class PairingInvitation(
    val invitationId: String,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val displayName: String,
    val publicKey: ByteArray,
    val signature: ByteArray
)

data class PairingResponse(
    val invitationId: String,
    val invitationDigest: ByteArray,
    val createdAtEpochSeconds: Long,
    val displayName: String,
    val publicKey: ByteArray,
    val signature: ByteArray
)

data class PinnedPeer(
    val displayName: String,
    val publicKey: ByteArray,
    val fingerprint: String,
    val pairedAtEpochSeconds: Long
)

/**
 * QR bootstrap only. It does not discover network addresses or create a transport path.
 * Callers must obtain explicit user confirmation of the displayed peer fingerprint.
 */
class PairingCoordinator(
    private val identityStore: () -> DeviceIdentity,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1_000L }
) {
    fun createInvitation(displayName: String, lifetimeSeconds: Long = DEFAULT_LIFETIME): PairingInvitation {
        requireValidName(displayName)
        require(lifetimeSeconds in MIN_LIFETIME..MAX_LIFETIME)
        val identity = identityStore()
        val now = clockSeconds()
        val unsigned = PairingInvitation(
            UUID.randomUUID().toString(), now, now + lifetimeSeconds,
            displayName.trim(), identity.publicKeyEncoded, ByteArray(0)
        )
        return unsigned.copy(signature = identity.sign(invitationSigningBytes(unsigned)))
    }

    fun createResponse(
        invitation: PairingInvitation,
        displayName: String,
        confirmedInviterFingerprint: String
    ): PairingResponse {
        requireValidInvitation(invitation, clockSeconds())
        val expectedFingerprint = AndroidDeviceIdentityStore.fingerprint(invitation.publicKey)
        require(confirmedInviterFingerprint == expectedFingerprint) {
            "The confirmed inviter fingerprint does not match this invitation"
        }
        requireValidName(displayName)
        val identity = identityStore()
        require(!identity.publicKeyEncoded.contentEquals(invitation.publicKey)) {
            "A device cannot pair with itself"
        }
        val unsigned = PairingResponse(
            invitation.invitationId,
            invitationDigest(invitation),
            clockSeconds(),
            displayName.trim(),
            identity.publicKeyEncoded,
            ByteArray(0)
        )
        return unsigned.copy(signature = identity.sign(responseSigningBytes(unsigned)))
    }

    fun verifyResponse(
        invitation: PairingInvitation,
        response: PairingResponse,
        confirmedPeerFingerprint: String
    ): PinnedPeer {
        requireValidInvitation(invitation, clockSeconds())
        require(response.invitationId == invitation.invitationId) { "Response belongs to another invitation" }
        require(response.invitationDigest.size == SHA256_BYTES)
        require(MessageDigest.isEqual(response.invitationDigest, invitationDigest(invitation))) {
            "Response is not bound to this invitation"
        }
        require(response.createdAtEpochSeconds in invitation.createdAtEpochSeconds..invitation.expiresAtEpochSeconds) {
            "Response timestamp is outside the invitation validity window"
        }
        require(response.publicKey.isNotEmpty() && response.publicKey.size <= MAX_KEY_BYTES)
        require(response.signature.isNotEmpty() && response.signature.size <= MAX_SIGNATURE_BYTES)
        requireValidName(response.displayName)
        require(!response.publicKey.contentEquals(invitation.publicKey)) { "Self-pairing is not allowed" }
        require(
            AndroidDeviceIdentityStore.verify(
                response.publicKey, responseSigningBytes(response), response.signature
            )
        ) { "Invalid responder signature" }

        val actualFingerprint = AndroidDeviceIdentityStore.fingerprint(response.publicKey)
        require(confirmedPeerFingerprint == actualFingerprint) {
            "The confirmed peer fingerprint does not match this response"
        }
        return PinnedPeer(
            response.displayName, response.publicKey.copyOf(), actualFingerprint, clockSeconds()
        )
    }

    fun encodeInvitation(invitation: PairingInvitation): String = JSONObject()
        .put("type", INVITATION_TYPE)
        .put("version", VERSION)
        .put("id", invitation.invitationId)
        .put("created", invitation.createdAtEpochSeconds)
        .put("expires", invitation.expiresAtEpochSeconds)
        .put("name", invitation.displayName)
        .put("key", b64(invitation.publicKey))
        .put("sig", b64(invitation.signature))
        .toString()

    fun decodeInvitation(encoded: String): PairingInvitation {
        require(encoded.length <= MAX_QR_CHARS) { "Invitation QR is too large" }
        val json = JSONObject(encoded)
        require(json.getString("type") == INVITATION_TYPE && json.getInt("version") == VERSION) {
            "Unsupported invitation format"
        }
        val invitation = PairingInvitation(
            json.getString("id"), json.getLong("created"), json.getLong("expires"),
            json.getString("name"), unb64(json.getString("key")), unb64(json.getString("sig"))
        )
        requireValidInvitation(invitation, clockSeconds())
        return invitation
    }

    fun encodeResponse(response: PairingResponse): String = JSONObject()
        .put("type", RESPONSE_TYPE)
        .put("version", VERSION)
        .put("id", response.invitationId)
        .put("digest", b64(response.invitationDigest))
        .put("created", response.createdAtEpochSeconds)
        .put("name", response.displayName)
        .put("key", b64(response.publicKey))
        .put("sig", b64(response.signature))
        .toString()

    fun decodeResponse(encoded: String): PairingResponse {
        require(encoded.length <= MAX_QR_CHARS) { "Response QR is too large" }
        val json = JSONObject(encoded)
        require(json.getString("type") == RESPONSE_TYPE && json.getInt("version") == VERSION) {
            "Unsupported response format"
        }
        return PairingResponse(
            json.getString("id"), unb64(json.getString("digest")), json.getLong("created"),
            json.getString("name"), unb64(json.getString("key")), unb64(json.getString("sig"))
        )
    }

    private fun requireValidInvitation(invitation: PairingInvitation, now: Long) {
        require(runCatching { UUID.fromString(invitation.invitationId) }.isSuccess) { "Invalid invitation ID" }
        require(invitation.publicKey.isNotEmpty() && invitation.publicKey.size <= MAX_KEY_BYTES)
        require(invitation.signature.isNotEmpty() && invitation.signature.size <= MAX_SIGNATURE_BYTES)
        requireValidName(invitation.displayName)
        require(invitation.expiresAtEpochSeconds > invitation.createdAtEpochSeconds)
        require(invitation.expiresAtEpochSeconds - invitation.createdAtEpochSeconds in MIN_LIFETIME..MAX_LIFETIME)
        require(now <= invitation.expiresAtEpochSeconds) { "Invitation has expired" }
        require(now >= invitation.createdAtEpochSeconds - CLOCK_SKEW_SECONDS) { "Invitation timestamp is in the future" }
        require(
            AndroidDeviceIdentityStore.verify(
                invitation.publicKey, invitationSigningBytes(invitation), invitation.signature
            )
        ) { "Invalid inviter signature" }
    }

    private fun invitationSigningBytes(value: PairingInvitation): ByteArray = canonical {
        writeUTF(INVITATION_TYPE)
        writeInt(VERSION)
        writeUTF(value.invitationId)
        writeLong(value.createdAtEpochSeconds)
        writeLong(value.expiresAtEpochSeconds)
        writeUTF(value.displayName)
        writeBytes(value.publicKey)
    }

    private fun responseSigningBytes(value: PairingResponse): ByteArray = canonical {
        writeUTF(RESPONSE_TYPE)
        writeInt(VERSION)
        writeUTF(value.invitationId)
        writeBytes(value.invitationDigest)
        writeLong(value.createdAtEpochSeconds)
        writeUTF(value.displayName)
        writeBytes(value.publicKey)
    }

    private fun invitationDigest(value: PairingInvitation): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(invitationSigningBytes(value) + value.signature)

    private fun canonical(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.block()
        output.flush()
        return bytes.toByteArray()
    }

    private fun requireValidName(name: String) {
        require(name.trim().isNotEmpty() && name.length <= MAX_NAME_CHARS) {
            "Device name must contain 1–$MAX_NAME_CHARS characters"
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray {
        require(value.length <= MAX_QR_CHARS) { "Encoded field is too large" }
        return Base64.getUrlDecoder().decode(value)
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_LIFETIME = 300L
        private const val MIN_LIFETIME = 60L
        private const val MAX_LIFETIME = 600L
        private const val CLOCK_SKEW_SECONDS = 60L
        private const val MAX_NAME_CHARS = 48
        private const val MAX_KEY_BYTES = 512
        private const val MAX_SIGNATURE_BYTES = 256
        private const val MAX_QR_CHARS = 8_192
        private const val SHA256_BYTES = 32
        private const val INVITATION_TYPE = "abap-private-invitation"
        private const val RESPONSE_TYPE = "abap-private-response"
    }
}

object PairingQrCode {
    fun render(payload: String, size: Int = 768): Bitmap {
        require(size in 256..1_536)
        val matrix = QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF101820.toInt() else 0xFFFFFFFF.toInt())
            }
        }
    }
}