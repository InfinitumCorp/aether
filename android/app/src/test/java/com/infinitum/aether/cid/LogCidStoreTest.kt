package com.infinitum.aether.cid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The append-only record format kept in cid.vault. */
class LogCidStoreTest {
    private val log = mutableListOf<ByteArray>()
    private fun open() = LogCidStore(append = { log += it }, readAll = { log.toList() })

    private val session = CidSession(
        CidProfile(cid = "cid_7c1e", username = "ann", email = "ann@cumuluscloud.app", handle = "@ann.veil", name = "Ann"),
        verifiedAtMillis = 1_800_000_000_000L,
        issuer = CidConfig.RU_ISSUER,
    )

    @Test
    fun sessionAndPendingSurviveAReopenAndTheLastRecordWins() {
        val s = open()
        val pending = CidPending("verifier", "state", CidConfig.DEFAULT_ISSUER, "nonce")
        s.savePending(pending)
        s.saveSession(session)
        val again = open()
        assertEquals(session, again.loadSession())
        assertEquals(pending, again.loadPending())
        again.savePending(null)
        again.saveSession(null)
        val third = open()
        assertNull(third.loadSession())
        assertNull(third.loadPending())
        third.saveSession(session.copy(profile = session.profile.copy(username = "ann2")))
        assertEquals("ann2", open().loadSession()!!.profile.username)
    }

    @Test
    fun recordsHoldClaimsOnlyAndNoToken() {
        open().saveSession(session)
        val o = JSONObject(log.single().decodeToString())
        assertEquals(CidConfig.OIDC_ISSUER, o.getString("iss"))
        assertEquals(
            setOf("t", "iss", "host", "at", "cid", "username", "email", "handle", "name"),
            o.keys().asSequence().toSet(),
        )
    }

    @Test
    fun clearingNothingWritesNothing() {
        open().savePending(null)
        assertEquals(0, log.size)
    }

    @Test
    fun ignoresForeignIssuersBadRecordsAndIncompletePending() {
        log += "not json".toByteArray()
        log += JSONObject().put("t", "session").put("iss", "https://evil.example").put("cid", "x").toString().toByteArray()
        log += JSONObject().put("t", "session").put("iss", CidConfig.OIDC_ISSUER).put("cid", "").toString().toByteArray()
        log += JSONObject().put("t", "pending").put("v", "v").put("s", "").put("i", "i").put("n", "n").toString().toByteArray()
        val s = open()
        assertNull(s.loadSession())
        assertNull(s.loadPending())
        assertFalse(log.isEmpty())
    }
}
