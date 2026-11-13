package com.abap.privatep2p.core.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.MessageDigest

/** Device identity backed by a non-exportable Android Keystore P-256 signing key. */
data class DeviceIdentity(
    val publicKeyEncoded: ByteArray,
    val fingerprint: String,
    private val signer: (ByteArray) -> ByteArray
) {
    fun sign(message: ByteArray): ByteArray = signer(message)
}

class AndroidDeviceIdentityStore(private val alias: String = DEFAULT_ALIAS) {
    fun getOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicKey = keyStore.getCertificate(alias)?.publicKey
            ?: error("Device identity certificate is missing")
        val privateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
            ?: error("Device identity private key is missing")
        val encoded = publicKey.encoded.copyOf()
        return DeviceIdentity(encoded, fingerprint(encoded)) { message ->
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(message)
                sign()
            }
        }
    }

    fun deleteIdentity() {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "abap-private-device-identity-v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun fingerprint(publicKeyEncoded: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
                .joinToString(":") { "%02X".format(it) }

        fun verify(publicKeyEncoded: ByteArray, message: ByteArray, signatureBytes: ByteArray): Boolean =
            runCatching {
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(publicKeyEncoded))
                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                    initVerify(publicKey)
                    update(message)
                    verify(signatureBytes)
                }
            }.getOrDefault(false)
    }
}