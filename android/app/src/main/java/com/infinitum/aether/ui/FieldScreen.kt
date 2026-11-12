package com.infinitum.aether.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.infinitum.aether.data.AuditStatus
import com.infinitum.aether.data.FieldReceived
import com.infinitum.aether.data.FieldStore
import com.infinitum.aether.data.fieldError
import com.infinitum.aether.data.toHex
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.net.LinkState
import kotlinx.coroutines.launch
import uniffi.aether_core.FieldMessage
import uniffi.aether_core.Mission
import uniffi.aether_core.MissionState
import uniffi.aether_core.Packet
import uniffi.aether_core.PlaceClass
import uniffi.aether_core.Precedence
import uniffi.aether_core.placeIsProtected
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FIELD_STALE_CLAIM_MS = 7L * 24 * 3600 * 1000

private sealed interface FieldView {
    data object Home : FieldView
    data object Capture : FieldView
    data object Packets : FieldView
    data class PacketDetail(val id: String) : FieldView
    data object Missions : FieldView
    data object PostMission : FieldView
    data class MissionDetail(val id: String) : FieldView
    data object Received : FieldView
    data object Audit : FieldView
}

private fun fieldPlaceName(p: PlaceClass) = p.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun fieldPrecedenceCode(p: Precedence) = when (p) {
    Precedence.ROUTINE -> "R"
    Precedence.PRIORITY -> "P"
    Precedence.IMMEDIATE -> "O"
    Precedence.FLASH -> "Z"
}

private fun fieldStamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

private fun fieldPosition(lat: Double?, lon: Double?) =
    if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else "No position"

/** Last known GPS fix, only if location permission was already granted. Never requests it. */
@SuppressLint("MissingPermission")
private fun fieldLastFix(context: Context): Pair<Double, Double>? {
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let { it.latitude to it.longitude }
}

@Composable
internal fun FieldScreen(node: AetherNode, field: FieldStore) {
    val me = node.profile.displayName
    val inbox by node.fieldInbox.collectAsState()
    val contacts by node.contacts.collectAsState()
    LaunchedEffect(field) { field.load() }
    LaunchedEffect(field, inbox, contacts) {
        if (inbox.isNotEmpty()) field.mergeInbox(inbox, contacts.mapValues { it.value.name }, me)
    }
    var view by remember { mutableStateOf<FieldView>(FieldView.Home) }
    BackHandler(enabled = view != FieldView.Home) {
        view = when (view) {
            is FieldView.PacketDetail -> FieldView.Packets
            is FieldView.MissionDetail, FieldView.PostMission -> FieldView.Missions
            else -> FieldView.Home
        }
    }
    val open: (FieldView) -> Unit = { view = it }
    when (val v = view) {
        FieldView.Home -> FieldHome(field, open)
        FieldView.Capture -> FieldCaptureView(node, field, open)
        FieldView.Packets -> FieldPacketsView(field, open)
        is FieldView.PacketDetail -> FieldPacketDetailView(node, field, v.id) { view = FieldView.Packets }
        FieldView.Missions -> FieldMissionsView(node, field, open)
        FieldView.PostMission -> FieldPostMissionView(node, field) { view = it }
        is FieldView.MissionDetail -> FieldMissionDetailView(node, field, v.id) { view = FieldView.Missions }
        FieldView.Received -> FieldReceivedView(field, open)
        FieldView.Audit -> FieldAuditView(field) { view = FieldView.Home }
    }
}

// ---- Shared pieces ----------------------------------------------------------------------

@Composable
private fun FieldSubBar(title: String, tag: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = GUTTER.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "← Back",
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
                modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp).testTag(tag),
            )
            Spacer(Modifier.weight(1f))
            Kicker(title)
        }
        Hairline()
    }
}

@Composable
private fun FieldSecondary(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(text, color = if (enabled) Accent else RuleStrong, style = MaterialTheme.typography.labelLarge)
    }

@Composable
private fun FieldStatus(text: String?) {
    if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = Ink, modifier = Modifier.padding(vertical = 10.dp).testTag("field_status"))
}

/** Online contacts as (convo, name). */
@Composable
private fun fieldOnlineContacts(node: AetherNode): List<Pair<String, String>> {
    val states by node.linkStates.collectAsState()
    val contacts by node.contacts.collectAsState()
    return remember(states, contacts) {
        val live = node.onlineConvos().toSet() + states.filterValues { it == LinkState.ONLINE }.keys
        live.mapNotNull { c -> contacts[c]?.let { c to it.name } }.sortedBy { it.second }
    }
}

@Composable
private fun FieldSendToDialog(node: AetherNode, title: String, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val online = fieldOnlineContacts(node)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (online.isEmpty()) Hint("No teammate is online. Connect in Talk first.")
                for ((convo, name) in online) {
                    ListRow(tag = "send_to_$name", online = true, title = name, subtitle = "Online", trailing = "Send ↗") { onPick(convo, name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { FieldSecondary("Close", "send_to_close", onClick = onDismiss) },
    )
}

/**
 * Sends a mission snapshot to the online teammates it concerns (poster, claimers before and
 * after the change, and the conversation it came from). Returns how many received it.
 */
private suspend fun fieldPropagate(node: AetherNode, field: FieldStore, me: String, before: Mission?, after: Mission, from: String?): Int {
    val names = setOfNotNull(after.postedBy, after.claimedBy, before?.claimedBy) - me
    val contacts = node.contacts.value
    val targets = node.onlineConvos().filter { c -> contacts[c]?.name?.let { it in names } == true || c == from }.toSet()
    var sent = 0
    for (c in targets) {
        if (node.sendField(c, FieldMessage(null, after)).isSuccess) {
            sent++
            field.recordMissionShared(me, after, contacts[c]?.name ?: c.takeLast(12))
        }
    }
    return sent
}

// ---- Home -------------------------------------------------------------------------------

@Composable
private fun FieldHome(field: FieldStore, open: (FieldView) -> Unit) {
    val captures by field.captures.collectAsState()
    val packets by field.packets.collectAsState()
    val missions by field.missions.collectAsState()
    val received by field.received.collectAsState()
    val audit by field.audit.collectAsState()
    val status by field.auditStatus.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("field_home"), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Kicker("Field reporting and tasking")
            Spacer(Modifier.height(10.dp))
            Text("ÆTHER Field™", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "Capture an observation, refine it into a sealed packet and share it with your unit. Missions task collection " +
                    "between teammates. Captures at protected places stay on this device unless you release them.",
                style = MaterialTheme.typography.bodyMedium,
                color = Body,
            )
        }
        item { Section(1, "Field") }
        item {
            val kept = captures.count { it.packetId == null }
            ListRow("field_open_capture", false, "Capture", "New observation" + if (kept > 0) " · $kept kept on this device" else "", "Open ↗") { open(FieldView.Capture) }
            ListRow("field_open_packets", false, "Packets", "${packets.size} packet(s)", "Open ↗") { open(FieldView.Packets) }
            val active = missions.values.count { it.state != MissionState.ACCEPTED && it.state != MissionState.CANCELLED }
            ListRow("field_open_missions", false, "Missions", "$active active · ${missions.size} total", "Open ↗") { open(FieldView.Missions) }
            ListRow("field_open_received", false, "Received", "${received.size} from teammates", "Open ↗") { open(FieldView.Received) }
            val chain = when (val s = status) {
                AuditStatus.Loading -> "Checking chain…"
                is AuditStatus.Verified -> "Verified · ${audit.size} entries"
                is AuditStatus.Broken -> "Broken: ${s.message}"
            }
            ListRow("field_open_audit", status is AuditStatus.Verified, "Audit", chain, "Open ↗") { open(FieldView.Audit) }
        }
    }
}

// ---- 1. Capture -------------------------------------------------------------------------

@Composable
private fun FieldCaptureView(node: AetherNode, field: FieldStore, open: (FieldView) -> Unit) {
    val me = node.profile.displayName
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captures by field.captures.collectAsState()
    var note by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf(PlaceClass.FIELD) }
    var fix by remember { mutableStateOf(fieldLastFix(context)) }
    var attach by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var releaseId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().imePadding()) {
        FieldSubBar("01 / Capture", "field_capture_back") { open(FieldView.Home) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(4000) },
                label = { Text("Observation") },
                minLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("capture_note"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(128) },
                label = { Text("Place label") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("capture_label"),
            )
            Section(2, "Place class")
            for (pc in PlaceClass.entries) {
                val protectedPlace = placeIsProtected(pc)
                Column(Modifier.fillMaxWidth().clickable { place = pc }.testTag("capture_place_${pc.name.lowercase()}")) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusMark(place == pc)
                        Spacer(Modifier.width(14.dp))
                        Text(fieldPlaceName(pc), style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        if (protectedPlace) Kicker("Stays on this device", color = Alert)
                    }
                    Hairline()
                }
            }
            Section(3, "Position")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val f = fix
                Text(
                    if (f == null) "No position" else fieldPosition(f.first, f.second),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    color = if (f == null) Body else Ink,
                    modifier = Modifier.weight(1f).testTag("capture_position"),
                )
                FieldSecondary("Refresh", "capture_position_refresh") { fix = fieldLastFix(context) }
            }
            if (fix != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = attach,
                    onCheckedChange = { attach = it },
                    colors = CheckboxDefaults.colors(checkedColor = Ink, uncheckedColor = RuleStrong),
                    modifier = Modifier.testTag("capture_attach_position"),
                )
                Text("Attach this position", style = MaterialTheme.typography.bodyMedium, color = Ink)
            } else {
                Text("Uses the last GPS fix when location access is already allowed.", style = MaterialTheme.typography.bodySmall, color = Body)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (busy) "Saving…" else "Save and refine",
                enabled = note.isNotBlank() && !busy,
                tag = "capture_save",
                modifier = Modifier.fillMaxWidth(),
            ) {
                busy = true
                val f = fix?.takeIf { attach }
                scope.launch {
                    field.capture(me, note, label, place, f?.first, f?.second)
                        .onSuccess { fc ->
                            note = ""; label = ""
                            if (fc.packetId != null) {
                                status = fc.reason + " Packet ${fc.packetId}."
                            } else {
                                status = fc.reason
                                releaseId = fc.capture.id
                            }
                        }
                        .onFailure { status = "Not saved: " + fieldError(it) }
                    busy = false
                }
            }
            FieldStatus(status)

            val kept = captures.filter { it.packetId == null }
            if (kept.isNotEmpty()) {
                Section(4, "Kept on this device")
                for (fc in kept) {
                    ListRow(
                        tag = "kept_${fc.capture.id}",
                        online = false,
                        title = fc.capture.placeLabel.ifBlank { fieldPlaceName(fc.capture.placeClass) },
                        subtitle = fieldPlaceName(fc.capture.placeClass) + " · " + fieldStamp(fc.capture.atMs),
                        trailing = "Release…",
                    ) { releaseId = fc.capture.id }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    val rid = releaseId
    val target = captures.firstOrNull { it.capture.id == rid && it.packetId == null }
    if (target != null) AlertDialog(
        onDismissRequest = { releaseId = null },
        containerColor = Paper,
        title = { Text("Release this capture?", style = MaterialTheme.typography.titleMedium, color = Ink) },
        text = {
            Text(
                "${target.reason}\n\nReleasing refines it into a packet that you can send to teammates, so it can leave this " +
                    "device. ${fieldPlaceName(target.capture.placeClass)} is a protected place. Release only if the report is needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Body,
            )
        },
        confirmButton = {
            FieldSecondary("Release this capture", "capture_release_confirm") {
                releaseId = null
                scope.launch {
                    field.release(target.capture.id, me)
                        .onSuccess { status = "Released. Packet ${it.packetId}." }
                        .onFailure { status = "Not released: " + fieldError(it) }
                }
            }
        },
        dismissButton = { FieldSecondary("Keep on device", "capture_release_cancel") { releaseId = null } },
    )
}

// ---- 2. Packets -------------------------------------------------------------------------

@Composable
private fun FieldPacketsView(field: FieldStore, open: (FieldView) -> Unit) {
    val packets by field.packets.collectAsState()
    Column(Modifier.fillMaxSize()) {
        FieldSubBar("02 / Packets", "field_packets_back") { open(FieldView.Home) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp)) {
            if (packets.isEmpty()) item { Hint("No packets yet. Save a capture to make one.") }
            items(packets, key = { it.id }) { p -> FieldPacketRow(p, "packet_${p.id}") { open(FieldView.PacketDetail(p.id)) } }
        }
    }
}

@Composable
private fun FieldPacketRow(p: Packet, tag: String, sender: String? = null, onClick: () -> Unit) = ListRow(
    tag = tag,
    online = false,
    title = p.placeLabel.ifBlank { fieldPlaceName(p.placeClass) },
    subtitle = (sender?.let { "from $it · " } ?: "") +
        "Q${p.quality} · ${p.redactions} removed · ${p.proof.take(12)}",
    trailing = fieldPlaceName(p.placeClass),
    onClick = onClick,
)

@Composable
private fun FieldPacketDetailView(node: AetherNode, field: FieldStore, id: String, onBack: () -> Unit) {
    val me = node.profile.displayName
    val packets by field.packets.collectAsState()
    val received by field.received.collectAsState()
    val own = packets.firstOrNull { it.id == id }
    val p = own ?: received.firstNotNullOfOrNull { r -> r.packet?.takeIf { it.id == id } }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    if (p == null) {
        Column(Modifier.fillMaxSize()) {
            FieldSubBar("Packet", "field_packet_back", onBack)
            Column(Modifier.padding(horizontal = GUTTER.dp)) { Hint("Packet not found.") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        FieldSubBar("Packet", "field_packet_back", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(16.dp))
            Text(p.placeLabel.ifBlank { fieldPlaceName(p.placeClass) }, style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text(p.observation, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.testTag("packet_observation"))
            Spacer(Modifier.height(12.dp))
            Hairline()
            SpecRow("Packet", p.id)
            SpecRow("Owner", p.owner)
            SpecRow("Place class", fieldPlaceName(p.placeClass))
            SpecRow("Position", fieldPosition(p.lat, p.lon))
            SpecRow("Captured", fieldStamp(p.atMs))
            SpecRow("Quality", "${p.quality} / 100")
            SpecRow("Contact details removed", p.redactions.toString())
            SpecRow("Proof", p.proof.take(16) + "…")
            if (own != null) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Send to…", true, "packet_send", Modifier.fillMaxWidth()) { picking = true }
            }
            FieldStatus(status)
            Spacer(Modifier.height(40.dp))
        }
    }
    if (picking) FieldSendToDialog(node, "Send packet to", onDismiss = { picking = false }) { convo, name ->
        picking = false
        scope.launch {
            node.sendField(convo, FieldMessage(p, null))
                .onSuccess { field.recordPacketShared(me, p, name); status = "Sent to $name." }
                .onFailure { status = "Not sent: " + fieldError(it) }
        }
    }
}

// ---- 3. Missions ------------------------------------------------------------------------

@Composable
private fun FieldMissionsView(node: AetherNode, field: FieldStore, open: (FieldView) -> Unit) {
    val me = node.profile.displayName
    val missions by field.missions.collectAsState()
    val all = missions.values.sortedByDescending { it.postedAtMs }
    val done = all.filter { it.state == MissionState.ACCEPTED || it.state == MissionState.CANCELLED }
    val live = all - done.toSet()
    val mine = live.filter { it.postedBy == me }
    val claimed = live.filter { it.claimedBy == me && it.postedBy != me }
    val openOnes = live.filter { it.state == MissionState.OPEN && it.postedBy != me }
    val others = live - mine.toSet() - claimed.toSet() - openOnes.toSet()
    Column(Modifier.fillMaxSize()) {
        FieldSubBar("03 / Missions", "field_missions_back") { open(FieldView.Home) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Post a mission", true, "mission_new", Modifier.fillMaxWidth()) { open(FieldView.PostMission) }
            }
            val groups = listOf("Open" to openOnes, "Mine" to mine, "Claimed by me" to claimed, "In progress (others)" to others, "Done" to done)
            var n = 0
            for ((title, list) in groups) {
                if (title.startsWith("In progress") && list.isEmpty()) continue
                n++
                val num = n
                item { Section(num, title) }
                if (list.isEmpty()) item { Hint("None.") }
                items(list, key = { "${title}_${it.id}" }) { m -> FieldMissionRow(m) { open(FieldView.MissionDetail(m.id)) } }
            }
        }
    }
}

@Composable
private fun FieldMissionRow(m: Mission, sender: String? = null, onClick: () -> Unit) = ListRow(
    tag = "mission_${m.id}",
    online = m.state == MissionState.OPEN,
    title = "[${fieldPrecedenceCode(m.precedence)}] ${m.title}",
    subtitle = (sender?.let { "from $it · " } ?: "") + fieldPlaceName(m.placeClass) + " · by " + m.postedBy +
        (m.claimedBy?.let { " · claimed by $it" } ?: ""),
    trailing = m.state.name.lowercase(),
    onClick = onClick,
)

@Composable
private fun FieldPostMissionView(node: AetherNode, field: FieldStore, go: (FieldView) -> Unit) {
    val me = node.profile.displayName
    val scope = rememberCoroutineScope()
    val online = fieldOnlineContacts(node)
    var title by rememberSaveable { mutableStateOf("") }
    var brief by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf(PlaceClass.FIELD) }
    var precedence by rememberSaveable { mutableStateOf(Precedence.ROUTINE) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        FieldSubBar("Post a mission", "mission_post_back") { go(FieldView.Missions) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(128) }, label = { Text("Title") }, singleLine = true,
                shape = MaterialTheme.shapes.small, colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("mission_title"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = brief, onValueChange = { brief = it.take(4000) }, label = { Text("Brief") }, minLines = 3,
                shape = MaterialTheme.shapes.small, colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("mission_brief"),
            )
            Section(1, "Precedence")
            Row {
                for (p in Precedence.entries) {
                    TextButton(onClick = { precedence = p }, modifier = Modifier.testTag("mission_precedence_${fieldPrecedenceCode(p)}")) {
                        Text(
                            fieldPrecedenceCode(p) + " · " + p.name.lowercase(),
                            color = if (precedence == p) Ink else Accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Section(2, "Place class")
            Text("Protected and private places cannot be tasked.", style = MaterialTheme.typography.bodySmall, color = Body)
            for (pc in PlaceClass.entries.filterNot { placeIsProtected(it) }) {
                Column(Modifier.fillMaxWidth().clickable { place = pc }.testTag("mission_place_${pc.name.lowercase()}")) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusMark(place == pc)
                        Spacer(Modifier.width(14.dp))
                        Text(fieldPlaceName(pc), style = MaterialTheme.typography.bodyMedium, color = Ink)
                    }
                    Hairline()
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (online.isEmpty()) "No teammate is online. You can send it later from the mission."
                else "It will be sent to ${online.size} online teammate(s).",
                style = MaterialTheme.typography.bodySmall,
                color = Body,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (busy) "Posting…" else "Post mission", title.isNotBlank() && !busy, "mission_post", Modifier.fillMaxWidth()) {
                busy = true
                scope.launch {
                    field.postMission(me, title, brief, place, precedence)
                        .onSuccess { m ->
                            var sent = 0
                            for ((convo, name) in online) {
                                if (node.sendField(convo, FieldMessage(null, m)).isSuccess) {
                                    sent++
                                    field.recordMissionShared(me, m, name)
                                }
                            }
                            busy = false
                            go(FieldView.MissionDetail(m.id))
                        }
                        .onFailure { status = "Not posted: " + fieldError(it); busy = false }
                }
            }
            FieldStatus(status)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FieldMissionDetailView(node: AetherNode, field: FieldStore, id: String, onBack: () -> Unit) {
    val me = node.profile.displayName
    val missions by field.missions.collectAsState()
    val from by field.missionFrom.collectAsState()
    val packets by field.packets.collectAsState()
    val received by field.received.collectAsState()
    val m = missions[id]
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (m == null) {
        Column(Modifier.fillMaxSize()) {
            FieldSubBar("Mission", "field_mission_back", onBack)
            Column(Modifier.padding(horizontal = GUTTER.dp)) { Hint("Mission not found.") }
        }
        return
    }
    var picking by remember { mutableStateOf(false) }
    var sendOpen by remember { mutableStateOf(false) }

    fun act(label: String, op: suspend () -> Result<Mission>) {
        val before = m
        busy = true
        scope.launch {
            op().onSuccess { next ->
                val sent = fieldPropagate(node, field, me, before, next, from[next.id])
                status = "$label. " + if (sent > 0) "Sent to $sent teammate(s)." else "No teammate concerned is online; use Send to… later."
            }.onFailure { status = "$label failed: " + fieldError(it) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        FieldSubBar("Mission", "field_mission_back", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(16.dp))
            Kicker("Precedence ${fieldPrecedenceCode(m.precedence)} · ${m.state.name.lowercase()}", color = if (m.precedence == Precedence.FLASH) Alert else Body)
            Spacer(Modifier.height(6.dp))
            Text(m.title, style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.testTag("mission_detail_title"))
            if (m.brief.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(m.brief, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            Spacer(Modifier.height(12.dp))
            Hairline()
            SpecRow("Mission", m.id)
            SpecRow("Place class", fieldPlaceName(m.placeClass))
            SpecRow("Posted by", m.postedBy)
            SpecRow("Posted", fieldStamp(m.postedAtMs))
            SpecRow("State", m.state.name.lowercase())
            SpecRow("Claimed by", m.claimedBy ?: "—")
            m.claimedAtMs?.let { SpecRow("Claimed", fieldStamp(it)) }
            SpecRow("Packet", m.packetId ?: "—")
            SpecRow("Turned down", m.rejectedPackets.size.toString())

            val submitted = m.packetId?.let { pid ->
                packets.firstOrNull { it.id == pid } ?: received.firstNotNullOfOrNull { r -> r.packet?.takeIf { it.id == pid } }
            }
            if (submitted != null) {
                Section(1, "Submitted packet")
                Text(submitted.observation, style = MaterialTheme.typography.bodyMedium, color = Ink)
                SpecRow("Quality", "${submitted.quality} / 100")
                SpecRow("Position", fieldPosition(submitted.lat, submitted.lon))
            } else if (m.packetId != null) {
                Hint("The submitted packet has not reached this device. Ask the claimer to send it.")
            }

            Section(2, "Actions")
            val now = System.currentTimeMillis()
            val isPoster = m.postedBy == me
            val isClaimer = m.claimedBy == me
            val canClaim = m.state == MissionState.OPEN && !isPoster
            val canSubmit = m.state == MissionState.CLAIMED && isClaimer
            val canDecide = m.state == MissionState.SUBMITTED && isPoster
            val canRelease = m.state == MissionState.CLAIMED && isClaimer
            val stale = m.state == MissionState.CLAIMED && (m.claimedAtMs?.let { now - it > FIELD_STALE_CLAIM_MS } == true)
            val canCancel = isPoster && (m.state == MissionState.OPEN || stale)
            if (canClaim) PrimaryButton("Claim", !busy, "mission_claim", Modifier.fillMaxWidth()) { act("Claimed") { field.claim(m.id, me) } }
            if (canSubmit) PrimaryButton("Submit a packet", !busy, "mission_submit", Modifier.fillMaxWidth()) { picking = true }
            if (canDecide) {
                PrimaryButton("Accept", !busy, "mission_accept", Modifier.fillMaxWidth()) { act("Accepted") { field.accept(m.id, me) } }
                FieldSecondary("Reject", "mission_reject", !busy) { act("Rejected") { field.reject(m.id, me) } }
            }
            if (canRelease) FieldSecondary("Release this mission", "mission_release", !busy) { act("Released") { field.releaseMission(m.id, me) } }
            if (canCancel) FieldSecondary("Cancel mission", "mission_cancel", !busy) { act("Cancelled") { field.cancel(m.id, me) } }
            if (!(canClaim || canSubmit || canDecide || canRelease || canCancel)) Hint("No action available to you in this state.")
            FieldSecondary("Send to…", "mission_send", !busy) { picking = false; status = null; sendOpen = true }
            FieldStatus(status)
            Spacer(Modifier.height(40.dp))
        }
    }

    if (picking) {
        val eligible = packets.filter { it.owner == me && it.placeClass == m.placeClass && it.id !in m.rejectedPackets }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Paper,
            title = { Text("Submit a packet", style = MaterialTheme.typography.titleMedium, color = Ink) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (eligible.isEmpty()) Hint("No packet of yours matches ${fieldPlaceName(m.placeClass)}. Capture one first.")
                    for (p in eligible) FieldPacketRow(p, "submit_${p.id}") {
                        picking = false
                        act("Submitted") { field.submit(m.id, me, p.id) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { FieldSecondary("Close", "submit_close") { picking = false } },
        )
    }
    if (sendOpen) FieldSendToDialog(node, "Send mission to", onDismiss = { sendOpen = false }) { convo, name ->
        sendOpen = false
        scope.launch {
            node.sendField(convo, FieldMessage(null, m))
                .onSuccess { field.recordMissionShared(me, m, name); status = "Sent to $name." }
                .onFailure { status = "Not sent: " + fieldError(it) }
        }
    }
}

// ---- 4. Received ------------------------------------------------------------------------

@Composable
private fun FieldReceivedView(field: FieldStore, open: (FieldView) -> Unit) {
    val received by field.received.collectAsState()
    Column(Modifier.fillMaxSize()) {
        FieldSubBar("04 / Received", "field_received_back") { open(FieldView.Home) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp)) {
            if (received.isEmpty()) item { Hint("Nothing received yet. Teammates' packets and mission updates appear here.") }
            items(received, key = { "${it.atMs}_${it.convo}_${it.packet?.id ?: it.mission?.id}_${it.packet?.proof?.take(8) ?: it.mission?.state}" }) { r ->
                FieldReceivedRow(r, open)
            }
        }
    }
}

@Composable
private fun FieldReceivedRow(r: FieldReceived, open: (FieldView) -> Unit) {
    val p = r.packet
    val m = r.mission
    when {
        p != null -> ListRow(
            tag = "received_packet_${p.id}",
            online = r.applied,
            title = p.placeLabel.ifBlank { fieldPlaceName(p.placeClass) },
            subtitle = "from ${r.sender} · ${fieldStamp(r.atMs)} · ${r.note}",
            trailing = if (r.applied) "Q${p.quality}" else "refused",
        ) { if (r.applied) open(FieldView.PacketDetail(p.id)) }
        m != null -> ListRow(
            tag = "received_mission_${m.id}",
            online = r.applied,
            title = "[${fieldPrecedenceCode(m.precedence)}] ${m.title}",
            subtitle = "from ${r.sender} · ${m.state.name.lowercase()} · ${r.note}",
            trailing = if (r.applied) "applied" else "not applied",
        ) { open(FieldView.MissionDetail(m.id)) }
        else -> ListRow("received_empty", false, "Unreadable message", "from ${r.sender} · ${r.note}", "") {}
    }
}

// ---- 5. Audit ---------------------------------------------------------------------------

@Composable
private fun FieldAuditView(field: FieldStore, onBack: () -> Unit) {
    val audit by field.audit.collectAsState()
    val status by field.auditStatus.collectAsState()
    val newest = remember(audit) { audit.asReversed().toList() }
    Column(Modifier.fillMaxSize()) {
        FieldSubBar("05 / Audit", "field_audit_back", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, if (status is AuditStatus.Broken) Alert else Rule)).padding(18.dp).testTag("audit_status")) {
                    Kicker("Chain status")
                    Spacer(Modifier.height(6.dp))
                    when (val s = status) {
                        AuditStatus.Loading -> Text("Checking…", style = MaterialTheme.typography.bodyMedium, color = Body)
                        is AuditStatus.Verified -> {
                            Text("Verified · ${s.count} entries", style = MaterialTheme.typography.titleMedium, color = Ink)
                            Spacer(Modifier.height(4.dp))
                            Text("Head ${s.head.take(16)}…", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Body)
                        }
                        is AuditStatus.Broken -> {
                            Text("Broken", style = MaterialTheme.typography.titleMedium, color = Alert)
                            Spacer(Modifier.height(4.dp))
                            Text(s.message, style = MaterialTheme.typography.bodySmall, color = Alert)
                        }
                    }
                }
            }
            item { Section(1, "Entries · newest first") }
            if (newest.isEmpty()) item { Hint("No entries yet.") }
            items(newest, key = { it.seq.toString() }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("audit_${e.seq}")) {
                    Kicker("#${e.seq} · ${fieldStamp(e.atMs)} · ${e.actor}")
                    Spacer(Modifier.height(4.dp))
                    Text(e.action, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = Ink)
                    if (e.subject.isNotBlank()) Text(e.subject, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Body)
                    if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Body)
                    Text("hash ${e.hash.toHex().take(12)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = RuleStrong)
                }
                Hairline()
            }
        }
    }
}
