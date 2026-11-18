package com.infinitum.aether.data

import android.content.Context
import com.infinitum.aether.security.KeyWrapper
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aether_core.Identity
import uniffi.aether_core.Precedence
import uniffi.aether_core.Vault
import java.io.File

data class Profile(val identity: Identity, val displayName: String)

/**
 * A known operator. [pinned] means the key arrived in a Key Drop under [codename]:
 * a connection is trusted as this contact only if the peer proves that exact key.
 * [via] is the burner number id this contact talks to, or null for the real identity.
 */
data class Contact(
    val keyHex: String,
    val name: String,
    val verified: Boolean,
    val pinned: Boolean = false,
    val codename: String? = null,
    val addresses: List<String> = emptyList(),
    val via: String? = null,
    /** True once this contact has completed the post-quantum handshake; classical fallback is then refused. */
    val pq: Boolean = false,
)

data class StoredMessage(
    val peerKeyHex: String,
    val outgoing: Boolean,
    val sentAtMs: Long,
    val body: String,
    /** True when [body] is an encoded Key Drop rather than text. */
    val drop: Boolean = false,
    /** Burner number id this conversation runs under, or null for the real identity. */
    val via: String? = null,
    /**
     * [KIND_POSITION], [KIND_FIELD] or [KIND_EIDETIC] when [body] is an encoded position report,
     * field message or eidetic review message; [KIND_SYSTEM] for a chat system line. Null is text.
     */
    val kind: String? = null,
    // ---- ÆTHER Talk™ messenger state (all optional; legacy records load with the defaults) ----
    /** Social message id; null for legacy plain-text messages. A later record with the same id replaces this one. */
    val id: String? = null,
    val replyTo: String? = null,
    val forwarded: Boolean = false,
    val edited: Boolean = false,
    /** Deleted for everyone: [body] is emptied and the bubble shows "Message deleted". */
    val deleted: Boolean = false,
    /** Emoji → identity keys (hex) of everyone who reacted with it. */
    val reactions: Map<String, Set<String>> = emptyMap(),
    /** Local wall-clock time after which the message is purged; 0 = never. */
    val expiresAt: Long = 0,
    /** Outgoing only: [DELIVERY_SENT], [DELIVERY_DELIVERED] or [DELIVERY_READ]. */
    val delivery: Int = DELIVERY_SENT,
    /** Group this message belongs to; then [peerKeyHex] is the sender (own key when outgoing). */
    val groupId: String? = null,
    /** File id of an attached photo, file or voice message (social Message `target`), for the media code. */
    val fileId: String? = null,
    /** Message precedence: [Precedence.ROUTINE] (default), PRIORITY, IMMEDIATE or FLASH. */
    val precedence: Precedence = Precedence.ROUTINE,
) {
    fun expired(now: Long = System.currentTimeMillis()) = expiresAt in 1..now

    companion object {
        const val KIND_POSITION = "pos"
        const val KIND_FIELD = "field"
        const val KIND_EIDETIC = "eid"
        const val KIND_SYSTEM = "sys"
        const val DELIVERY_SENT = 0
        const val DELIVERY_DELIVERED = 1
        const val DELIVERY_READ = 2
    }
}

/**
 * An add-on bundle of burner-number slots (1, 2 or 5), renewed every [period]
 * ("month" or "year"). Its slots count only until [expiresAt].
 */
data class Bundle(val id: String, val size: Int, val period: String, val grantedAt: Long, val expiresAt: Long) {
    fun active(now: Long = System.currentTimeMillis()) = now < expiresAt

    companion object {
        val PERIODS = listOf("month", "year")

        /** End of one [period] starting at [from]. */
        fun periodEnd(from: Long, period: String): Long = java.util.Calendar.getInstance().run {
            timeInMillis = from
            add(if (period == "year") java.util.Calendar.YEAR else java.util.Calendar.MONTH, 1)
            timeInMillis
        }
    }
}

/** Conversation key: the peer's key for the real identity, scoped by burner otherwise. */
fun convoKey(via: String?, peerKeyHex: String) = if (via == null) peerKeyHex else "b:$via:$peerKeyHex"

val Contact.convo get() = convoKey(via, keyHex)
val StoredMessage.convo get() = if (groupId != null) groupConvo(groupId) else convoKey(via, peerKeyHex)

/** Conversation key of a group chat. */
fun groupConvo(groupId: String) = "g:$groupId"
fun isGroupConvo(convo: String) = convo.startsWith("g:")

/** JSON event encoding shared by the main log and each burner's vault. */
object EventLog {
    fun contactJson(c: Contact): JSONObject =
        JSONObject().put("t", "contact").put("k", c.keyHex).put("n", c.name).put("v", c.verified)
            .put("p", c.pinned).put("c", c.codename ?: "").put("a", JSONArray(c.addresses)).put("q", c.pq)

    fun messageJson(m: StoredMessage): JSONObject {
        val o = JSONObject().put("t", "msg").put("k", m.peerKeyHex).put("o", m.outgoing).put("ts", m.sentAtMs)
            .put("b", m.body).put("d", m.drop).put("y", m.kind ?: "")
        // Messenger fields only when set, so plain records stay as small as before.
        m.id?.let { o.put("id", it) }
        m.replyTo?.let { o.put("re", it) }
        if (m.forwarded) o.put("fw", true)
        if (m.edited) o.put("ed", true)
        if (m.deleted) o.put("del", true)
        if (m.reactions.isNotEmpty()) o.put("rx", JSONObject().apply { m.reactions.forEach { (e, ks) -> put(e, JSONArray(ks.toList())) } })
        if (m.expiresAt > 0) o.put("exp", m.expiresAt)
        if (m.delivery != StoredMessage.DELIVERY_SENT) o.put("dl", m.delivery)
        m.groupId?.let { o.put("g", it) }
        m.fileId?.let { o.put("f", it) }
        if (m.precedence != Precedence.ROUTINE) o.put("pr", m.precedence.name)
        return o
    }

    /** "Delete for me": a later record that removes message [id] of [convo] on replay. */
    fun goneJson(convo: String, id: String): JSONObject = JSONObject().put("t", "gone").put("c", convo).put("id", id)

    fun isMessageRecord(o: JSONObject) = o.optString("t").let { it == "msg" || it == "gone" }

    private fun message(o: JSONObject, via: String?): StoredMessage {
        val rx = o.optJSONObject("rx")
        val reactions = if (rx == null) emptyMap() else rx.keys().asSequence().associateWith { e ->
            val a = rx.getJSONArray(e); (0 until a.length()).map { a.getString(it) }.toSet()
        }.filterValues { it.isNotEmpty() }
        return StoredMessage(
            o.getString("k"), o.getBoolean("o"), o.getLong("ts"), o.getString("b"), o.optBoolean("d"), via,
            o.optString("y").ifEmpty { null },
            id = o.optString("id").ifEmpty { null },
            replyTo = o.optString("re").ifEmpty { null },
            forwarded = o.optBoolean("fw"),
            edited = o.optBoolean("ed"),
            deleted = o.optBoolean("del"),
            reactions = reactions,
            expiresAt = o.optLong("exp", 0),
            delivery = o.optInt("dl", StoredMessage.DELIVERY_SENT),
            groupId = o.optString("g").ifEmpty { null },
            fileId = o.optString("f").ifEmpty { null },
            precedence = Precedence.entries.firstOrNull { it.name == o.optString("pr") } ?: Precedence.ROUTINE,
        )
    }

    /** Result of [replay]: contacts, current messages, and how many records were superseded. */
    data class Replay(val contacts: Map<String, Contact>, val messages: List<StoredMessage>, val superseded: Int)

    /**
     * Replays contact/message events; other event types are ignored. Keys are conversation keys.
     * A message record with the same conversation and id as an earlier one replaces it in place
     * (edits, deletes, reactions, delivery state); a `gone` record removes it.
     */
    fun replay(events: List<JSONObject>, via: String?): Pair<Map<String, Contact>, List<StoredMessage>> =
        replayFull(events, via).let { it.contacts to it.messages }

    fun replayFull(events: List<JSONObject>, via: String?): Replay {
        val contacts = LinkedHashMap<String, Contact>()
        val messages = ArrayList<StoredMessage?>()
        val byId = HashMap<String, Int>() // convo|id -> index in messages
        var superseded = 0
        for (o in events) when (o.optString("t")) {
            "contact" -> {
                val k = o.getString("k")
                val a = o.optJSONArray("a")
                val c = Contact(
                    k, o.getString("n"), o.optBoolean("v"), o.optBoolean("p"),
                    o.optString("c").ifEmpty { null },
                    if (a == null) emptyList() else List(a.length()) { a.getString(it) },
                    via,
                    o.optBoolean("q"),
                )
                contacts[c.convo] = c
            }
            "msg" -> {
                val m = message(o, via)
                val key = m.id?.let { "${m.convo}|$it" }
                val at = key?.let { byId[it] }
                if (at != null) { messages[at] = m; superseded++ } else {
                    messages += m
                    if (key != null) byId[key] = messages.size - 1
                }
            }
            "gone" -> {
                val at = byId.remove("${o.optString("c")}|${o.optString("id")}")
                if (at != null) { messages[at] = null; superseded += 2 }
            }
        }
        return Replay(contacts, messages.filterNotNull(), superseded)
    }
}

/**
 * Everything ÆTHER persists for the real identity, in Rust-core vaults:
 * `identity.vault` (secret key, then display-name records), `messages.vault`
 * (contact and message events) and `numbers.vault` (bundles, burn-code hash).
 * Burner numbers live in their own vaults; see [BurnerStore].
 */
class Store(context: Context) {
    private val dir = File(context.noBackupFilesDir, "aether").apply { mkdirs() }
    private val keys = KeyWrapper(context)
    private val identityVault: Vault
    @Volatile private var logVault: Vault
    private val numbersVault: Vault

    val keyBacking: String get() = keys.hardwareBacking

    init {
        val key = keys.dataKey()
        try {
            identityVault = Vault.open(File(dir, "identity.vault").path, key)
            logVault = Vault.open(File(dir, "messages.vault").path, key)
            numbersVault = Vault.open(File(dir, "numbers.vault").path, key)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens (or creates) another encrypted vault under the same hardware-wrapped key, for the
     * Maps, Field and eidetic stores. The data key is wiped from memory after opening.
     */
    fun openVault(name: String): Vault {
        require(name.matches(Regex("[a-z0-9-]{1,32}"))) { "bad vault name" }
        val key = keys.dataKey()
        try {
            return Vault.open(File(dir, "$name.vault").path, key)
        } finally {
            key.fill(0)
        }
    }

    fun loadProfile(): Profile? {
        val records = identityVault.readAll()
        if (records.isEmpty()) return null
        val secret = records[0]
        val identity = try { Identity.fromSecret(secret) } finally { secret.fill(0) }
        val name = records.drop(1).lastOrNull()?.decodeToString() ?: "ÆTHER user"
        return Profile(identity, name)
    }

    fun createProfile(displayName: String): Profile {
        check(identityVault.len() == 0UL) { "identity already exists" }
        val identity = Identity.generate()
        val secret = identity.exportSecret()
        try { identityVault.append(secret) } finally { secret.fill(0) }
        identityVault.append(displayName.toByteArray())
        return Profile(identity, displayName)
    }

    /** Replays the real identity's event log into contacts and messages. */
    @Synchronized
    fun loadLog(): Pair<Map<String, Contact>, List<StoredMessage>> = loadLogFull().let { it.contacts to it.messages }

    @Synchronized
    fun loadLogFull(): EventLog.Replay =
        EventLog.replayFull(logVault.readAll().map { JSONObject(it.decodeToString()) }, via = null)

    @Synchronized
    fun saveContact(c: Contact) = logVault.append(EventLog.contactJson(c).toString().toByteArray())

    @Synchronized
    fun saveMessage(m: StoredMessage) = logVault.append(EventLog.messageJson(m).toString().toByteArray())

    @Synchronized
    fun saveGone(convo: String, id: String) = logVault.append(EventLog.goneJson(convo, id).toString().toByteArray())

    /**
     * Rewrites `messages.vault` so it holds every non-message record as before and exactly
     * [keep] as its messages. Expired, deleted and superseded message text is gone from disk.
     */
    @Synchronized
    fun compactLog(keep: List<StoredMessage>) {
        val others = logVault.readAll().filter { !EventLog.isMessageRecord(JSONObject(it.decodeToString())) }
        logVault = rewriteVault("messages", others + keep.map { EventLog.messageJson(it).toString().toByteArray() })
    }

    /**
     * Replaces vault [name] with a fresh one holding [records], via a temporary file and an
     * atomic rename. The old [Vault] object for [name] must not be used afterwards.
     */
    @Synchronized
    fun rewriteVault(name: String, records: List<ByteArray>): Vault {
        require(name.matches(Regex("[a-z0-9-]{1,32}"))) { "bad vault name" }
        val tmp = File(dir, "$name.vault.tmp").apply { delete() }
        val key = keys.dataKey()
        try {
            val v = Vault.open(tmp.path, key)
            try { records.forEach { v.append(it) } } finally { v.destroy() }
        } finally {
            key.fill(0)
        }
        check(tmp.renameTo(File(dir, "$name.vault"))) { "could not replace $name.vault" }
        return openVault(name)
    }

    /** Bundles granted so far, and the current remote-burn code hash (if one is set). */
    fun loadNumbersLog(): Pair<List<Bundle>, ByteArray?> {
        // A later record with the same id is a renewal and replaces the earlier one.
        val bundles = LinkedHashMap<String, Bundle>()
        var burnHash: ByteArray? = null
        for (raw in numbersVault.readAll()) {
            val o = JSONObject(raw.decodeToString())
            when (o.optString("t")) {
                "bundle" -> {
                    val at = o.getLong("at")
                    val period = o.optString("p").takeIf { it in Bundle.PERIODS } ?: "month"
                    val b = Bundle(o.getString("id"), o.getInt("size"), period, at, o.optLong("exp", Bundle.periodEnd(at, period)))
                    bundles[b.id] = b
                }
                "burncode" -> burnHash = o.getString("h").hexToBytes()
            }
        }
        return bundles.values.toList() to burnHash
    }

    fun saveBundle(b: Bundle) = numbersVault.append(
        JSONObject().put("t", "bundle").put("id", b.id).put("size", b.size).put("p", b.period)
            .put("at", b.grantedAt).put("exp", b.expiresAt).toString().toByteArray(),
    )

    fun saveBurnCodeHash(hash: ByteArray) = numbersVault.append(
        JSONObject().put("t", "burncode").put("h", hash.toHex()).toString().toByteArray(),
    )
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Random 128-bit id, base64url without padding (the shared burner-number record format). */
fun randomId(): String = java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
