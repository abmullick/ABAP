package com.abap.privatep2p.feature.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores exactly one pinned peer and a bounded set of consumed invitation IDs.
 * The complete record is AES-256-GCM encrypted with a key held in Android Keystore.
 */
class EncryptedPairingStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )

    @Synchronized
    fun loadPeer(): PinnedPeer? {
        val peer = readState().optJSONObject("peer") ?: return null
        val publicKey = Base64.decode(peer.getString("publicKey"), Base64.NO_WRAP)
        val fingerprint = AndroidDeviceIdentityStoreFingerprint.of(publicKey)
        require(fingerprint == peer.getString("fingerprint")) {
            "Stored peer fingerprint does not match its public key"
        }
        return PinnedPeer(
            peer.getString("displayName"),
            publicKey,
            fingerprint,
            peer.getLong("pairedAt")
        )
    }

    @Synchronized
    fun loadPendingResponse(): PendingPairingResponse? {
        val pending = readState().optJSONObject("pendingResponse") ?: return null
        return PendingPairingResponse(
            pending.getString("invitationId"),
            pending.getString("payload")
        )
    }

    @Synchronized
    fun isInvitationConsumed(invitationId: String): Boolean =
        readConsumedIds().contains(invitationId)

    /**
     * Atomically pins the one allowed peer and consumes the invitation.
     * A different peer must be explicitly revoked before a replacement can be pinned.
     */
    @Synchronized
    fun completePairing(
        peer: PinnedPeer,
        invitationId: String,
        pendingResponsePayload: String? = null
    ) {
        require(invitationId.isNotBlank())
        require(peer.publicKey.isNotEmpty() && peer.publicKey.size <= MAX_PUBLIC_KEY_BYTES)
        require(peer.displayName.isNotBlank() && peer.displayName.length <= MAX_DISPLAY_NAME_CHARS)
        require(peer.fingerprint == AndroidDeviceIdentityStoreFingerprint.of(peer.publicKey)) {
            "Peer fingerprint does not match its public key"
        }
        val state = readState()
        val consumed = state.optJSONArray("consumedInvitations") ?: JSONArray()
        for (index in 0 until consumed.length()) {
            require(consumed.optString(index) != invitationId) { "This invitation has already been used" }
        }

        val existing = state.optJSONObject("peer")
        if (existing != null) {
            val existingKey = Base64.decode(existing.getString("publicKey"), Base64.NO_WRAP)
            require(existingKey.contentEquals(peer.publicKey)) {
                "A different peer is already pinned. Revoke that peer before pairing another device."
            }
        }

        state.put("peer", JSONObject()
            .put("displayName", peer.displayName)
            .put("publicKey", Base64.encodeToString(peer.publicKey, Base64.NO_WRAP))
            .put("fingerprint", peer.fingerprint)
            .put("pairedAt", peer.pairedAtEpochSeconds)
        )
        if (pendingResponsePayload != null) {
            require(pendingResponsePayload.length <= MAX_PENDING_RESPONSE_CHARS)
            state.put("pendingResponse", JSONObject()
                .put("invitationId", invitationId)
                .put("payload", pendingResponsePayload)
            )
        } else {
            state.remove("pendingResponse")
        }
        consumed.put(invitationId)
        while (consumed.length() > MAX_CONSUMED_INVITATIONS) consumed.remove(0)
        state.put("consumedInvitations", consumed)
        writeState(state)
    }

    @Synchronized
    fun revokePeer() {
        val state = readState()
        state.remove("peer")
        state.remove("pendingResponse")
        writeState(state)
    }

    @Synchronized
    fun clearPairingHistory() {
        check(preferences.edit().remove(STATE_KEY).commit()) { "Could not clear pairing state" }
    }

    private fun readConsumedIds(): Set<String> {
        val array = readState().optJSONArray("consumedInvitations") ?: return emptySet()
        return buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
    }

    private fun readState(): JSONObject {
        val stored = preferences.getString(STATE_KEY, null) ?: return JSONObject()
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            require(packed.size > GCM_NONCE_BYTES) { "Encrypted pairing state is truncated" }
            val nonce = packed.copyOfRange(0, GCM_NONCE_BYTES)
            val ciphertext = packed.copyOfRange(GCM_NONCE_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(ASSOCIATED_DATA)
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Unable to authenticate/decrypt local pairing state", error)
        }
    }

    private fun writeState(state: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        check(preferences.edit().putString(STATE_KEY, encoded).commit()) {
            "Could not persist encrypted pairing state"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "abap-private-pairing-state-aes-v1"
        private const val PREFERENCES_NAME = "abap_private_pairing_v1"
        private const val STATE_KEY = "encrypted_state"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_CONSUMED_INVITATIONS = 64
        private const val MAX_PUBLIC_KEY_BYTES = 512
        private const val MAX_DISPLAY_NAME_CHARS = 48
        private const val MAX_PENDING_RESPONSE_CHARS = 8_192
        private val ASSOCIATED_DATA = "ABAP_PRIVATE_PAIRING_STATE_V1".toByteArray(Charsets.UTF_8)
    }
}

private object AndroidDeviceIdentityStoreFingerprint {
    fun of(publicKey: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}
