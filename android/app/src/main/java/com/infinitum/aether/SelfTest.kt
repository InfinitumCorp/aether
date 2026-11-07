package com.infinitum.aether

import uniffi.aether_core.AetherException
import uniffi.aether_core.ChatMessage
import uniffi.aether_core.Handshake
import uniffi.aether_core.Identity
import uniffi.aether_core.MessageKind
import uniffi.aether_core.Vault
import java.io.File

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/**
 * On-device verification that the Rust core behaves correctly on this ABI:
 * a full Noise XX handshake between two fresh identities, message round trip,
 * tamper rejection, and vault persistence / wrong-key rejection.
 */
object SelfTest {
    fun run(scratch: File): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        fun check(name: String, block: () -> String) {
            out += try { CheckResult(name, true, block()) } catch (e: Throwable) { CheckResult(name, false, e.toString()) }
        }

        val alice = Identity.generate()
        val bob = Identity.generate()
        val i = Handshake.initiator(alice)
        val r = Handshake.responder(bob)
        var aliceSession: uniffi.aether_core.Session? = null
        var bobSession: uniffi.aether_core.Session? = null

        check("Noise XX handshake") {
            r.readMessage(i.writeMessage(ByteArray(0)))
            val n1 = i.readMessage(r.writeMessage("bob".toByteArray())).decodeToString()
            val n2 = r.readMessage(i.writeMessage("alice".toByteArray())).decodeToString()
            require(n1 == "bob" && n2 == "alice") { "payload mismatch" }
            aliceSession = i.finish(); bobSession = r.finish()
            "3 messages, names exchanged"
        }
        check("Mutual authentication") {
            require(aliceSession!!.remotePublicKey().contentEquals(bob.publicKey()))
            require(bobSession!!.remotePublicKey().contentEquals(alice.publicKey()))
            val sn = aliceSession!!.safetyNumber(alice)
            require(sn == bobSession!!.safetyNumber(bob)) { "safety numbers differ" }
            "safety number ${sn.take(17)}…"
        }
        check("Encrypted round trip") {
            val msg = ChatMessage(MessageKind.TEXT, 1UL, "ÆTHER self-test")
            val frame = aliceSession!!.encrypt(msg)
            require(bobSession!!.decrypt(frame) == msg)
            "${frame.size}-byte frame"
        }
        check("Tamper rejected") {
            val frame = aliceSession!!.encrypt(ChatMessage(MessageKind.TEXT, 2UL, "x"))
            frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
            try { bobSession!!.decrypt(frame); error("tampered frame accepted") } catch (e: AetherException.Decrypt) { "authentication failure raised" }
        }
        check("Vault persistence") {
            val f = File(scratch, "selftest.vault").apply { delete() }
            val key = ByteArray(32) { 7 }
            Vault.open(f.path, key).use { it.append("persist".toByteArray()) }
            val back = Vault.open(f.path, key).use { it.readAll() }
            require(back.size == 1 && back[0].decodeToString() == "persist")
            val wrong = try { Vault.open(f.path, ByteArray(32) { 8 }); false } catch (e: AetherException.InvalidKey) { true }
            require(wrong) { "wrong key accepted" }
            f.delete()
            "reopened 1 record; wrong key refused"
        }
        listOf(alice, bob, i, r).forEach { it.destroy() }
        aliceSession?.destroy(); bobSession?.destroy()
        return out
    }
}
