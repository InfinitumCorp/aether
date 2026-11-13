package com.infinitum.aether.data

import android.content.Context
import com.infinitum.aether.security.KeyWrapper
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aether_core.Identity
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
     * field message or eidetic review message.
     */
    val kind: String? = null,
) {
    companion object {
        const val KIND_POSITION = "pos"
        const val KIND_FIELD = "field"
        const val KIND_EIDETIC = "eid"
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
val StoredMessage.convo get() = convoKey(via, peerKeyHex)

/** JSON event encoding shared by the main log and each burner's vault. */
object EventLog {
    fun contactJson(c: Contact): JSONObject =
        JSONObject().put("t", "contact").put("k", c.keyHex).put("n", c.name).put("v", c.verified)
            .put("p", c.pinned).put("c", c.codename ?: "").put("a", JSONArray(c.addresses)).put("q", c.pq)

    fun messageJson(m: StoredMessage): JSONObject =
        JSONObject().put("t", "msg").put("k", m.peerKeyHex).put("o", m.outgoing).put("ts", m.sentAtMs)
            .put("b", m.body).put("d", m.drop).put("y", m.kind ?: "")

    /** Replays contact/message events; other event types are ignored. Keys are conversation keys. */
    fun replay(events: List<JSONObject>, via: String?): Pair<Map<String, Contact>, List<StoredMessage>> {
        val contacts = LinkedHashMap<String, Contact>()
        val messages = ArrayList<StoredMessage>()
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
            "msg" -> messages += StoredMessage(
                o.getString("k"), o.getBoolean("o"), o.getLong("ts"), o.getString("b"), o.optBoolean("d"), via,
                o.optString("y").ifEmpty { null },
            )
        }
        return contacts to messages
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
    private val logVault: Vault
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
    fun loadLog(): Pair<Map<String, Contact>, List<StoredMessage>> =
        EventLog.replay(logVault.readAll().map { JSONObject(it.decodeToString()) }, via = null)

    fun saveContact(c: Contact) = logVault.append(EventLog.contactJson(c).toString().toByteArray())

    fun saveMessage(m: StoredMessage) = logVault.append(EventLog.messageJson(m).toString().toByteArray())

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
