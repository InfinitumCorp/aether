package com.infinitum.aether.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aether_core.Vault

/** Disappearing-message timers a chat may use, in seconds (0 = off); mirrors the core's SOCIAL_TIMERS. */
val SOCIAL_TIMERS: List<Int> = listOf(0, 30, 300, 3600, 28_800, 86_400, 604_800)

fun timerLabel(s: Int): String = when (s) {
    0 -> "Off"
    30 -> "30 seconds"
    300 -> "5 minutes"
    3600 -> "1 hour"
    28_800 -> "8 hours"
    86_400 -> "1 day"
    604_800 -> "1 week"
    else -> "${s}s"
}

/** Local, per-conversation chat settings. None of these leave the device except the timer. */
data class ChatSettings(
    val convo: String,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    /** Disappearing-message timer in seconds (0 = off). */
    val timerS: Int = 0,
    /** Newest incoming message time (peer clock) the operator has seen; older ones are read. */
    val lastReadMs: Long = 0,
)

/**
 * A Talk group. [members] are identity keys (hex, lower case), including this device's own key
 * for [via]. [admin] is the creator: only the admin's updates (rename, members) are accepted.
 */
data class ChatGroup(
    val id: String,
    val name: String,
    val members: List<String>,
    val admin: String,
    /** Local identity the group runs under (burner id), or null for the real identity. */
    val via: String? = null,
    /** True once this device left or was removed; the chat stays readable but sends nothing. */
    val left: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val convo: String get() = groupConvo(id)
}

/** An encoded social event waiting for a member's link to come up. */
data class PendingSend(val qid: String, val convo: String, val body: String, val sentAtMs: Long)

/**
 * Chat settings, groups and the group outbox, in their own vault (`chats.vault`) under the
 * device's hardware-wrapped key. Records are JSON events; the latest record of a kind and key
 * wins. The vault is rewritten when it grows well past its live state, and when a burner
 * number is burned so nothing about its chats remains.
 */
class ChatStore(private val store: Store) {
    private var vault: Vault = store.openVault("chats")

    private val _settings = MutableStateFlow<Map<String, ChatSettings>>(emptyMap())
    val settings: StateFlow<Map<String, ChatSettings>> = _settings.asStateFlow()
    private val _groups = MutableStateFlow<Map<String, ChatGroup>>(emptyMap())
    val groups: StateFlow<Map<String, ChatGroup>> = _groups.asStateFlow()
    private val pending = LinkedHashMap<String, PendingSend>()
    private var records = 0

    init { load() }

    @Synchronized
    private fun load() {
        val s = LinkedHashMap<String, ChatSettings>()
        val g = LinkedHashMap<String, ChatGroup>()
        val raw = vault.readAll()
        records = raw.size
        for (r in raw) {
            val o = runCatching { JSONObject(r.decodeToString()) }.getOrNull() ?: continue
            when (o.optString("t")) {
                "set" -> settingsFrom(o).let { s[it.convo] = it }
                "group" -> groupFrom(o).let { g[it.id] = it }
                "q" -> PendingSend(o.getString("id"), o.getString("c"), o.getString("b"), o.getLong("ts")).let { pending[it.qid] = it }
                "qx" -> pending.remove(o.optString("id"))
            }
        }
        _settings.value = s
        _groups.value = g
        if (records > 64 && records > 3 * (s.size + g.size + pending.size)) compact()
    }

    fun get(convo: String): ChatSettings = _settings.value[convo] ?: ChatSettings(convo)

    @Synchronized
    fun update(convo: String, f: (ChatSettings) -> ChatSettings) {
        val old = get(convo)
        val new = f(old).copy(convo = convo)
        if (new == old) return
        append(settingsJson(new))
        _settings.update { it + (convo to new) }
    }

    @Synchronized
    fun saveGroup(g: ChatGroup) {
        append(groupJson(g))
        _groups.update { it + (g.id to g) }
    }

    @Synchronized
    fun enqueue(convo: String, body: String, sentAtMs: Long) {
        // Bounded per conversation: the oldest waiting events give way.
        val mine = pending.values.filter { it.convo == convo }
        if (mine.size >= MAX_PENDING_PER_CONVO) mine.take(mine.size - MAX_PENDING_PER_CONVO + 1).forEach { done(it.qid) }
        val p = PendingSend(randomId(), convo, body, sentAtMs)
        append(JSONObject().put("t", "q").put("id", p.qid).put("c", convo).put("b", body).put("ts", sentAtMs))
        pending[p.qid] = p
    }

    @Synchronized
    fun pendingFor(convo: String): List<PendingSend> = pending.values.filter { it.convo == convo }

    @Synchronized
    fun done(qid: String) {
        if (pending.remove(qid) != null) append(JSONObject().put("t", "qx").put("id", qid))
    }

    /** Forgets every chat, group and queued event of a burned number, and rewrites the vault. */
    @Synchronized
    fun forgetVia(burnerId: String) {
        val prefix = "b:$burnerId:"
        val gone = _groups.value.values.filter { it.via == burnerId }.map { it.id }.toSet()
        _settings.update { m -> m.filterKeys { !it.startsWith(prefix) && it.removePrefix("g:") !in gone } }
        _groups.update { m -> m.filterKeys { it !in gone } }
        pending.values.removeAll { it.convo.startsWith(prefix) }
        compact()
    }

    @Synchronized
    private fun compact() {
        val out = ArrayList<ByteArray>()
        _settings.value.values.forEach { out += settingsJson(it).toString().toByteArray() }
        _groups.value.values.forEach { out += groupJson(it).toString().toByteArray() }
        pending.values.forEach {
            out += JSONObject().put("t", "q").put("id", it.qid).put("c", it.convo).put("b", it.body).put("ts", it.sentAtMs).toString().toByteArray()
        }
        vault = store.rewriteVault("chats", out)
        records = out.size
    }

    private fun append(o: JSONObject) {
        vault.append(o.toString().toByteArray())
        records++
    }

    private fun settingsJson(c: ChatSettings) = JSONObject().put("t", "set").put("c", c.convo).put("p", c.pinned)
        .put("m", c.muted).put("a", c.archived).put("tm", c.timerS).put("lr", c.lastReadMs)

    private fun settingsFrom(o: JSONObject) = ChatSettings(
        o.getString("c"), o.optBoolean("p"), o.optBoolean("m"), o.optBoolean("a"),
        o.optInt("tm").takeIf { it in SOCIAL_TIMERS } ?: 0, o.optLong("lr"),
    )

    private fun groupJson(g: ChatGroup) = JSONObject().put("t", "group").put("id", g.id).put("n", g.name)
        .put("mb", JSONArray(g.members)).put("ad", g.admin).put("via", g.via ?: "").put("l", g.left).put("at", g.createdAt)

    private fun groupFrom(o: JSONObject): ChatGroup {
        val a = o.getJSONArray("mb")
        return ChatGroup(
            o.getString("id"), o.getString("n"), List(a.length()) { a.getString(it) }, o.getString("ad"),
            o.optString("via").ifEmpty { null }, o.optBoolean("l"), o.optLong("at"),
        )
    }

    companion object {
        private const val MAX_PENDING_PER_CONVO = 500
    }
}
