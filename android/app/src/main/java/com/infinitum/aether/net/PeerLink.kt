package com.infinitum.aether.net

import uniffi.aether_core.ChatMessage
import uniffi.aether_core.Handshake
import uniffi.aether_core.Identity
import uniffi.aether_core.Session
import uniffi.aether_core.Hello
import uniffi.aether_core.decodeHello
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket

/**
 * One encrypted link to a peer over a reliable byte stream (TCP today; mesh
 * relays later). Frames are `len:u16be | bytes`. The first three frames are
 * the Noise XX handshake, carrying display names; every frame after that is a
 * session-encrypted [ChatMessage].
 */
class PeerLink private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val session: Session,
    /** What the peer sent in the handshake: display name and, optionally, its device certificate. */
    val peerHello: Hello,
) : AutoCloseable {

    val peerName: String = peerHello.name.take(64).ifBlank { "Unnamed peer" }

    val remoteKey: ByteArray = session.remotePublicKey()

    /** True when the session keys also depend on an ML-KEM-768 secret (protocol v2). */
    val postQuantum: Boolean = session.postQuantum()

    fun send(message: ChatMessage) {
        val frame = session.encrypt(message)
        synchronized(output) { writeFrame(output, frame) }
    }

    /** Blocks for the next message; throws on EOF, socket error or failed authentication. */
    fun receive(): ChatMessage = session.decrypt(readFrame(input))

    val remoteAddress: String get() = "${socket.inetAddress.hostAddress}:${socket.port}"

    override fun close() {
        runCatching { socket.close() }
        session.destroy()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /**
         * Runs the handshake as initiator (we dialed) or responder (we accepted). A dialer uses the
         * hybrid post-quantum handshake (v2) unless [classic]; a responder answers either version.
         *
         * [hello] is our encoded hello (core `encodeHello`). [acceptRemote] (initiator only) sees the
         * responder's authenticated key and hello after message 2; if it refuses, the dialer hangs
         * up before message 3, so a wrong peer never learns the dialer's identity, name or certificate.
         */
        fun establish(
            socket: Socket,
            identity: Identity,
            hello: ByteArray,
            initiator: Boolean,
            acceptRemote: ((ByteArray, Hello) -> Boolean)? = null,
            classic: Boolean = false,
        ): PeerLink {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val hs = when {
                !initiator -> Handshake.responder(identity)
                classic -> Handshake.initiatorClassic(identity)
                else -> Handshake.initiator(identity)
            }
            try {
                val peerHello: Hello
                if (initiator) {
                    writeFrame(output, hs.writeMessage(ByteArray(0)))     // -> e (+ ML-KEM key in v2)
                    peerHello = decodeHello(hs.readMessage(readFrame(input))) // <- e, ee, s, es (+hello)
                    if (acceptRemote != null) {
                        val rk = hs.remotePublicKey()
                        if (rk == null || !acceptRemote(rk, peerHello)) throw RemoteRejectedException()
                    }
                    writeFrame(output, hs.writeMessage(hello))            // -> s, se (, psk) (+hello)
                } else {
                    hs.readMessage(readFrame(input))                      // <- e
                    writeFrame(output, hs.writeMessage(hello))            // -> e, ee, s, es (+hello)
                    peerHello = decodeHello(hs.readMessage(readFrame(input))) // <- s, se (, psk) (+hello)
                }
                val session = hs.finish()
                socket.soTimeout = 0
                return PeerLink(socket, input, output, session, peerHello)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            } finally {
                hs.destroy()
            }
        }

        private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
            require(bytes.size <= 0xFFFF) { "frame too large" }
            out.writeShort(bytes.size)
            out.write(bytes)
            out.flush()
        }

        private fun readFrame(input: DataInputStream): ByteArray {
            val len = try { input.readUnsignedShort() } catch (e: EOFException) { throw EOFException("peer closed") }
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}

/** The dialed peer authenticated as someone other than expected; the dialer revealed nothing. */
class RemoteRejectedException : Exception("peer is not the expected key")
