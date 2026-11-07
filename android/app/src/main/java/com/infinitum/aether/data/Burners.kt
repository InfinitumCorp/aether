package com.infinitum.aether.data

import android.content.Context
import com.infinitum.aether.security.KeyWrapper
import org.json.JSONObject
import uniffi.aether_core.Identity
import uniffi.aether_core.Vault
import uniffi.aether_core.burnerNumber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ScopeKind(val label: String) { PERSONAL("Personal"), TEAM("Team"), MISSION("Mission") }

/** A disposable ÆTHER mesh number: its own identity key, and the 10-digit number derived from it. */
data class Burner(
    val id: String,
    val number: String,
    val label: String,
    val scope: ScopeKind,
    val scopeName: String?,
    val createdAt: Long,
    val rotatedFrom: String?,
    val bundleId: String?,
    val identity: Identity,
) {
    val keyHex: String get() = identity.publicKey().toHex()
    val scopeText: String get() = if (scope == ScopeKind.PERSONAL || scopeName.isNullOrBlank()) scope.label else "${scope.label} · $scopeName"
}

/**
 * One vault per burner number at `burners/<id>.vault`, encrypted under a key wrapped by
 * that number's own Keystore key. Record 0 is the number's secret key; later records are
 * JSON events: `meta` (latest wins), then `contact` / `msg` for its conversations.
 * Burning deletes the Keystore key and the files, so the number, its key and its
 * conversations are gone together.
 */
class BurnerStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "aether/burners").apply { mkdirs() }
    private val keys = KeyWrapper(context)
    private val vaults = ConcurrentHashMap<String, Vault>()

    private fun alias(id: String) = "aether.burner.$id"
    private fun keyFile(id: String) = File(dir, "$id.key")
    private fun vaultFile(id: String) = File(dir, "$id.vault")

    private fun open(id: String): Vault = vaults.getOrPut(id) {
        val key = keys.scopedKey(alias(id), keyFile(id))
        try { Vault.open(vaultFile(id).path, key) } finally { key.fill(0) }
    }

    /** Every live burner with its conversations. Unreadable leftovers are shredded. */
    fun loadAll(): List<Triple<Burner, Map<String, Contact>, List<StoredMessage>>> =
        (dir.listFiles { f -> f.name.endsWith(".vault") } ?: emptyArray()).mapNotNull { f ->
            val id = f.name.removeSuffix(".vault")
            runCatching {
                val records = open(id).readAll()
                val secret = records[0]
                val identity = try { Identity.fromSecret(secret) } finally { secret.fill(0) }
                val events = records.drop(1).map { JSONObject(it.decodeToString()) }
                val meta = events.last { it.optString("t") == "meta" }
                val (c, m) = EventLog.replay(events, via = id)
                Triple(fromMeta(id, meta, identity), c, m)
            }.getOrElse { burn(id); null }
        }.sortedBy { it.first.createdAt }

    fun create(label: String, scope: ScopeKind, scopeName: String?, rotatedFrom: String?, bundleId: String?): Burner {
        val id = randomId()
        val identity = Identity.generate()
        val b = Burner(id, burnerNumber(identity.publicKey()), label, scope, scopeName, System.currentTimeMillis(), rotatedFrom, bundleId, identity)
        val v = open(id)
        val secret = identity.exportSecret()
        try { v.append(secret) } finally { secret.fill(0) }
        saveMeta(b)
        return b
    }

    fun saveMeta(b: Burner) = append(
        b.id,
        JSONObject().put("t", "meta").put("v", 1).put("id", b.id).put("network", "aether-mesh").put("number", b.number)
            .put("label", b.label).put("scope", JSONObject().put("kind", b.scope.name.lowercase()).put("name", b.scopeName ?: JSONObject.NULL))
            .put("state", "active").put("createdAt", b.createdAt).put("rotatedFrom", b.rotatedFrom ?: JSONObject.NULL)
            .put("bundleId", b.bundleId ?: JSONObject.NULL),
    )

    fun append(id: String, o: JSONObject) {
        open(id).append(o.toString().toByteArray())
    }

    fun burn(id: String) {
        vaults.remove(id)?.destroy()
        keys.destroyScoped(alias(id), keyFile(id))
        vaultFile(id).delete()
    }

    private fun fromMeta(id: String, o: JSONObject, identity: Identity): Burner {
        val scope = o.optJSONObject("scope")
        return Burner(
            id = id,
            number = burnerNumber(identity.publicKey()),
            label = o.getString("label"),
            scope = runCatching { ScopeKind.valueOf(scope?.optString("kind").orEmpty().uppercase()) }.getOrDefault(ScopeKind.PERSONAL),
            scopeName = scope?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" },
            createdAt = o.getLong("createdAt"),
            rotatedFrom = o.optString("rotatedFrom").takeIf { it.isNotEmpty() && it != "null" },
            bundleId = o.optString("bundleId").takeIf { it.isNotEmpty() && it != "null" },
            identity = identity,
        )
    }
}
