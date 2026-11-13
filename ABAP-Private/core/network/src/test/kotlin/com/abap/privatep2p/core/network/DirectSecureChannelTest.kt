package com.abap.privatep2p.core.network

import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.core.identity.DeviceIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class DirectSecureChannelTest {
    @Test
    fun authenticatesPinnedPeersAndExchangesEncryptedFramesBothWays() {
        val a = softwareIdentity()
        val b = softwareIdentity()
        val server = ServerSocket(0)
        val accepted = ArrayBlockingQueue<DirectSecureChannel>(1)
        val failures = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                accepted.put(DirectSecureChannel.accept(server.accept(), b, a.publicKeyEncoded))
            } catch (error: Throwable) {
                failures.offer(error)
            }
        }.apply { start() }

        val client = DirectSecureChannel.connect(
            "127.0.0.1", server.localPort, a, b.publicKeyEncoded
        )
        val remote = accepted.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("Server handshake failed", failures.poll())

        val message = "hello over encrypted P2P".toByteArray()
        client.send(DirectSecureChannel.TYPE_TEXT, message)
        val received = remote.receive()
        assertEquals(DirectSecureChannel.TYPE_TEXT, received.type)
        assertArrayEquals(message, received.payload)

        val reply = "reply".toByteArray()
        remote.send(DirectSecureChannel.TYPE_TEXT, reply)
        assertArrayEquals(reply, client.receive().payload)

        client.close()
        remote.close()
        server.close()
        worker.join(1_000)
    }

    @Test
    fun rejectsConnectionWhenPeerIdentityIsNotPinned() {
        val a = softwareIdentity()
        val b = softwareIdentity()
        val impostor = softwareIdentity()
        val server = ServerSocket(0)
        val failures = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                DirectSecureChannel.accept(server.accept(), b, impostor.publicKeyEncoded)
            } catch (error: Throwable) {
                failures.offer(error)
            }
        }.apply { start() }

        assertThrows(Exception::class.java) {
            DirectSecureChannel.connect("127.0.0.1", server.localPort, a, b.publicKeyEncoded)
        }
        failures.poll(5, TimeUnit.SECONDS)
        server.close()
        worker.join(1_000)
    }

    private fun softwareIdentity(): DeviceIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        val publicKey = pair.public.encoded
        return DeviceIdentity(
            publicKey,
            AndroidDeviceIdentityStore.fingerprint(publicKey)
        ) { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(message)
                sign()
            }
        }
    }
}
