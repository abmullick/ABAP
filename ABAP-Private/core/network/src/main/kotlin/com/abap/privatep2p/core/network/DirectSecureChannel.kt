package com.abap.privatep2p.core.network

import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.core.identity.DeviceIdentity
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac

/**
 * Authenticated direct TCP transport. Both sides must already have pinned the other's
 * long-term signing key through the QR pairing flow. No discovery, relay or rendezvous.
 *
 * This is an initial protocol implementation and requires independent security review
 * before production use.
 */
class DirectSecureChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray
) : Closeable {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun send(type: Int, payload: ByteArray) {
        require(type in 1..255)
        require(payload.size <= MAX_FRAME_BYTES) { "Frame is too large" }
        check(!socket.isClosed) { "Connection is closed" }
        val counter = sendCounter++
        val header = frameHeader(type, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(payload)
        synchronized(output) {
            output.writeByte(type)
            output.writeLong(counter)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    fun receive(): SecureFrame {
        val type = input.readUnsignedByte()
        val counter = input.readLong()
        val size = input.readInt()
        require(counter == receiveCounter) { "Out-of-order or replayed frame" }
        require(size in 16..(MAX_FRAME_BYTES + 16)) { "Invalid encrypted frame size" }
        val encrypted = ByteArray(size)
        input.readFully(encrypted)
        val header = frameHeader(type, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(header)
        val plain = cipher.doFinal(encrypted)
        receiveCounter++
        return SecureFrame(type, counter, plain)
    }

    override fun close() {
        runCatching { socket.close() }
        sendKey.fill(0)
        receiveKey.fill(0)
    }

    data class SecureFrame(val type: Int, val counter: Long, val payload: ByteArray)

    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_AUDIO = 2
        const val TYPE_CALL_REQUEST = 3
        const val TYPE_CALL_ACCEPT = 4
        const val TYPE_CALL_REJECT = 5
        const val TYPE_CALL_END = 6
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val NONCE_BYTES = 32
        private const val LABEL_CLIENT = "ABAP-PRIVATE-CLIENT-HANDSHAKE-V1"
        private const val LABEL_SERVER = "ABAP-PRIVATE-SERVER-HANDSHAKE-V1"
        private val random = SecureRandom()

        fun connect(
            host: String,
            port: Int,
            localIdentity: DeviceIdentity,
            expectedPeerPublicKey: ByteArray,
            connectTimeoutMs: Int = 12_000
        ): DirectSecureChannel {
            require(host.isNotBlank())
            require(port in 1..65535)
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return try {
                clientHandshake(socket, localIdentity, expectedPeerPublicKey)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }

        fun accept(
            socket: Socket,
            localIdentity: DeviceIdentity,
            expectedPeerPublicKey: ByteArray
        ): DirectSecureChannel {
            socket.tcpNoDelay = true
            return try {
                serverHandshake(socket, localIdentity, expectedPeerPublicKey)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }

        private fun clientHandshake(
            socket: Socket,
            identity: DeviceIdentity,
            peerPublicKey: ByteArray
        ): DirectSecureChannel {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val ephemeral = ephemeralKeyPair()
            val clientNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val clientEphemeral = ephemeral.public.encoded
            val clientSigned = signedBytes(LABEL_CLIENT, clientEphemeral, clientNonce, identity.publicKeyEncoded)
            val clientSignature = identity.sign(clientSigned)
            writeBlob(output, clientEphemeral)
            writeBlob(output, clientNonce)
            writeBlob(output, clientSignature)
            output.flush()

            val serverEphemeral = readBlob(input, 256)
            val serverNonce = readBlob(input, NONCE_BYTES).also { require(it.size == NONCE_BYTES) }
            val serverSignature = readBlob(input, 256)
            val transcript = transcript(clientEphemeral, clientNonce, serverEphemeral, serverNonce,
                identity.publicKeyEncoded, peerPublicKey)
            val serverSigned = signedBytes(LABEL_SERVER, transcript)
            check(AndroidDeviceIdentityStore.verify(peerPublicKey, serverSigned, serverSignature)) {
                "Peer authentication failed: server signature is invalid"
            }
            val shared = agree(ephemeral, serverEphemeral)
            val keys = deriveKeys(shared, clientNonce, serverNonce, transcript)
            shared.fill(0)
            return DirectSecureChannel(socket, input, output, keys.first, keys.second)
        }

        private fun serverHandshake(
            socket: Socket,
            identity: DeviceIdentity,
            peerPublicKey: ByteArray
        ): DirectSecureChannel {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val clientEphemeral = readBlob(input, 256)
            val clientNonce = readBlob(input, NONCE_BYTES).also { require(it.size == NONCE_BYTES) }
            val clientSignature = readBlob(input, 256)
            val clientSigned = signedBytes(LABEL_CLIENT, clientEphemeral, clientNonce, peerPublicKey)
            check(AndroidDeviceIdentityStore.verify(peerPublicKey, clientSigned, clientSignature)) {
                "Peer authentication failed: client signature is invalid"
            }

            val ephemeral = ephemeralKeyPair()
            val serverNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val serverEphemeral = ephemeral.public.encoded
            val transcript = transcript(clientEphemeral, clientNonce, serverEphemeral, serverNonce,
                peerPublicKey, identity.publicKeyEncoded)
            val serverSigned = signedBytes(LABEL_SERVER, transcript)
            val signature = identity.sign(serverSigned)
            writeBlob(output, serverEphemeral)
            writeBlob(output, serverNonce)
            writeBlob(output, signature)
            output.flush()

            val shared = agree(ephemeral, clientEphemeral)
            val keys = deriveKeys(shared, clientNonce, serverNonce, transcript)
            shared.fill(0)
            return DirectSecureChannel(socket, input, output, keys.second, keys.first)
        }

        private fun ephemeralKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

        private fun agree(local: KeyPair, remoteEncoded: ByteArray): ByteArray {
            val remote = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remoteEncoded))
            return KeyAgreement.getInstance("ECDH").run {
                init(local.private)
                doPhase(remote, true)
                generateSecret()
            }
        }

        private fun deriveKeys(shared: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray,
                               transcript: ByteArray): Pair<ByteArray, ByteArray> {
            val salt = MessageDigest.getInstance("SHA-256").digest(clientNonce + serverNonce)
            val prk = hmac(salt, shared)
            val material = hkdfExpand(prk, "ABAP-PRIVATE-TRAFFIC-V1".toByteArray() + transcript, 64)
            prk.fill(0)
            return material.copyOfRange(0, 32) to material.copyOfRange(32, 64)
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
            var previous = ByteArray(0)
            val result = ByteArray(length)
            var offset = 0
            var block = 1
            while (offset < length) {
                previous = hmac(prk, previous + info + byteArrayOf(block.toByte()))
                val count = minOf(previous.size, length - offset)
                previous.copyInto(result, offset, 0, count)
                offset += count
                block++
            }
            previous.fill(0)
            return result
        }

        private fun transcript(clientEphemeral: ByteArray, clientNonce: ByteArray,
                               serverEphemeral: ByteArray, serverNonce: ByteArray,
                               clientIdentity: ByteArray, serverIdentity: ByteArray): ByteArray =
            signedBytes("ABAP-PRIVATE-TRANSCRIPT-V1", clientEphemeral, clientNonce,
                serverEphemeral, serverNonce, clientIdentity, serverIdentity)

        private fun signedBytes(label: String, vararg parts: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val data = DataOutputStream(out)
            writeBlob(data, label.toByteArray())
            parts.forEach { writeBlob(data, it) }
            return out.toByteArray()
        }

        private fun writeBlob(output: DataOutputStream, bytes: ByteArray) {
            require(bytes.size <= 4096)
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun readBlob(input: DataInputStream, max: Int): ByteArray {
            val size = input.readInt()
            require(size in 1..max) { "Invalid handshake field length" }
            return ByteArray(size).also(input::readFully)
        }

        private fun nonce(counter: Long): ByteArray = ByteArray(12).also {
            for (i in 0..7) it[11 - i] = (counter ushr (i * 8)).toByte()
        }

        private fun frameHeader(type: Int, counter: Long): ByteArray =
            java.nio.ByteBuffer.allocate(9).put(type.toByte()).putLong(counter).array()
    }
}

class DirectPeerListener(
    private val port: Int,
    private val localIdentity: DeviceIdentity,
    private val expectedPeerPublicKey: ByteArray,
    private val onConnected: (DirectSecureChannel) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private val server = ServerSocket()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        check(!running)
        require(port in 1..65535)
        server.reuseAddress = true
        server.bind(InetSocketAddress("0.0.0.0", port))
        running = true
        thread = Thread({
            while (running) {
                try {
                    val socket = server.accept()
                    Thread({
                        try {
                            onConnected(DirectSecureChannel.accept(socket, localIdentity, expectedPeerPublicKey))
                        } catch (error: Throwable) {
                            onError(error)
                        }
                    }, "abap-private-handshake").start()
                } catch (error: Throwable) {
                    if (running) onError(error)
                }
            }
        }, "abap-private-listener").apply { isDaemon = true; start() }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        thread?.interrupt()
    }
}
