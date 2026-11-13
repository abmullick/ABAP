package com.abap.privatep2p.core.network

import com.abap.privatep2p.core.identity.DeviceIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class DirectEndpoint(val host: String, val port: Int = 47831)

data class EndpointAdvertisement(
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val endpoints: List<DirectEndpoint>,
    /** SHA-256 fingerprint of the public key carried in this signed QR. */
    val peerFingerprint: String,
    /** Public signing key carried in the QR; must exactly match the already-pinned key. */
    val peerPublicKeyEncoded: ByteArray
)

/**
 * Signed endpoint hand-off for an already-paired peer.
 *
 * V2 carries the sender's public signing key and its SHA-256 fingerprint inside
 * the signed payload. Verification requires an exact match with the key pinned
 * during mutual pairing. No private key or identity secret is exported.
 */
object EndpointAdvertisementCodec {
    const val DEFAULT_PORT = 47831
    private const val PREFIX = "ABAP-ENDPOINT-V2"
    private const val MAGIC = "ABEP"
    private const val VERSION = 2
    private const val MAX_ENDPOINTS = 16
    private const val MAX_HOST_BYTES = 64
    private const val MAX_PUBLIC_KEY_BYTES = 256
    private const val FINGERPRINT_BYTES = 32
    private const val MAX_PAYLOAD_BYTES = 2048
    private const val DEFAULT_LIFETIME_SECONDS = 24 * 60 * 60L
    private const val CLOCK_SKEW_SECONDS = 5 * 60L

    fun create(
        identity: DeviceIdentity,
        endpoints: List<DirectEndpoint>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS
    ): String {
        require(lifetimeSeconds in 60..(7 * 24 * 60 * 60L)) {
            "Endpoint QR lifetime must be between one minute and seven days"
        }
        require(identity.publicKeyEncoded.isNotEmpty() &&
            identity.publicKeyEncoded.size <= MAX_PUBLIC_KEY_BYTES) { "Device public key is invalid" }

        val normalized = endpoints.map { normalize(it) }.distinctBy { it.host to it.port }
        require(normalized.isNotEmpty()) { "No usable direct endpoint is available to share" }
        require(normalized.size <= MAX_ENDPOINTS) { "Too many endpoint candidates" }

        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.publicKeyEncoded)
        val payload = encodePayload(
            nowEpochSeconds,
            nowEpochSeconds + lifetimeSeconds,
            identity.publicKeyEncoded,
            fingerprint,
            normalized
        )
        val signature = identity.sign(payload)
        return "$PREFIX.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
    }

    fun verify(
        encoded: String,
        expectedPeerPublicKey: ByteArray,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): EndpointAdvertisement {
        require(encoded.length <= 8192) { "Endpoint QR payload is too large" }
        val parts = encoded.split('.')
        require(parts.size == 3 && parts[0] == PREFIX) {
            "This is not a supported ABAP Private endpoint QR; ask your peer to create a fresh connection QR"
        }
        val payload = runCatching { Base64.getUrlDecoder().decode(parts[1]) }
            .getOrElse { throw IllegalArgumentException("Endpoint QR payload is malformed", it) }
        val signature = runCatching { Base64.getUrlDecoder().decode(parts[2]) }
            .getOrElse { throw IllegalArgumentException("Endpoint QR signature is malformed", it) }
        require(payload.size in 64..MAX_PAYLOAD_BYTES) { "Endpoint QR payload has an invalid size" }

        val input = DataInputStream(ByteArrayInputStream(payload))
        val magic = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Unsupported endpoint QR format" }
        require(input.readUnsignedByte() == VERSION) { "Unsupported endpoint QR version" }
        val issuedAt = input.readLong()
        val expiresAt = input.readLong()
        val keySize = input.readUnsignedShort()
        require(keySize in 1..MAX_PUBLIC_KEY_BYTES) { "Endpoint QR public key has an invalid size" }
        val publicKeyEncoded = ByteArray(keySize).also(input::readFully)
        val fingerprintBytes = ByteArray(FINGERPRINT_BYTES).also(input::readFully)

        require(MessageDigest.isEqual(publicKeyEncoded, expectedPeerPublicKey)) {
            "Endpoint QR belongs to a different device identity than your paired peer"
        }
        val calculatedFingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        require(MessageDigest.isEqual(fingerprintBytes, calculatedFingerprint)) {
            "Endpoint QR identity fingerprint does not match its public key"
        }
        require(verifySignature(publicKeyEncoded, payload, signature)) {
            "Endpoint QR signature is invalid for your paired peer"
        }

        require(issuedAt >= 0) { "Endpoint QR issue time is invalid" }
        require(issuedAt <= nowEpochSeconds + CLOCK_SKEW_SECONDS) { "Endpoint QR was issued in the future" }
        require(expiresAt > nowEpochSeconds) { "Endpoint QR has expired; ask your peer to share a fresh one" }
        require(expiresAt > issuedAt && expiresAt - issuedAt <= 7 * 24 * 60 * 60L) {
            "Endpoint QR validity period is invalid"
        }
        val count = input.readUnsignedByte()
        require(count in 1..MAX_ENDPOINTS) { "Endpoint QR contains no usable endpoints" }
        val endpoints = buildList {
            repeat(count) {
                val size = input.readUnsignedByte()
                require(size in 1..MAX_HOST_BYTES) { "Invalid endpoint address length" }
                val host = ByteArray(size).also(input::readFully).toString(Charsets.US_ASCII)
                val port = input.readUnsignedShort()
                add(normalize(DirectEndpoint(host, port)))
            }
        }
        require(input.available() == 0) { "Endpoint QR contains trailing data" }
        return EndpointAdvertisement(
            issuedAt,
            expiresAt,
            endpoints.distinctBy { it.host to it.port },
            fingerprintBytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) },
            publicKeyEncoded
        )
    }

    private fun encodePayload(
        issuedAt: Long,
        expiresAt: Long,
        publicKeyEncoded: ByteArray,
        fingerprint: ByteArray,
        endpoints: List<DirectEndpoint>
    ): ByteArray {
        require(fingerprint.size == FINGERPRINT_BYTES)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC.toByteArray(Charsets.US_ASCII))
            output.writeByte(VERSION)
            output.writeLong(issuedAt)
            output.writeLong(expiresAt)
            output.writeShort(publicKeyEncoded.size)
            output.write(publicKeyEncoded)
            output.write(fingerprint)
            output.writeByte(endpoints.size)
            endpoints.forEach { endpoint ->
                val host = endpoint.host.toByteArray(Charsets.US_ASCII)
                output.writeByte(host.size)
                output.write(host)
                output.writeShort(endpoint.port)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    private fun normalize(endpoint: DirectEndpoint): DirectEndpoint {
        require(endpoint.port in 1..65535) { "Endpoint port is invalid" }
        val host = endpoint.host.trim().removePrefix("[").removeSuffix("]")
        require(host.isNotBlank() && host.length <= MAX_HOST_BYTES) { "Endpoint address is invalid" }
        require('%' !in host && host.all { it.isDigit() || it in "abcdefABCDEF:." }) {
            "Only literal IPv4 or IPv6 addresses are allowed in endpoint QR codes"
        }
        val address = parseLiteralAddress(host)
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress) {
            "Unusable local, link-local or multicast endpoint address"
        }
        return DirectEndpoint(address.hostAddress.substringBefore('%'), endpoint.port)
    }

    private fun parseLiteralAddress(host: String): InetAddress {
        if (':' in host) {
            val parsed = InetAddress.getByName(host)
            require(parsed is Inet6Address) { "Invalid IPv6 endpoint" }
            return parsed
        }
        val octets = host.split('.')
        require(octets.size == 4) { "Invalid IPv4 endpoint" }
        val bytes = octets.map {
            require(it.isNotEmpty() && (it == "0" || !it.startsWith('0'))) { "Invalid IPv4 endpoint" }
            it.toIntOrNull()?.also { value -> require(value in 0..255) }?.toByte()
                ?: throw IllegalArgumentException("Invalid IPv4 endpoint")
        }.toByteArray()
        return InetAddress.getByAddress(bytes)
    }

    private fun verifySignature(publicKeyEncoded: ByteArray, payload: ByteArray, signatureBytes: ByteArray): Boolean =
        runCatching {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyEncoded))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
}
