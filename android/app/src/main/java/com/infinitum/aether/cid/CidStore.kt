package com.infinitum.aether.cid

import org.json.JSONObject

/**
 * Keeps the verified Cumulus ID sign-in (claims only, never a token) and the in-flight PKCE
 * verifier, state and nonce.
 */
interface CidStore {
    fun loadSession(): CidSession?
    fun saveSession(session: CidSession?)
    fun loadPending(): CidPending?
    fun savePending(pending: CidPending?)
}

class MemoryCidStore : CidStore {
    private var session: CidSession? = null
    private var pending: CidPending? = null
    override fun loadSession() = session
    override fun saveSession(session: CidSession?) { this.session = session }
    override fun loadPending() = pending
    override fun savePending(pending: CidPending?) { this.pending = pending }
}

/**
 * [CidStore] over an append-only encrypted record log: in the app, the ÆTHER vault
 * `cid.vault` (`Store.openVault("cid")`, sealed by the hardware-wrapped data key, no backup).
 * Each save appends one JSON record; on load the last record of each kind wins, and a "clear"
 * record removes the value. The vault holds only claims and the short-lived PKCE values.
 */
class LogCidStore(
    private val append: (ByteArray) -> Unit,
    private val readAll: () -> List<ByteArray>,
) : CidStore {
    private var session: CidSession? = null
    private var pending: CidPending? = null

    init {
        for (raw in readAll()) {
            val o = try {
                JSONObject(raw.decodeToString())
            } catch (_: Exception) {
                continue
            }
            when (o.optString("t")) {
                T_SESSION -> session = sessionOf(o)
                T_SIGNOUT -> session = null
                T_PENDING -> pending = pendingOf(o)
                T_PENDING_CLEAR -> pending = null
            }
        }
    }

    override fun loadSession() = session

    override fun saveSession(session: CidSession?) {
        val record = if (session == null) {
            JSONObject().put("t", T_SIGNOUT)
        } else {
            JSONObject().put("t", T_SESSION).put("iss", CidConfig.OIDC_ISSUER).put("host", session.issuer)
                .put("at", session.verifiedAtMillis).put("cid", session.profile.cid)
                .put("username", session.profile.username).put("email", session.profile.email)
                .put("handle", session.profile.handle).put("name", session.profile.name)
        }
        append(record.toString().toByteArray())
        this.session = session
    }

    override fun loadPending() = pending

    override fun savePending(pending: CidPending?) {
        if (pending == null && this.pending == null) return
        val record = if (pending == null) {
            JSONObject().put("t", T_PENDING_CLEAR)
        } else {
            JSONObject().put("t", T_PENDING).put("v", pending.verifier).put("s", pending.state)
                .put("i", pending.issuer).put("n", pending.nonce)
        }
        append(record.toString().toByteArray())
        this.pending = pending
    }

    private fun sessionOf(o: JSONObject): CidSession? {
        // Only sessions verified against the canonical issuer, with a subject, are kept.
        if (o.optString("iss") != CidConfig.OIDC_ISSUER || o.optString("cid").isEmpty()) return null
        return CidSession(
            profile = CidProfile(
                cid = o.optString("cid"),
                username = o.optString("username"),
                email = o.optString("email"),
                handle = o.optString("handle"),
                name = o.optString("name"),
            ),
            verifiedAtMillis = o.optLong("at", 0L),
            issuer = o.optString("host", CidConfig.DEFAULT_ISSUER),
        )
    }

    private fun pendingOf(o: JSONObject): CidPending? {
        val v = o.optString("v")
        val s = o.optString("s")
        val i = o.optString("i")
        val n = o.optString("n")
        if (v.isEmpty() || s.isEmpty() || i.isEmpty() || n.isEmpty()) return null
        return CidPending(v, s, i, n)
    }

    private companion object {
        const val T_SESSION = "session"
        const val T_SIGNOUT = "signout"
        const val T_PENDING = "pending"
        const val T_PENDING_CLEAR = "pending-clear"
    }
}
