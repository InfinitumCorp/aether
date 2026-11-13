package com.infinitum.aether.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.infinitum.aether.data.EideticStore
import com.infinitum.aether.data.SearchRecord
import com.infinitum.aether.data.TemplateFile
import com.infinitum.aether.data.eideticMessage
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.net.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aether_core.EideticDataset
import uniffi.aether_core.EideticPurpose
import uniffi.aether_core.EideticRole
import uniffi.aether_core.Review
import uniffi.aether_core.ReviewOutcome
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val EIDETIC = "ÆTHER eidetic MIL™"
private const val REVIEW_LINE = "A trained reviewer must confirm before anyone acts on this result."
private const val NO_MODEL =
    "This build has no on-device face model. Templates come from an approved capture or enrollment station as a file: " +
        "JSON ([0.12, -0.03, ...] or {\"vector\": [...]}) or raw little-endian float32 (.f32), 64–2048 values."

private sealed interface EPage {
    data object Desk : EPage
    data object Enroll : EPage
    data object Search : EPage
    data class Result(val searchId: String) : EPage
    data object Reviews : EPage
    data class ReviewDetail(val searchId: String) : EPage
    data object Audit : EPage
}

/** A template read from a file. Not a data class, so its values never end up in a toString(). */
private class LoadedTemplate(val fileName: String, val values: List<Float>)

private fun roleLabel(r: EideticRole) = when (r) {
    EideticRole.OPERATOR -> "Operator: runs searches"
    EideticRole.REVIEWER -> "Reviewer: confirms or rejects results"
    EideticRole.AUDITOR -> "Auditor: reads the audit log"
    EideticRole.ENROLLMENT -> "Enrollment: enrolls people into rosters"
}

private fun purposeLabel(p: EideticPurpose) = when (p) {
    EideticPurpose.CHECKPOINT_VERIFICATION -> "Checkpoint verification"
    EideticPurpose.ACCESS_CONTROL -> "Base access control"
    EideticPurpose.PERSONNEL_RECOVERY -> "Personnel recovery"
    EideticPurpose.CASUALTY_IDENTIFICATION -> "Casualty identification"
}

private fun datasetLabel(d: EideticDataset) = when (d) {
    EideticDataset.OWN_FORCES -> "Own forces (consenting personnel)"
    EideticDataset.ACCESS_LIST -> "Site access list"
}

private fun outcomeLabel(o: ReviewOutcome) = when (o) {
    ReviewOutcome.PENDING -> "Pending review"
    ReviewOutcome.CONFIRMED -> "Confirmed"
    ReviewOutcome.REJECTED -> "Rejected"
    ReviewOutcome.INCONCLUSIVE -> "Inconclusive"
}

private fun eStamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

private fun eF2(x: Float) = "%.2f".format(Locale.US, x)

@Composable
internal fun EideticScreen(node: AetherNode, eidetic: EideticStore) {
    val displayName = node.profile.displayName
    LaunchedEffect(displayName) { eidetic.setOperator(displayName) }
    val loaded by eidetic.loaded.collectAsState()
    val loadError by eidetic.loadError.collectAsState()
    val inbox by node.eideticInbox.collectAsState()
    val contacts by node.contacts.collectAsState()
    LaunchedEffect(eidetic, inbox, contacts, displayName) {
        if (inbox.isNotEmpty()) eidetic.mergeInbox(inbox, contacts.mapValues { it.value.name }, displayName)
    }
    var page by remember { mutableStateOf<EPage>(EPage.Desk) }
    BackHandler(enabled = page != EPage.Desk) {
        page = when (page) {
            is EPage.ReviewDetail -> EPage.Reviews
            else -> EPage.Desk
        }
    }
    when {
        !loaded -> Centered { CircularProgressIndicator(color = Ink, strokeWidth = 1.5.dp) }
        loadError != null -> Centered { Text(loadError ?: "", color = Alert, modifier = Modifier.testTag("eidetic_load_error")) }
        else -> when (val p = page) {
            EPage.Desk -> DeskPage(eidetic) { page = it }
            EPage.Enroll -> EnrollPage(eidetic) { page = EPage.Desk }
            EPage.Search -> SearchPage(eidetic, onBack = { page = EPage.Desk }, onResult = { page = EPage.Result(it) })
            is EPage.Result -> ResultPage(node, eidetic, p.searchId) { page = EPage.Desk }
            EPage.Reviews -> ReviewsPage(eidetic, onBack = { page = EPage.Desk }, onOpen = { page = EPage.ReviewDetail(it) })
            is EPage.ReviewDetail -> ReviewDetailPage(node, eidetic, p.searchId) { page = EPage.Reviews }
            EPage.Audit -> AuditPage(eidetic) { page = EPage.Desk }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------

@Composable
private fun EPageFrame(tag: String, kicker: String, title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp).testTag(tag),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("eidetic_back")) {
                Text("← Desk", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Spacer(Modifier.height(20.dp))
        }
        Kicker(kicker)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink)
        content()
        Spacer(Modifier.height(40.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun EMessage(text: String?, tag: String = "eidetic_message", error: Boolean = true) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) Alert else Ink,
        modifier = Modifier.padding(vertical = 12.dp).testTag(tag),
    )
}

/** The line every result screen carries. */
@Composable
private fun ReviewNotice() {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).border(BorderStroke(1.dp, Ink)).padding(16.dp).testTag("eidetic_review_notice")) {
        Kicker("Human review required", color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(REVIEW_LINE, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

@Composable
private fun <T> EChoices(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    tag: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        for (o in options) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(o) }.padding(vertical = 14.dp).testTag(tag(o)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusMark(o == selected)
                Spacer(Modifier.width(14.dp))
                Text(label(o), style = MaterialTheme.typography.bodyMedium, color = if (o == selected) Ink else Body)
            }
            Hairline()
        }
    }
}

@Composable
private fun EField(value: String, onChange: (String) -> Unit, label: String, tag: String, max: Int = 128, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        singleLine = singleLine,
        shape = MaterialTheme.shapes.small,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(tag),
    )
}

/** Reads a template file chosen through the system picker. Values are never shown or logged. */
private fun readTemplate(context: Context, uri: Uri): LoadedTemplate {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    } ?: "template"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= TemplateFile.MAX_BYTES) { "The file is too large to be a template." }
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("The file could not be opened.")
    try {
        return LoadedTemplate(name, TemplateFile.parse(bytes, name))
    } finally {
        bytes.fill(0)
    }
}

@Composable
private fun TemplatePicker(tag: String, label: String, template: LoadedTemplate?, onLoaded: (LoadedTemplate?) -> Unit, onError: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val t = withContext(Dispatchers.IO) { readTemplate(context, uri) }
                    onLoaded(t)
                    onError(null)
                } catch (e: Exception) {
                    onLoaded(null)
                    onError(eideticMessage(e))
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Kicker(label)
        Spacer(Modifier.height(6.dp))
        Text(
            if (template == null) "No template file chosen." else "${template.fileName} · ${template.values.size} values loaded",
            style = MaterialTheme.typography.bodyMedium,
            color = if (template == null) Body else Ink,
            modifier = Modifier.testTag("${tag}_status"),
        )
        Row {
            TextButton(
                onClick = { launcher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag(tag),
            ) { Text(if (template == null) "Choose template file" else "Choose another file", color = Accent) }
            if (template != null) {
                Spacer(Modifier.width(20.dp))
                TextButton(onClick = { onLoaded(null) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("${tag}_clear")) {
                    Text("Clear", color = Accent)
                }
            }
        }
        Text(NO_MODEL, style = MaterialTheme.typography.bodySmall, color = Body)
    }
}

/** Online contacts as (convo, name), the same way ÆTHER Field™ picks teammates. */
@Composable
private fun eOnlineContacts(node: AetherNode): List<Pair<String, String>> {
    val states by node.linkStates.collectAsState()
    val contacts by node.contacts.collectAsState()
    return remember(states, contacts) {
        val live = node.onlineConvos().toSet() + states.filterValues { it == LinkState.ONLINE }.keys
        live.mapNotNull { c -> contacts[c]?.let { c to it.name } }.sortedBy { it.second }
    }
}

@Composable
private fun ESendToDialog(node: AetherNode, title: String, exclude: String, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val online = eOnlineContacts(node).filterNot { it.second.equals(exclude, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Hint("Send only to a trained reviewer. The request carries the search, scores, evidence and probe digest, never the probe or any template.")
                Spacer(Modifier.height(8.dp))
                if (online.isEmpty()) Hint("No teammate is online. Connect in Talk first.")
                for ((convo, name) in online) {
                    ListRow(tag = "eidetic_send_to_$name", online = true, title = name, subtitle = "Online", trailing = "Send ↗") { onPick(convo, name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("eidetic_send_to_close")) { Text("Close", color = Accent) }
        },
    )
}

// ---- Desk -----------------------------------------------------------------------------

@Composable
private fun DeskPage(eidetic: EideticStore, open: (EPage) -> Unit) {
    val role by eidetic.role.collectAsState()
    val operator by eidetic.operator.collectAsState()
    val roster by eidetic.rosterSummary.collectAsState()
    val searches by eidetic.searches.collectAsState()
    val reviews by eidetic.reviews.collectAsState()
    val audit by eidetic.audit.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val pending = reviews.values.count { it.outcome == ReviewOutcome.PENDING }
    val reviewable = reviews.values.count { it.outcome == ReviewOutcome.PENDING && !it.searcher.equals(operator, ignoreCase = true) }
    val ownSearches = searches.filter { it.from == null }

    EPageFrame("eidetic_desk", "Identity verification / Force protection", EIDETIC, onBack = null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Verifies people at checkpoints and access points, and identifies recovered personnel and casualties, " +
                "against enrolled rosters only. Every search is authorized, recorded in a hash-chained audit log and " +
                "reviewed by a trained reviewer before anyone acts on it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Body,
        )

        Section(1, "Role on this device")
        SpecRow("Operator", operator.ifEmpty { "-" })
        EChoices(
            options = enumValues<EideticRole>().toList(),
            selected = role,
            label = ::roleLabel,
            tag = { "eidetic_role_${it.name.lowercase()}" },
        ) { r ->
            scope.launch {
                message = try {
                    eidetic.setRole(r)
                    null
                } catch (e: Exception) {
                    eideticMessage(e)
                }
            }
        }
        Hint("Changing the role is recorded in the audit log.")
        EMessage(message)

        Section(2, "Status")
        SpecRow("Own forces enrolled", roster.count { it.dataset == EideticDataset.OWN_FORCES }.toString())
        SpecRow("Access list enrolled", roster.count { it.dataset == EideticDataset.ACCESS_LIST }.toString())
        SpecRow("Searches recorded", ownSearches.size.toString())
        SpecRow("Received for review", (searches.size - ownSearches.size).toString())
        SpecRow("Pending reviews", pending.toString())
        SpecRow("Audit chain", if (audit.verified) "Verified · ${audit.entries.size} entries" else "BROKEN")

        Section(3, "Work")
        Spacer(Modifier.height(12.dp))
        when (role) {
            null -> Hint("Choose this device's role to begin.")
            EideticRole.OPERATOR -> {
                PrimaryButton("Run a search", enabled = true, tag = "eidetic_open_search", modifier = Modifier.fillMaxWidth()) { open(EPage.Search) }
                if (ownSearches.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Kicker("Recent searches")
                    for (s in ownSearches.take(20)) {
                        val outcome = reviews[s.request.searchId]?.outcome
                        ListRow(
                            tag = "eidetic_search_${s.request.searchId}",
                            online = s.decision.allowed && s.result != null,
                            title = s.request.searchId,
                            subtitle = "${purposeLabel(s.request.purpose)} · ${eStamp(s.atMs)}",
                            trailing = when {
                                !s.decision.allowed -> "Denied"
                                s.result == null -> "Failed"
                                outcome != null -> outcomeLabel(outcome)
                                else -> "Recorded"
                            },
                            titleMono = true,
                        ) { open(EPage.Result(s.request.searchId)) }
                    }
                }
            }
            EideticRole.REVIEWER -> PrimaryButton("Review queue ($reviewable)", enabled = true, tag = "eidetic_open_reviews", modifier = Modifier.fillMaxWidth()) {
                open(EPage.Reviews)
            }
            EideticRole.ENROLLMENT -> PrimaryButton("Enroll and manage roster", enabled = true, tag = "eidetic_open_enroll", modifier = Modifier.fillMaxWidth()) {
                open(EPage.Enroll)
            }
            EideticRole.AUDITOR -> {
                SpecRow("Head hash", audit.headHex?.take(16) ?: "-")
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Open audit log", enabled = true, tag = "eidetic_open_audit_primary", modifier = Modifier.fillMaxWidth()) { open(EPage.Audit) }
            }
        }
        if (role != null && role != EideticRole.AUDITOR) {
            TextButton(onClick = { open(EPage.Audit) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 8.dp).testTag("eidetic_open_audit")) {
                Text("Audit log", color = Accent)
            }
        }
    }
}

// ---- Enroll ---------------------------------------------------------------------------

@Composable
private fun EnrollPage(eidetic: EideticStore, onBack: () -> Unit) {
    val role by eidetic.role.collectAsState()
    val roster by eidetic.rosterSummary.collectAsState()
    val scope = rememberCoroutineScope()
    var subjectId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dataset by remember { mutableStateOf(EideticDataset.OWN_FORCES) }
    var consent by remember { mutableStateOf("") }
    var template by remember { mutableStateOf<LoadedTemplate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

    EPageFrame("eidetic_enroll", "Enrollment", "Enroll a person", onBack) {
        if (role != EideticRole.ENROLLMENT) {
            Hint("Switch this device to the enrollment role on the desk to enroll people or change the roster.")
            return@EPageFrame
        }
        Hint("Enroll only people who consented or are covered by an enrollment order. The template is sealed on this device and never shown, sent or logged.")

        Section(1, "Person")
        EField(subjectId, { subjectId = it }, "Subject id (service or badge number)", "eidetic_enroll_id")
        EField(name, { name = it }, "Display name", "eidetic_enroll_name")
        EField(consent, { consent = it }, "Consent record or enrollment order reference", "eidetic_enroll_consent", max = 256)

        Section(2, "Roster")
        EChoices(enumValues<EideticDataset>().toList(), dataset, ::datasetLabel, { "eidetic_enroll_ds_${it.name.lowercase()}" }) { dataset = it }

        Section(3, "Template")
        TemplatePicker("eidetic_enroll_template", "Enrollment template", template, { template = it }, { message = it })

        Spacer(Modifier.height(20.dp))
        val ready = subjectId.isNotBlank() && name.isNotBlank() && consent.isNotBlank() && template != null && !busy
        PrimaryButton(if (busy) "Sealing…" else "Check and seal enrollment", enabled = ready, tag = "eidetic_enroll_submit", modifier = Modifier.fillMaxWidth()) {
            val t = template ?: return@PrimaryButton
            busy = true
            message = null
            notice = null
            scope.launch {
                try {
                    val e = eidetic.enroll(subjectId, name, dataset, consent, t.values)
                    notice = "Enrolled ${e.displayName} (${e.subjectId}) in ${datasetLabel(e.dataset)}."
                    subjectId = ""; name = ""; consent = ""; template = null
                } catch (e: Exception) {
                    message = eideticMessage(e)
                } finally {
                    busy = false
                }
            }
        }
        EMessage(notice, tag = "eidetic_enroll_notice", error = false)
        EMessage(message)

        Section(4, "Enrolled (${roster.size})")
        if (roster.isEmpty()) Hint("No one is enrolled on this device.")
        for (r in roster) {
            ListRow(
                tag = "eidetic_roster_${r.subjectId}",
                online = true,
                title = r.displayName,
                subtitle = "${r.subjectId} · ${datasetLabel(r.dataset)} · ${r.consentRef}",
                trailing = "Remove",
            ) { removing = r.subjectId }
        }
    }

    removing?.let { id ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $id?") },
            text = { Text("They will no longer be matched. The removal is recorded in the audit log; earlier searches and reviews stay on record.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        message = try {
                            eidetic.remove(id); null
                        } catch (e: Exception) {
                            eideticMessage(e)
                        }
                    }
                }, modifier = Modifier.testTag("eidetic_remove_confirm")) { Text("Remove", color = Alert) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }, modifier = Modifier.testTag("eidetic_remove_cancel")) { Text("Cancel", color = Accent) }
            },
        )
    }
}

// ---- Search ---------------------------------------------------------------------------

@Composable
private fun SearchPage(eidetic: EideticStore, onBack: () -> Unit, onResult: (String) -> Unit) {
    val role by eidetic.role.collectAsState()
    val scope = rememberCoroutineScope()
    var purpose by remember { mutableStateOf(EideticPurpose.CHECKPOINT_VERIFICATION) }
    var dataset by remember { mutableStateOf(EideticDataset.ACCESS_LIST) }
    var authority by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<LoadedTemplate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    EPageFrame("eidetic_search", "Search", "Verify a person", onBack) {
        if (role != EideticRole.OPERATOR) {
            Hint("Only an operator may run a search. Switch this device to the operator role on the desk.")
            return@EPageFrame
        }
        Hint("Searches run only against enrolled rosters, for the purposes below. Every search, allowed or denied, is recorded in the audit log.")

        Section(1, "Purpose")
        EChoices(enumValues<EideticPurpose>().toList(), purpose, ::purposeLabel, { "eidetic_purpose_${it.name.lowercase()}" }) { p ->
            purpose = p
            val allowed = EideticStore.datasetsFor(p)
            if (dataset !in allowed) dataset = allowed.first()
        }

        Section(2, "Roster")
        EChoices(EideticStore.datasetsFor(purpose), dataset, ::datasetLabel, { "eidetic_dataset_${it.name.lowercase()}" }) { dataset = it }

        Section(3, "Authority")
        EField(authority, { authority = it }, "Order or standing authority (e.g. FRAGO 12-3)", "eidetic_authority", max = 256)

        Section(4, "Probe")
        TemplatePicker("eidetic_probe", "Probe template", probe, { probe = it }, { message = it })

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            if (busy) "Searching…" else "Authorize and search",
            enabled = probe != null && !busy,
            tag = "eidetic_search_submit",
            modifier = Modifier.fillMaxWidth(),
        ) {
            val p = probe ?: return@PrimaryButton
            busy = true
            message = null
            scope.launch {
                try {
                    val rec = eidetic.search(purpose, dataset, authority, p.values)
                    probe = null
                    if (!rec.decision.allowed) {
                        message = "Denied: ${rec.decision.reason} The denial is recorded in the audit log."
                    } else {
                        onResult(rec.request.searchId)
                    }
                } catch (e: Exception) {
                    message = eideticMessage(e)
                } finally {
                    busy = false
                }
            }
        }
        EMessage(message, tag = "eidetic_search_message")
    }
}

// ---- Result ---------------------------------------------------------------------------

@Composable
private fun ResultBody(s: SearchRecord, review: Review?) {
    Section(1, "Search")
    SpecRow("Search id", s.request.searchId)
    SpecRow("Run by", s.request.searcher)
    s.from?.let { SpecRow("Sent for review by", s.fromName ?: it.takeLast(12)) }
    SpecRow("Time", eStamp(s.atMs))
    SpecRow("Purpose", purposeLabel(s.request.purpose))
    SpecRow("Roster", datasetLabel(s.request.dataset))
    SpecRow("Authority", s.request.legalAuthority.ifEmpty { "-" })
    SpecRow("Decision", if (s.decision.allowed) "Allowed" else "Denied")
    if (!s.decision.allowed) {
        EMessage(s.decision.reason, tag = "eidetic_denial")
        return
    }
    val r = s.result
    if (r == null) {
        EMessage(s.error ?: "The matcher did not run.", tag = "eidetic_match_error")
        return
    }
    ReviewNotice()

    Section(2, "Result")
    SpecRow("Confidence", eF2(r.confidence))
    SpecRow("Margin over next", eF2(r.margin))
    SpecRow("Probe digest", r.probeDigest)
    SpecRow("Review", review?.let { outcomeLabel(it.outcome) } ?: "-")
    Text(r.evidence, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.padding(vertical = 14.dp).testTag("eidetic_evidence"))

    Section(3, "Candidates (${r.candidates.size})")
    if (r.candidates.isEmpty()) Hint("No enrolled person scored above the reporting floor.")
    r.candidates.forEachIndexed { i, c ->
        SpecRow("${i + 1}. ${c.displayName} (${c.subjectId})", "score ${eF2(c.score)}")
    }
    review?.takeIf { it.outcome != ReviewOutcome.PENDING }?.let { d ->
        Section(4, "Decision")
        SpecRow("Outcome", outcomeLabel(d.outcome))
        SpecRow("Reviewer", d.reviewer ?: "-")
        SpecRow("Decided", d.decidedAtMs?.let(::eStamp) ?: "-")
        d.confirmedSubject?.let { SpecRow("Confirmed", it) }
        if (d.note.isNotEmpty()) Hint(d.note)
    }
}

@Composable
private fun ResultPage(node: AetherNode, eidetic: EideticStore, searchId: String, onBack: () -> Unit) {
    val searches by eidetic.searches.collectAsState()
    val reviews by eidetic.reviews.collectAsState()
    val sentTo by eidetic.sentTo.collectAsState()
    val s = searches.firstOrNull { it.request.searchId == searchId }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    EPageFrame("eidetic_result", "Search result", searchId, onBack) {
        if (s == null) {
            Hint("This search is not on record.")
        } else {
            ResultBody(s, reviews[searchId])
            if (reviews[searchId]?.outcome == ReviewOutcome.PENDING && s.result != null && s.from == null) {
                Hint("A review is open for this search. A reviewer other than you must decide it: send it to a reviewer's device.")
                val to = sentTo[searchId].orEmpty()
                if (to.isNotEmpty()) SpecRow("Sent for review to", to.joinToString(", ") { it.second })
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    if (busy) "Sending…" else "Send for review to…",
                    enabled = !busy,
                    tag = "eidetic_send_for_review",
                    modifier = Modifier.fillMaxWidth(),
                ) { picking = true }
                EMessage(notice, tag = "eidetic_send_notice", error = false)
                EMessage(message, tag = "eidetic_send_message")
            }
        }
    }
    if (picking && s != null) {
        ESendToDialog(node, "Send $searchId for review", exclude = s.request.searcher, onDismiss = { picking = false }) { convo, name ->
            picking = false
            busy = true
            message = null
            notice = null
            scope.launch {
                eidetic.sendForReview(searchId, convo, name, node::sendEidetic)
                    .onSuccess { notice = "Sent to $name for review. Their decision will appear here." }
                    .onFailure { message = "Not sent to $name: ${eideticMessage(it)}" }
                busy = false
            }
        }
    }
}

// ---- Reviews --------------------------------------------------------------------------

@Composable
private fun ReviewsPage(eidetic: EideticStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val role by eidetic.role.collectAsState()
    val operator by eidetic.operator.collectAsState()
    val reviews by eidetic.reviews.collectAsState()
    val searches by eidetic.searches.collectAsState()
    val byId = searches.associateBy { it.request.searchId }
    val pending = reviews.values.filter { it.outcome == ReviewOutcome.PENDING }
    val queue = pending.filterNot { it.searcher.equals(operator, ignoreCase = true) }.sortedByDescending { byId[it.searchId]?.atMs ?: 0L }
    val own = pending.size - queue.size
    val decided = reviews.values.filter { it.outcome != ReviewOutcome.PENDING }.sortedByDescending { it.decidedAtMs ?: 0L }

    EPageFrame("eidetic_reviews", "Review", "Review queue", onBack) {
        if (role != EideticRole.REVIEWER) {
            Hint("Only a reviewer may decide reviews. Switch this device to the reviewer role on the desk.")
            return@EPageFrame
        }
        Section(1, "Pending (${queue.size})")
        if (queue.isEmpty()) Hint("No reviews are waiting for you.")
        for (r in queue) {
            val s = byId[r.searchId]
            ListRow(
                tag = "eidetic_review_${r.searchId}",
                online = true,
                title = r.searchId,
                subtitle = listOfNotNull(
                    s?.let { purposeLabel(it.request.purpose) },
                    "run by ${r.searcher}",
                    s?.from?.let { f -> "from ${s.fromName ?: f.takeLast(12)}" },
                    s?.let { eStamp(it.atMs) },
                ).joinToString(" · "),
                trailing = s?.result?.let { "conf ${eF2(it.confidence)}" } ?: "Pending",
                titleMono = true,
            ) { onOpen(r.searchId) }
        }
        if (own > 0) {
            Hint("$own pending review(s) are of searches run under your name ($operator). Another reviewer must decide them.")
        }
        Section(2, "Decided (${decided.size})")
        if (decided.isEmpty()) Hint("No decisions yet.")
        for (r in decided.take(30)) {
            ListRow(
                tag = "eidetic_decided_${r.searchId}",
                online = false,
                title = r.searchId,
                subtitle = "${r.reviewer ?: "-"} · ${r.decidedAtMs?.let(::eStamp) ?: "-"}",
                trailing = outcomeLabel(r.outcome),
                titleMono = true,
            ) { onOpen(r.searchId) }
        }
    }
}

@Composable
private fun ReviewDetailPage(node: AetherNode, eidetic: EideticStore, searchId: String, onBack: () -> Unit) {
    val role by eidetic.role.collectAsState()
    val operator by eidetic.operator.collectAsState()
    val reviews by eidetic.reviews.collectAsState()
    val searches by eidetic.searches.collectAsState()
    val decisionSent by eidetic.decisionSent.collectAsState()
    var returning by remember { mutableStateOf(false) }
    var returnNote by remember { mutableStateOf<String?>(null) }
    val s = searches.firstOrNull { it.request.searchId == searchId }
    val review = reviews[searchId]
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<ReviewOutcome?>(null) }
    var subject by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    EPageFrame("eidetic_review_detail", "Review", searchId, onBack) {
        if (s == null || review == null) {
            Hint("This review is not on record.")
            return@EPageFrame
        }
        ResultBody(s, review)
        val from = s.from
        if (from != null && review.outcome != ReviewOutcome.PENDING) {
            val who = s.fromName ?: from.takeLast(12)
            if (searchId in decisionSent) {
                Hint("Decision sent back to $who.")
            } else {
                Hint("The decision has not reached $who yet.")
                TextButton(
                    onClick = {
                        returning = true
                        scope.launch {
                            returnNote = eidetic.returnDecision(searchId, node::sendEidetic).fold(
                                { "Decision sent back to $who." },
                                { "Not sent to $who: ${eideticMessage(it)}" },
                            )
                            returning = false
                        }
                    },
                    enabled = !returning,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("eidetic_return_decision"),
                ) { Text(if (returning) "Sending…" else "Send decision back to $who", color = Accent) }
            }
            EMessage(returnNote, tag = "eidetic_return_message", error = false)
        }
        val ownSearch = review.searcher.equals(operator, ignoreCase = true)
        val canDecide = role == EideticRole.REVIEWER && review.outcome == ReviewOutcome.PENDING && !ownSearch && s.result != null
        if (review.outcome == ReviewOutcome.PENDING && ownSearch) {
            Hint("You ran this search, so you cannot review it. Another reviewer must decide it.")
        }
        if (!canDecide) return@EPageFrame

        Section(5, "Decision")
        EChoices(
            listOf(ReviewOutcome.CONFIRMED, ReviewOutcome.REJECTED, ReviewOutcome.INCONCLUSIVE),
            outcome,
            ::outcomeLabel,
            { "eidetic_outcome_${it.name.lowercase()}" },
        ) { outcome = it }
        if (outcome == ReviewOutcome.CONFIRMED) {
            Spacer(Modifier.height(12.dp))
            Kicker("Person confirmed")
            val cands = s.result?.candidates.orEmpty()
            if (cands.isEmpty()) Hint("There are no reported candidates to confirm.")
            EChoices(cands.map { it.subjectId }, subject, { id -> cands.first { it.subjectId == id }.let { "${it.displayName} ($id) · ${eF2(it.score)}" } }, { "eidetic_confirm_$it" }) {
                subject = it
            }
        }
        EField(note, { note = it }, "Note (optional)", "eidetic_review_note", max = 1024, singleLine = false)
        Spacer(Modifier.height(20.dp))
        val ready = !busy && outcome != null && (outcome != ReviewOutcome.CONFIRMED || subject != null)
        PrimaryButton(if (busy) "Recording…" else "Record decision", enabled = ready, tag = "eidetic_review_submit", modifier = Modifier.fillMaxWidth()) {
            val o = outcome ?: return@PrimaryButton
            busy = true
            message = null
            scope.launch {
                try {
                    eidetic.decide(searchId, o, subject, note)
                    if (s.from != null) {
                        val who = s.fromName ?: s.from.takeLast(12)
                        returnNote = eidetic.returnDecision(searchId, node::sendEidetic).fold(
                            { "Decision recorded and sent back to $who." },
                            { "Decision recorded. Not yet sent to $who: ${eideticMessage(it)}" },
                        )
                    }
                } catch (e: Exception) {
                    message = eideticMessage(e)
                } finally {
                    busy = false
                }
            }
        }
        EMessage(message, tag = "eidetic_review_message")
    }
}

// ---- Audit ----------------------------------------------------------------------------

@Composable
private fun AuditPage(eidetic: EideticStore, onBack: () -> Unit) {
    val audit by eidetic.audit.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val shown = 200

    EPageFrame("eidetic_audit", "Audit", "Audit log", onBack) {
        Hint("Every role change, enrollment, removal, search, denial and review is recorded here. Each entry commits to the one before it, so editing, removing or reordering entries breaks verification.")
        Section(1, "Verification")
        SpecRow("Chain", if (audit.verified) "Verified" else "BROKEN")
        SpecRow("Head hash", audit.headHex?.take(16) ?: "-")
        SpecRow("Entries", audit.entries.size.toString())
        audit.problem?.let { EMessage(it, tag = "eidetic_audit_problem") }
        TextButton(
            onClick = {
                busy = true
                scope.launch {
                    try { eidetic.reverifyAudit() } catch (e: Exception) { } finally { busy = false }
                }
            },
            enabled = !busy,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.testTag("eidetic_audit_reverify"),
        ) { Text(if (busy) "Verifying…" else "Re-read and verify", color = Accent) }

        Section(2, "Entries, newest first")
        if (audit.entries.isEmpty()) Hint("Nothing recorded yet.")
        if (audit.entries.size > shown) Hint("Showing the latest $shown of ${audit.entries.size}.")
        for (e in audit.entries.asReversed().take(shown)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("eidetic_audit_${e.seq}")) {
                Kicker("#${e.seq} · ${eStamp(e.atMs)} · ${e.actor}")
                Spacer(Modifier.height(4.dp))
                Text(e.action, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = Ink)
                if (e.subject.isNotEmpty()) Text(e.subject, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Ink)
                if (e.detail.isNotEmpty()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Body)
            }
            Hairline()
        }
    }
}
