package com.abap.privatep2p.core.network

import com.abap.privatep2p.core.identity.DeviceIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class DirectEndpoint(val host: String, val port: Int = DEFAULT_PORT)

data class EndpointAdvertisement(
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val endpoints: List<DirectEndpoint>
)

/**
 * Signed endpoint hand-off for an already-paired peer.
 *
 * This QR contains only reachable-address candidates, not identity secrets. Its
 * signature is checked against the public key already pinned during mutual pairing.
 * It does not discover public IPv4 addresses, punch NATs, or provide rendezvous.
 */
object EndpointAdvertisementCodec {
    const val DEFAULT_PORT = 47831
    private const val PREFIX = "ABAP-ENDPOINT-V1"
    private const val MAGIC = "ABEP"
    private const val VERSION = 1
    private const val MAX_ENDPOINTS = 16
    private const val MAX_HOST_BYTES = 64
    private const val MAX_PAYLOAD_BYTES = 2048
    private const val DEFAULT_LIFETIME_SECONDS = 24 * 60 * 60L
    private const val CLOCK_SKEW_SECONDS = 5 * 60L

    fun create(
        identity: DeviceIdentity,
        endpoints: List<DirectEndpoint>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS
    ): String {
        require(lifetimeSeconds in 60..(7 * 24 * 60 * 60L)) { "Endpoint QR lifetime must be between one minute and seven days" }
        val normalized = endpoints.map { normalize(it) }.distinctBy { it.host to it.port }
        require(normalized.isNotEmpty()) { "No usable direct endpoint is available to share" }
        require(normalized.size <= MAX_ENDPOINTS) { "Too many endpoint candidates" }

        val payload = encodePayload(nowEpochSeconds, nowEpochSeconds + lifetimeSeconds, normalized)
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
        require(parts.size == 3 && parts[0] == PREFIX) { "This is not an ABAP Private endpoint QR" }
        val payload = runCatching { Base64.getUrlDecoder().decode(parts[1]) }
            .getOrElse { throw IllegalArgumentException("Endpoint QR payload is malformed", it) }
        val signature = runCatching { Base64.getUrlDecoder().decode(parts[2]) }
            .getOrElse { throw IllegalArgumentException("Endpoint QR signature is malformed", it) }
        require(payload.size in 16..MAX_PAYLOAD_BYTES) { "Endpoint QR payload has an invalid size" }
        require(verifySignature(expectedPeerPublicKey, payload, signature)) {
            "Endpoint QR signature does not match your paired peer"
        }

        val input = DataInputStream(ByteArrayInputStream(payload))
        val magic = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Unsupported endpoint QR format" }
        require(input.readUnsignedByte() == VERSION) { "Unsupported endpoint QR version" }
        val issuedAt = input.readLong()
        val expiresAt = input.readLong()
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
        return EndpointAdvertisement(issuedAt, expiresAt, endpoints.distinctBy { it.host to it.port })
    }

    private fun encodePayload(
        issuedAt: Long,
        expiresAt: Long,
        endpoints: List<DirectEndpoint>
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC.toByteArray(Charsets.US_ASCII))
            output.writeByte(VERSION)
            output.writeLong(issuedAt)
            output.writeLong(expiresAt)
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
