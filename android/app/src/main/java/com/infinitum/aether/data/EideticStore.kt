package com.infinitum.aether.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.aether_core.AetherException
import uniffi.aether_core.AuditEntry
import uniffi.aether_core.EideticDataset
import uniffi.aether_core.EideticPurpose
import uniffi.aether_core.EideticRole
import uniffi.aether_core.EnrolledTemplate
import uniffi.aether_core.MatchCandidate
import uniffi.aether_core.MatchResult
import uniffi.aether_core.PolicyDecision
import uniffi.aether_core.Review
import uniffi.aether_core.ReviewOutcome
import uniffi.aether_core.SearchRequest
import uniffi.aether_core.Vault
import uniffi.aether_core.auditAppend
import uniffi.aether_core.auditVerify
import uniffi.aether_core.eideticAuthorize
import uniffi.aether_core.eideticCheckEnrollment
import uniffi.aether_core.eideticDecide
import uniffi.aether_core.eideticMatch
import uniffi.aether_core.eideticOpenReview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One enrolled person as the desk shows them. Never carries the template. */
data class RosterEntry(
    val subjectId: String,
    val displayName: String,
    val dataset: EideticDataset,
    val consentRef: String,
    val enrolledAtMs: Long,
    val enrolledBy: String,
)

/** A search as recorded: the request, the policy decision and (when it ran) the match result. */
data class SearchRecord(
    val request: SearchRequest,
    val atMs: Long,
    val decision: PolicyDecision,
    val result: MatchResult?,
    /** Set when the search was authorized but the matcher refused it (e.g. template size mismatch). */
    val error: String?,
)

/** The audit chain and whether it verified. [headHex] is the head hash when [verified]. */
data class AuditState(
    val entries: List<AuditEntry> = emptyList(),
    val verified: Boolean = true,
    val headHex: String? = null,
    val problem: String? = null,
)

/** Plain message for a failure from the core or this store. */
fun eideticMessage(e: Throwable): String = when (e) {
    is AetherException.Malformed -> e.reason
    is AetherException.State -> e.reason
    is AetherException.Vault -> "Secure store error: ${e.reason}"
    is AetherException -> "The core refused this request."
    else -> e.message ?: "Something went wrong."
}

/**
 * Reads template files exported by an approved capture or enrollment station. There is no
 * on-device face model in this build. Accepted: JSON `[0.12, -0.03, ...]` or `{"vector":[...]}`,
 * or raw little-endian float32 (`.f32`), 64–2048 values. Errors never include the values.
 */
object TemplateFile {
    const val MIN_VALUES = 64
    const val MAX_VALUES = 2048
    /** Largest file read; a 2048-value JSON template is well under this. */
    const val MAX_BYTES = 256 * 1024

    fun parse(bytes: ByteArray, fileName: String?): List<Float> {
        require(bytes.isNotEmpty()) { "The file is empty." }
        require(bytes.size <= MAX_BYTES) { "The file is too large to be a template." }
        val binary = fileName?.lowercase()?.endsWith(".f32") == true
        val values = if (binary) {
            fromF32(bytes)
        } else {
            val text = bytes.decodeToString().trimStart('﻿', ' ', '\n', '\r', '\t')
            if (text.startsWith("[") || text.startsWith("{")) {
                try {
                    fromJson(text)
                } catch (e: JSONException) {
                    if (bytes.size % 4 == 0) fromF32(bytes) else throw IllegalArgumentException("The file is not a readable template.")
                }
            } else {
                fromF32(bytes)
            }
        }
        require(values.size in MIN_VALUES..MAX_VALUES) { "A template must have $MIN_VALUES–$MAX_VALUES values; this file has ${values.size}." }
        require(values.all { it.isFinite() }) { "The template contains values that are not numbers." }
        return values
    }

    private fun fromJson(text: String): List<Float> {
        val arr = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("vector")
        return List(arr.length()) { arr.getDouble(it).toFloat() }
    }

    private fun fromF32(bytes: ByteArray): List<Float> {
        require(bytes.size % 4 == 0) { "A raw float32 template must be a whole number of 4-byte values." }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return List(buf.remaining()) { buf.get(it) }
    }
}

/**
 * ÆTHER eidetic MIL™ desk storage.
 *
 * - [vault] holds JSON events: this device's role, searches (request, decision, result without
 *   the probe), reviews (latest per search id) and the hash-chained audit log.
 * - [templates] holds sealed enrolled templates and removal tombstones, and nothing else.
 *   Templates are read from it only to build the roster summary (vectors dropped) and to run a match.
 *
 * Every step appends an audit entry. Details carry ids, purpose, dataset, authority, decision,
 * scores and the probe digest only.
 */
class EideticStore(private val vault: Vault, private val templates: Vault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _role = MutableStateFlow<EideticRole?>(null)
    /** This device's role, or null until one is chosen. */
    val role: StateFlow<EideticRole?> = _role.asStateFlow()

    private val _operator = MutableStateFlow("")
    /** Name recorded as the actor on this device (the profile's display name). */
    val operator: StateFlow<String> = _operator.asStateFlow()

    private val _roster = MutableStateFlow<List<RosterEntry>>(emptyList())
    val rosterSummary: StateFlow<List<RosterEntry>> = _roster.asStateFlow()

    private val _searches = MutableStateFlow<List<SearchRecord>>(emptyList())
    /** Newest first. */
    val searches: StateFlow<List<SearchRecord>> = _searches.asStateFlow()

    private val _reviews = MutableStateFlow<Map<String, Review>>(emptyMap())
    /** Latest review state per search id. */
    val reviews: StateFlow<Map<String, Review>> = _reviews.asStateFlow()

    private val _audit = MutableStateFlow(AuditState())
    val audit: StateFlow<AuditState> = _audit.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Set when the stores could not be read. */
    val loadError: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch { lock.withLock { load() } }
    }

    /** Sets the name recorded for actions on this device. */
    fun setOperator(name: String) {
        _operator.value = name.replace(Regex("\\p{Cntrl}"), " ").trim().take(128)
    }

    // ---- Loading -------------------------------------------------------------------------

    private fun load() {
        try {
            var role: EideticRole? = null
            val searches = LinkedHashMap<String, SearchRecord>()
            val reviews = LinkedHashMap<String, Review>()
            val audit = ArrayList<AuditEntry>()
            for (raw in vault.readAll()) {
                val o = JSONObject(raw.decodeToString())
                when (o.optString("t")) {
                    "role" -> role = enumOrNull<EideticRole>(o.optString("r"))
                    "search" -> readSearch(o)?.let { searches[it.request.searchId] = it }
                    "review" -> readReview(o)?.let { reviews[it.searchId] = it }
                    "audit" -> audit += readAudit(o)
                }
            }
            _role.value = role
            _searches.value = searches.values.sortedByDescending { it.atMs }
            _reviews.value = reviews
            setAudit(audit)
            _roster.value = readRosterSummary()
            _error.value = null
        } catch (e: Exception) {
            _error.value = "The eidetic store could not be read: ${eideticMessage(e)}"
        } finally {
            _loaded.value = true
        }
    }

    private fun setAudit(entries: List<AuditEntry>) {
        _audit.value = try {
            AuditState(entries, verified = true, headHex = auditVerify(entries))
        } catch (e: Exception) {
            AuditState(entries, verified = false, problem = eideticMessage(e))
        }
    }

    /** Re-reads the audit chain from the vault and verifies it. */
    suspend fun reverifyAudit(): Unit = io {
        val entries = vault.readAll().map { JSONObject(it.decodeToString()) }.filter { it.optString("t") == "audit" }.map(::readAudit)
        setAudit(entries)
    }

    /** Roster summary: replays the templates vault and keeps ids, names, datasets and consent refs only. */
    private fun readRosterSummary(): List<RosterEntry> {
        val out = LinkedHashMap<String, RosterEntry>()
        for (raw in templates.readAll()) {
            val o = JSONObject(raw.decodeToString())
            val id = o.optString("s")
            when (o.optString("t")) {
                "enroll" -> {
                    val ds = enumOrNull<EideticDataset>(o.optString("d")) ?: continue
                    out[id] = RosterEntry(id, o.optString("n"), ds, o.optString("c"), o.optLong("at"), o.optString("by"))
                }
                "remove" -> out.remove(id)
            }
        }
        return out.values.toList()
    }

    /** Full roster with templates, for one match. Held only for the duration of the call. */
    private fun readRoster(dataset: EideticDataset): List<EnrolledTemplate> {
        val out = LinkedHashMap<String, EnrolledTemplate>()
        for (raw in templates.readAll()) {
            val o = JSONObject(raw.decodeToString())
            val id = o.optString("s")
            when (o.optString("t")) {
                "enroll" -> {
                    val ds = enumOrNull<EideticDataset>(o.optString("d")) ?: continue
                    if (ds != dataset) {
                        out.remove(id)
                        continue
                    }
                    val v = o.getJSONArray("v")
                    out[id] = EnrolledTemplate(id, o.optString("n"), ds, o.optString("c"), List(v.length()) { v.getDouble(it).toFloat() })
                }
                "remove" -> out.remove(id)
            }
        }
        return out.values.toList()
    }

    // ---- Role ----------------------------------------------------------------------------

    /** Changes this device's role. Audited. */
    suspend fun setRole(newRole: EideticRole): Unit = io {
        val actor = actor()
        val old = _role.value
        if (old == newRole) return@io
        record(actor, "eidetic.role.set", newRole.name, "from=${old?.name ?: "none"}; to=${newRole.name}")
        put(JSONObject().put("t", "role").put("r", newRole.name).put("at", now()).put("by", actor))
        _role.value = newRole
    }

    // ---- Enrollment ----------------------------------------------------------------------

    /**
     * Checks an enrollment with the core (enrollment role, consent reference, 64–2048 values),
     * then seals the normalized template into the templates vault. Audited, including refusals.
     */
    suspend fun enroll(
        subjectId: String,
        displayName: String,
        dataset: EideticDataset,
        consentRef: String,
        vector: List<Float>,
    ): RosterEntry = io {
        val actor = actor()
        val role = _role.value ?: error("Choose this device's role first.")
        val id = subjectId.trim()
        if (_roster.value.any { it.subjectId == id }) {
            error("$id is already enrolled. Remove the existing enrollment first.")
        }
        val checked = try {
            eideticCheckEnrollment(EnrolledTemplate(id, displayName, dataset, consentRef, vector), role)
        } catch (e: AetherException) {
            record(actor, "eidetic.enroll.refused", clip(id).ifEmpty { "-" }, "dataset=${dataset.name}; role=${role.name}; reason=${clip(eideticMessage(e))}")
            throw e
        }
        val at = now()
        val v = JSONArray().apply { checked.vector.forEach { put(it.toDouble()) } }
        // Record first: if the audit write fails, nothing is sealed.
        record(actor, "eidetic.enroll", checked.subjectId, "dataset=${checked.dataset.name}; consent=${clip(checked.consentRef)}; dims=${checked.vector.size}")
        templates.append(
            JSONObject().put("t", "enroll").put("s", checked.subjectId).put("n", checked.displayName)
                .put("d", checked.dataset.name).put("c", checked.consentRef).put("at", at).put("by", actor).put("v", v)
                .toString().toByteArray(),
        )
        val entry = RosterEntry(checked.subjectId, checked.displayName, checked.dataset, checked.consentRef, at, actor)
        _roster.value = _roster.value + entry
        entry
    }

    /** Removes a person from the roster with a tombstone in the templates vault. Audited. */
    suspend fun remove(subjectId: String): Unit = io {
        val actor = actor()
        val role = _role.value
        if (role != EideticRole.ENROLLMENT) error("Only enrollment staff may remove people from the roster.")
        val entry = _roster.value.firstOrNull { it.subjectId == subjectId } ?: error("$subjectId is not enrolled.")
        record(actor, "eidetic.roster.remove", entry.subjectId, "dataset=${entry.dataset.name}; consent=${clip(entry.consentRef)}")
        templates.append(JSONObject().put("t", "remove").put("s", entry.subjectId).put("at", now()).put("by", actor).toString().toByteArray())
        _roster.value = _roster.value.filterNot { it.subjectId == subjectId }
    }

    // ---- Search --------------------------------------------------------------------------

    /**
     * Authorizes a search with the core and audits the decision. A denied search is recorded
     * and returned without running the matcher. An allowed search is matched against the
     * enrolled roster for its dataset only, and a pending review is opened. The probe is never
     * stored; only its digest is.
     */
    suspend fun search(
        purpose: EideticPurpose,
        dataset: EideticDataset,
        legalAuthority: String,
        probe: List<Float>,
    ): SearchRecord = io {
        val actor = actor()
        val role = _role.value ?: error("Choose this device's role first.")
        val at = now()
        val request = SearchRequest(newSearchId(at), actor, role, purpose, dataset, legalAuthority.trim())
        val decision = eideticAuthorize(request)
        val base = "purpose=${purpose.name}; dataset=${dataset.name}; role=${role.name}; authority=${clip(request.legalAuthority)}"
        if (!decision.allowed) {
            record(actor, "eidetic.search.denied", request.searchId, "$base; reason=${clip(decision.reason)}")
            val rec = SearchRecord(request, at, decision, null, null)
            saveSearch(rec)
            return@io rec
        }
        record(actor, "eidetic.search.authorized", request.searchId, base)
        val result = try {
            eideticMatch(request, probe, readRoster(dataset))
        } catch (e: AetherException) {
            val msg = eideticMessage(e)
            record(actor, "eidetic.search.failed", request.searchId, "reason=${clip(msg)}")
            val rec = SearchRecord(request, at, decision, null, msg)
            saveSearch(rec)
            return@io rec
        }
        val scores = result.candidates.joinToString(",") { "${clip(it.subjectId, 64)}:${"%.3f".format(Locale.US, it.score)}" }.ifEmpty { "none" }
        record(
            actor, "eidetic.search.matched", request.searchId,
            "candidates=$scores; confidence=${"%.3f".format(Locale.US, result.confidence)}; " +
                "margin=${"%.3f".format(Locale.US, result.margin)}; probe=${result.probeDigest}",
        )
        val rec = SearchRecord(request, at, decision, result, null)
        saveSearch(rec)
        val review = eideticOpenReview(request.searchId, actor)
        record(actor, "eidetic.review.opened", request.searchId, "outcome=PENDING; searcher=${clip(actor, 64)}")
        saveReview(review)
        rec
    }

    // ---- Review --------------------------------------------------------------------------

    /**
     * Records a reviewer's decision. The core refuses the searcher, non-reviewers, already
     * decided reviews and confirmations of unreported candidates; refusals are audited.
     */
    suspend fun decide(searchId: String, outcome: ReviewOutcome, confirmedSubject: String?, note: String): Review = io {
        val actor = actor()
        val role = _role.value ?: error("Choose this device's role first.")
        val review = _reviews.value[searchId] ?: error("There is no review for $searchId.")
        val result = _searches.value.firstOrNull { it.request.searchId == searchId }?.result ?: error("$searchId has no match result to review.")
        val at = now()
        val decided = try {
            eideticDecide(review, result, actor, role, outcome, if (outcome == ReviewOutcome.CONFIRMED) confirmedSubject else null, note, at)
        } catch (e: AetherException) {
            record(actor, "eidetic.review.refused", searchId, "outcome=${outcome.name}; role=${role.name}; reason=${clip(eideticMessage(e))}")
            throw e
        }
        record(
            actor, "eidetic.review.decided", searchId,
            "outcome=${decided.outcome.name}; confirmed=${decided.confirmedSubject?.let { clip(it, 64) } ?: "-"}; " +
                "searcher=${clip(review.searcher, 64)}; note=${if (decided.note.isEmpty()) "no" else "yes"}",
        )
        saveReview(decided)
        decided
    }

    // ---- Persistence ---------------------------------------------------------------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun actor(): String = _operator.value.ifEmpty { error("No operator name is set on this device.") }

    private fun put(o: JSONObject) {
        vault.append(o.toString().toByteArray())
    }

    private fun record(actor: String, action: String, subject: String, detail: String) {
        val cur = _audit.value
        val prev = cur.entries.lastOrNull()
        val at = maxOf(now(), prev?.atMs ?: 0L)
        val e = auditAppend(prev, at, actor, action, subject, detail)
        put(
            JSONObject().put("t", "audit").put("seq", e.seq.toLong()).put("at", e.atMs).put("a", e.actor)
                .put("x", e.action).put("s", e.subject).put("d", e.detail).put("p", e.prevHash.toHex()).put("h", e.hash.toHex()),
        )
        val entries = cur.entries + e
        // Appends only extend a verified chain; a chain that already failed stays failed.
        _audit.value = if (cur.verified) AuditState(entries, true, e.hash.toHex()) else cur.copy(entries = entries)
    }

    private fun saveSearch(r: SearchRecord) {
        val o = JSONObject().put("t", "search").put("id", r.request.searchId).put("by", r.request.searcher)
            .put("role", r.request.role.name).put("p", r.request.purpose.name).put("d", r.request.dataset.name)
            .put("auth", r.request.legalAuthority).put("at", r.atMs).put("ok", r.decision.allowed).put("why", r.decision.reason)
            .put("err", r.error ?: "")
        r.result?.let { m ->
            val c = JSONArray()
            m.candidates.forEach { c.put(JSONObject().put("s", it.subjectId).put("n", it.displayName).put("sc", it.score.toDouble())) }
            o.put("res", JSONObject().put("c", c).put("conf", m.confidence.toDouble()).put("mg", m.margin.toDouble()).put("ev", m.evidence).put("pd", m.probeDigest))
        }
        put(o)
        _searches.value = (listOf(r) + _searches.value.filterNot { it.request.searchId == r.request.searchId }).sortedByDescending { it.atMs }
    }

    private fun saveReview(r: Review) {
        put(
            JSONObject().put("t", "review").put("id", r.searchId).put("by", r.searcher).put("o", r.outcome.name)
                .put("rv", r.reviewer ?: "").put("at", r.decidedAtMs ?: 0L).put("cs", r.confirmedSubject ?: "").put("note", r.note),
        )
        _reviews.value = _reviews.value + (r.searchId to r)
    }

    private fun readSearch(o: JSONObject): SearchRecord? {
        val req = SearchRequest(
            o.getString("id"), o.optString("by"),
            enumOrNull<EideticRole>(o.optString("role")) ?: return null,
            enumOrNull<EideticPurpose>(o.optString("p")) ?: return null,
            enumOrNull<EideticDataset>(o.optString("d")) ?: return null,
            o.optString("auth"),
        )
        val result = o.optJSONObject("res")?.let { m ->
            val c = m.optJSONArray("c") ?: JSONArray()
            MatchResult(
                req.searchId,
                List(c.length()) { i -> c.getJSONObject(i).let { MatchCandidate(it.getString("s"), it.optString("n"), it.getDouble("sc").toFloat()) } },
                m.optDouble("conf", 0.0).toFloat(), m.optDouble("mg", 0.0).toFloat(), m.optString("ev"), m.optString("pd"),
            )
        }
        return SearchRecord(req, o.optLong("at"), PolicyDecision(o.optBoolean("ok"), o.optString("why")), result, o.optString("err").ifEmpty { null })
    }

    private fun readReview(o: JSONObject): Review? {
        val outcome = enumOrNull<ReviewOutcome>(o.optString("o")) ?: return null
        return Review(
            o.getString("id"), o.optString("by"),
            outcome,
            o.optString("rv").ifEmpty { null },
            o.optLong("at").takeIf { it > 0L },
            o.optString("cs").ifEmpty { null },
            o.optString("note"),
        )
    }

    private fun readAudit(o: JSONObject) = AuditEntry(
        o.getLong("seq").toULong(), o.getLong("at"), o.optString("a"), o.optString("x"), o.optString("s"), o.optString("d"),
        o.getString("p").hexToBytes(), o.getString("h").hexToBytes(),
    )

    companion object {
        /** Datasets each purpose may search (mirrors the core; the core enforces it). */
        fun datasetsFor(p: EideticPurpose): List<EideticDataset> = when (p) {
            EideticPurpose.CHECKPOINT_VERIFICATION, EideticPurpose.ACCESS_CONTROL -> listOf(EideticDataset.ACCESS_LIST, EideticDataset.OWN_FORCES)
            EideticPurpose.PERSONNEL_RECOVERY, EideticPurpose.CASUALTY_IDENTIFICATION -> listOf(EideticDataset.OWN_FORCES)
        }

        private fun now() = System.currentTimeMillis()

        private fun newSearchId(at: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
            val rnd = ByteArray(2).also { java.security.SecureRandom().nextBytes(it) }.toHex()
            return "S-$stamp-$rnd"
        }

        /** One-line, length-limited text for an audit detail value (`;` would split fields). */
        private fun clip(s: String, max: Int = 160): String =
            s.replace(Regex("[\\p{Cntrl};]"), " ").trim().take(max)

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
            enumValues<E>().firstOrNull { it.name == name }
    }
}
