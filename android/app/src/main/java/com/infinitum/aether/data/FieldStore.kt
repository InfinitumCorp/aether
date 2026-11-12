package com.infinitum.aether.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aether_core.AetherException
import uniffi.aether_core.AuditEntry
import uniffi.aether_core.Capture
import uniffi.aether_core.FieldMessage
import uniffi.aether_core.Mission
import uniffi.aether_core.MissionState
import uniffi.aether_core.Packet
import uniffi.aether_core.PlaceClass
import uniffi.aether_core.Precedence
import uniffi.aether_core.auditAppend
import uniffi.aether_core.auditVerify
import uniffi.aether_core.encodeFieldMessage
import uniffi.aether_core.decodeFieldMessage
import uniffi.aether_core.fieldRefine
import uniffi.aether_core.fieldVerifyPacket
import uniffi.aether_core.missionAccept
import uniffi.aether_core.missionCancel
import uniffi.aether_core.missionClaim
import uniffi.aether_core.missionPost
import uniffi.aether_core.missionReject
import uniffi.aether_core.missionRelease
import uniffi.aether_core.missionSubmit
import uniffi.aether_core.Vault
import java.security.MessageDigest
import kotlin.math.roundToLong

/** A capture and what refining it produced: a packet id, or the reason it stayed on this device. */
data class FieldCapture(val capture: Capture, val packetId: String?, val reason: String, val released: Boolean)

/** A packet or mission snapshot received from a teammate. [applied] is false when it was refused. */
data class FieldReceived(
    val convo: String,
    val sender: String,
    val atMs: Long,
    val packet: Packet?,
    val mission: Mission?,
    val applied: Boolean,
    val note: String,
)

sealed interface AuditStatus {
    data object Loading : AuditStatus
    /** [head] is the hex hash of the newest entry (64 zeros for an empty chain). */
    data class Verified(val head: String, val count: Int) : AuditStatus
    data class Broken(val message: String) : AuditStatus
}

/** Plain-language text for a core error. */
fun fieldError(t: Throwable): String = when (t) {
    is AetherException.State -> t.reason
    is AetherException.Malformed -> t.reason
    is AetherException.Vault -> t.reason
    else -> t.message ?: t.toString()
}

/**
 * ÆTHER Field™ state: captures, packets, missions (latest snapshot per id wins), received
 * packets and mission snapshots, and a hash-chained audit log, all as append-only JSON
 * records in one encrypted vault. Call [load] before use; every other call loads on demand.
 * All vault IO runs on [Dispatchers.IO] under one lock.
 */
class FieldStore(private val vault: Vault) {
    private val lock = Mutex()
    private var loaded = false

    private val _captures = MutableStateFlow<List<FieldCapture>>(emptyList())
    /** Own captures, newest first. */
    val captures: StateFlow<List<FieldCapture>> = _captures.asStateFlow()
    private val _packets = MutableStateFlow<List<Packet>>(emptyList())
    /** Own packets, newest first. */
    val packets: StateFlow<List<Packet>> = _packets.asStateFlow()
    private val _missions = MutableStateFlow<Map<String, Mission>>(emptyMap())
    /** Missions by id (latest snapshot). */
    val missions: StateFlow<Map<String, Mission>> = _missions.asStateFlow()
    private val _missionFrom = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Mission id → conversation the latest received snapshot came from. */
    val missionFrom: StateFlow<Map<String, String>> = _missionFrom.asStateFlow()
    private val _received = MutableStateFlow<List<FieldReceived>>(emptyList())
    /** Packets and mission snapshots from teammates, newest first. */
    val received: StateFlow<List<FieldReceived>> = _received.asStateFlow()
    private val _audit = MutableStateFlow<List<AuditEntry>>(emptyList())
    /** The audit chain, oldest first. */
    val audit: StateFlow<List<AuditEntry>> = _audit.asStateFlow()
    private val _auditStatus = MutableStateFlow<AuditStatus>(AuditStatus.Loading)
    val auditStatus: StateFlow<AuditStatus> = _auditStatus.asStateFlow()

    private val seen = HashSet<String>()

    // ---- Loading ---------------------------------------------------------------------------

    /** Rebuilds state from the vault and verifies the audit chain. Safe to call repeatedly. */
    suspend fun load() = locked { }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) { replay(); loaded = true }
            block()
        }
    }

    private fun replay() {
        val captures = LinkedHashMap<String, FieldCapture>()
        val packets = LinkedHashMap<String, Packet>()
        val missions = LinkedHashMap<String, Mission>()
        val from = HashMap<String, String>()
        val received = ArrayList<FieldReceived>()
        val audit = ArrayList<AuditEntry>()
        for (raw in vault.readAll()) {
            val o = runCatching { JSONObject(raw.decodeToString()) }.getOrNull() ?: continue
            runCatching {
                when (o.optString("t")) {
                    "capture" -> {
                        val c = captureFrom(o.getJSONObject("c"))
                        captures[c.id] = FieldCapture(c, null, "", false)
                    }
                    "outcome" -> captures[o.getString("cid")]?.let { fc ->
                        captures[fc.capture.id] = fc.copy(
                            packetId = o.optString("pid").ifEmpty { null },
                            reason = o.optString("why"),
                            released = o.optBoolean("rel"),
                        )
                    }
                    "packet" -> packetFrom(o.getJSONObject("p")).let { packets[it.id] = it }
                    "mission" -> {
                        val m = missionFrom(o.getJSONObject("m"))
                        missions[m.id] = m
                        o.optString("from").ifEmpty { null }?.let { from[m.id] = it }
                    }
                    "recv" -> {
                        seen += o.getString("k")
                        val msg = runCatching { decodeFieldMessage(o.getString("b")) }.getOrNull()
                        received += FieldReceived(
                            o.getString("c"), o.optString("n"), o.getLong("at"),
                            msg?.packet, msg?.mission, o.optBoolean("ok"), o.optString("why"),
                        )
                    }
                    "audit" -> audit += auditFrom(o.getJSONObject("e"))
                }
            }
        }
        _captures.value = captures.values.reversed()
        _packets.value = packets.values.reversed()
        _missions.value = missions
        _missionFrom.value = from
        _received.value = received.reversed()
        _audit.value = audit
        _auditStatus.value = try {
            AuditStatus.Verified(auditVerify(audit), audit.size)
        } catch (e: Exception) {
            AuditStatus.Broken(fieldError(e))
        }
    }

    // ---- Writing ---------------------------------------------------------------------------

    private fun put(o: JSONObject) {
        vault.append(o.toString().toByteArray())
    }

    /** Appends an audit entry chained to the newest one and stores it. */
    private fun log(actor: String, action: String, subject: String, detail: String) {
        val prev = _audit.value.lastOrNull()
        val at = maxOf(System.currentTimeMillis(), prev?.atMs ?: Long.MIN_VALUE)
        val e = auditAppend(prev, at, actor.ifBlank { "unknown" }.take(256), action, subject.take(256), detail.take(2048))
        put(JSONObject().put("t", "audit").put("e", auditJson(e)))
        _audit.value = _audit.value + e
        val st = _auditStatus.value
        if (st is AuditStatus.Verified) _auditStatus.value = AuditStatus.Verified(e.hash.toHex(), _audit.value.size)
    }

    private fun savePacket(p: Packet) {
        put(JSONObject().put("t", "packet").put("p", packetJson(p)))
        _packets.value = listOf(p) + _packets.value.filter { it.id != p.id }
    }

    private fun saveMission(m: Mission, from: String?) {
        val o = JSONObject().put("t", "mission").put("m", missionJson(m))
        if (from != null) o.put("from", from)
        put(o)
        _missions.value = _missions.value + (m.id to m)
        if (from != null) _missionFrom.value = _missionFrom.value + (m.id to from)
    }

    private fun saveOutcome(fc: FieldCapture) {
        put(
            JSONObject().put("t", "outcome").put("cid", fc.capture.id).put("pid", fc.packetId ?: "")
                .put("why", fc.reason).put("rel", fc.released),
        )
        _captures.value = listOf(fc) + _captures.value.filter { it.capture.id != fc.capture.id }
    }

    // ---- Captures and packets --------------------------------------------------------------

    /**
     * Records a capture and refines it. A capture at a protected place stays on this device
     * (the result has no packet id and carries the core's reason) until [release] is called.
     * Coordinates are rounded to 7 decimals, the precision of the wire format, so a packet's
     * proof still verifies after it is sent.
     */
    suspend fun capture(
        owner: String,
        note: String,
        placeLabel: String,
        placeClass: PlaceClass,
        lat: Double?,
        lon: Double?,
    ): Result<FieldCapture> = runCatching {
        locked {
            val now = System.currentTimeMillis()
            val c = Capture("CAP-" + randomId().take(16), owner.trim(), note, placeLabel, placeClass, lat?.let { round7(it) }, lon?.let { round7(it) }, now)
            val outcome = fieldRefine(c, false, now)
            put(JSONObject().put("t", "capture").put("c", captureJson(c)))
            log(owner, "field.capture", c.id, "place=${placeClass.name.lowercase()}; position=${if (c.lat != null) "yes" else "no"}")
            val p = outcome.packet
            val fc = FieldCapture(c, p?.id, outcome.reason, false)
            saveOutcome(fc)
            if (p != null) {
                savePacket(p)
                log(owner, "field.refine", p.id, "capture=${c.id}; quality=${p.quality}; redactions=${p.redactions}")
            } else {
                log(owner, "field.keep_on_device", c.id, "place=${placeClass.name.lowercase()}")
            }
            fc
        }
    }

    /** Explicitly releases a capture kept on this device and refines it into a packet. */
    suspend fun release(captureId: String, owner: String): Result<FieldCapture> = runCatching {
        locked {
            val fc = _captures.value.firstOrNull { it.capture.id == captureId } ?: error("No such capture")
            if (fc.packetId != null) return@locked fc
            val outcome = fieldRefine(fc.capture, true, System.currentTimeMillis())
            val p = outcome.packet ?: error(outcome.reason)
            log(owner, "field.release", fc.capture.id, "place=${fc.capture.placeClass.name.lowercase()}")
            val done = fc.copy(packetId = p.id, reason = outcome.reason, released = true)
            saveOutcome(done)
            savePacket(p)
            log(owner, "field.refine", p.id, "capture=${fc.capture.id}; quality=${p.quality}; redactions=${p.redactions}; released=yes")
            done
        }
    }

    /** Records that a packet was sent to a teammate. Call after a successful send. */
    suspend fun recordPacketShared(actor: String, packet: Packet, contactName: String) = runCatching {
        locked { log(actor, "field.packet.share", packet.id, "to=${clean(contactName)}") }
    }

    /** Records that a mission snapshot was sent to a teammate. Call after a successful send. */
    suspend fun recordMissionShared(actor: String, mission: Mission, contactName: String) = runCatching {
        locked { log(actor, "field.mission.share", mission.id, "to=${clean(contactName)}; state=${mission.state.name.lowercase()}") }
    }

    // ---- Missions --------------------------------------------------------------------------

    suspend fun postMission(
        who: String,
        title: String,
        brief: String,
        placeClass: PlaceClass,
        precedence: Precedence,
    ): Result<Mission> = runCatching {
        locked {
            val m = missionPost("MSN-" + randomId().take(16), title, brief, placeClass, precedence, who.trim(), System.currentTimeMillis())
            saveMission(m, null)
            log(who, "field.mission.post", m.id, "place=${placeClass.name.lowercase()}; precedence=${precedence.name.lowercase()}")
            m
        }
    }

    suspend fun claim(id: String, who: String) = change(id, who, "claim") { missionClaim(it, who, System.currentTimeMillis()) }

    suspend fun submit(id: String, who: String, packetId: String) = change(id, who, "submit", "packet=$packetId") { m ->
        val p = _packets.value.firstOrNull { it.id == packetId } ?: error("No such packet")
        missionSubmit(m, who, p)
    }

    suspend fun accept(id: String, who: String) = change(id, who, "accept") { missionAccept(it, who) }

    suspend fun reject(id: String, who: String) = change(id, who, "reject") { missionReject(it, who) }

    suspend fun releaseMission(id: String, who: String) = change(id, who, "release") { missionRelease(it, who) }

    suspend fun cancel(id: String, who: String) = change(id, who, "cancel") { missionCancel(it, who, System.currentTimeMillis()) }

    private suspend fun change(id: String, who: String, verb: String, extra: String = "", op: (Mission) -> Mission): Result<Mission> = runCatching {
        locked {
            val m = _missions.value[id] ?: error("No such mission")
            val next = op(m)
            saveMission(next, null)
            log(who, "field.mission.$verb", id, "state=${next.state.name.lowercase()}" + if (extra.isNotEmpty()) "; $extra" else "")
            next
        }
    }

    // ---- Received --------------------------------------------------------------------------

    /**
     * Merges the node's field inbox. Each item is handled once (also across restarts).
     * Packets whose proof verifies are listed; mission snapshots replace the local copy only
     * when they are a legal next state. [names] maps conversation keys to contact names.
     */
    suspend fun mergeInbox(items: List<Pair<String, FieldMessage>>, names: Map<String, String>, me: String) = runCatching {
        locked {
            for ((convo, msg) in items) {
                val body = runCatching { encodeFieldMessage(msg) }.getOrNull()
                val key = sha256(convo + "\n" + (body ?: msg.toString()))
                if (key in seen) continue
                seen += key
                val sender = names[convo] ?: convo.takeLast(12)
                val now = System.currentTimeMillis()
                var ok: Boolean
                var why: String
                val packet = msg.packet
                val mission = msg.mission
                when {
                    packet != null -> {
                        ok = body != null && fieldVerifyPacket(packet)
                        why = if (ok) "Proof verified." else "Proof does not verify."
                        log(me, "field.received.packet", packet.id, "from=${clean(sender)}; verified=${if (ok) "yes" else "no"}")
                    }
                    mission != null -> {
                        why = judge(_missions.value[mission.id], mission)
                        ok = why.isEmpty()
                        if (ok) {
                            why = "Applied."
                            saveMission(mission, convo)
                        }
                        log(me, "field.received.mission", mission.id, "from=${clean(sender)}; state=${mission.state.name.lowercase()}; applied=${if (ok) "yes" else "no"}")
                    }
                    else -> { ok = false; why = "Empty message." }
                }
                put(
                    JSONObject().put("t", "recv").put("k", key).put("c", convo).put("n", sender).put("at", now)
                        .put("b", body ?: "").put("ok", ok).put("why", why),
                )
                _received.value = listOf(FieldReceived(convo, sender, now, packet, mission, ok, why)) + _received.value
            }
        }
    }

    /** Empty string when [incoming] may replace [local]; otherwise the reason it may not. */
    private fun judge(local: Mission?, incoming: Mission): String {
        if (local == null) return ""
        if (local == incoming) return "Already up to date."
        if (local.postedBy != incoming.postedBy || local.placeClass != incoming.placeClass) return "Does not match the local copy."
        if (local.state == MissionState.ACCEPTED || local.state == MissionState.CANCELLED) return "Mission is already closed."
        if (!incoming.rejectedPackets.containsAll(local.rejectedPackets)) return "Older than the local copy."
        if (incoming.packetId?.let { it in incoming.rejectedPackets } == true) return "Submits a packet that was turned down."
        if (incoming.state == MissionState.ACCEPTED || incoming.state == MissionState.CANCELLED) return ""
        val l = rank(local.state)
        val i = rank(incoming.state)
        if (i > l) return ""
        // The two legal backward moves: a rejection (submitted → claimed, one more turned-down
        // packet) and a release (claimed → open).
        if (local.state == MissionState.SUBMITTED && incoming.state == MissionState.CLAIMED &&
            incoming.rejectedPackets.size > local.rejectedPackets.size && incoming.claimedBy == local.claimedBy
        ) return ""
        if (local.state == MissionState.CLAIMED && incoming.state == MissionState.OPEN && incoming.claimedBy == null &&
            incoming.rejectedPackets.size == local.rejectedPackets.size
        ) return ""
        if (i == l && incoming.rejectedPackets.size > local.rejectedPackets.size && incoming.claimedBy == local.claimedBy) return ""
        return "Would move the mission backwards."
    }

    private fun rank(s: MissionState) = when (s) {
        MissionState.OPEN -> 0
        MissionState.CLAIMED -> 1
        MissionState.SUBMITTED -> 2
        MissionState.ACCEPTED, MissionState.CANCELLED -> 3
    }

    // ---- JSON ------------------------------------------------------------------------------

    private companion object {
        fun round7(v: Double): Double = (v * 1e7).roundToLong() / 1e7

        fun clean(s: String) = s.replace(Regex("[\\p{Cntrl};=]"), " ").trim().take(64)

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()

        fun JSONObject.optDouble2(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

        fun captureJson(c: Capture): JSONObject = JSONObject().put("id", c.id).put("o", c.owner).put("n", c.note)
            .put("pl", c.placeLabel).put("pc", c.placeClass.name).put("lat", c.lat).put("lon", c.lon).put("at", c.atMs)

        fun captureFrom(o: JSONObject) = Capture(
            o.getString("id"), o.getString("o"), o.getString("n"), o.getString("pl"), PlaceClass.valueOf(o.getString("pc")),
            o.optDouble2("lat"), o.optDouble2("lon"), o.getLong("at"),
        )

        fun packetJson(p: Packet): JSONObject = JSONObject().put("id", p.id).put("cid", p.captureId).put("o", p.owner)
            .put("obs", p.observation).put("pl", p.placeLabel).put("pc", p.placeClass.name).put("lat", p.lat).put("lon", p.lon)
            .put("at", p.atMs).put("q", p.quality.toInt()).put("r", p.redactions.toLong()).put("pf", p.proof)

        fun packetFrom(o: JSONObject) = Packet(
            o.getString("id"), o.getString("cid"), o.getString("o"), o.getString("obs"), o.getString("pl"),
            PlaceClass.valueOf(o.getString("pc")), o.optDouble2("lat"), o.optDouble2("lon"), o.getLong("at"),
            o.getInt("q").toUByte(), o.getLong("r").toUInt(), o.getString("pf"),
        )

        fun missionJson(m: Mission): JSONObject = JSONObject().put("id", m.id).put("ti", m.title).put("br", m.brief)
            .put("pc", m.placeClass.name).put("pr", m.precedence.name).put("pb", m.postedBy).put("pa", m.postedAtMs)
            .put("st", m.state.name).put("cb", m.claimedBy).put("ca", m.claimedAtMs).put("pk", m.packetId)
            .put("rj", JSONArray(m.rejectedPackets))

        fun missionFrom(o: JSONObject): Mission {
            val rj = o.optJSONArray("rj")
            return Mission(
                o.getString("id"), o.getString("ti"), o.getString("br"), PlaceClass.valueOf(o.getString("pc")),
                Precedence.valueOf(o.getString("pr")), o.getString("pb"), o.getLong("pa"), MissionState.valueOf(o.getString("st")),
                if (o.has("cb") && !o.isNull("cb")) o.getString("cb") else null,
                if (o.has("ca") && !o.isNull("ca")) o.getLong("ca") else null,
                if (o.has("pk") && !o.isNull("pk")) o.getString("pk") else null,
                if (rj == null) emptyList() else List(rj.length()) { rj.getString(it) },
            )
        }

        fun auditJson(e: AuditEntry): JSONObject = JSONObject().put("seq", e.seq.toLong()).put("at", e.atMs).put("a", e.actor)
            .put("ac", e.action).put("s", e.subject).put("d", e.detail).put("ph", e.prevHash.toHex()).put("h", e.hash.toHex())

        fun auditFrom(o: JSONObject) = AuditEntry(
            o.getLong("seq").toULong(), o.getLong("at"), o.getString("a"), o.getString("ac"), o.getString("s"), o.getString("d"),
            o.getString("ph").hexToBytes(), o.getString("h").hexToBytes(),
        )
    }
}
