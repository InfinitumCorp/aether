package com.infinitum.aether.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infinitum.aether.data.ChatSettings
import com.infinitum.aether.data.StoredMessage
import com.infinitum.aether.data.convo
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.net.LinkState
import kotlinx.coroutines.launch
import uniffi.aether_core.Precedence
import uniffi.aether_core.decodeKeyDrop

/** One row of the Talk list: a contact conversation or a group. */
internal data class TalkEntry(
    val convo: String,
    val title: String,
    val online: Boolean,
    val isGroup: Boolean,
    val last: StoredMessage?,
    val unread: Int,
    val settings: ChatSettings,
    val tag: String,
    /** Highest precedence among unread messages (Routine when none are urgent). */
    val urgency: Precedence = Precedence.ROUTINE,
    val channel: Boolean = false,
)

/** Readable one-line preview of a message for lists and reply quotes. */
internal fun previewOf(node: AetherNode, m: StoredMessage): String = when {
    m.deleted -> "Message deleted"
    m.drop -> "Key drop · " + (runCatching { decodeKeyDrop(m.body).codename }.getOrNull() ?: "")
    m.kind == StoredMessage.KIND_SYSTEM -> m.body
    m.body.isEmpty() && m.fileId != null -> "Attachment"
    else -> chatBodyText(m)
}.replace('\n', ' ')

/** Every conversation (contacts and groups) with its last message, unread count and settings. */
@Composable
internal fun talkEntries(node: AetherNode): List<TalkEntry> {
    val contacts by node.contacts.collectAsState()
    val states by node.linkStates.collectAsState()
    val messages by node.messages.collectAsState()
    val settings by node.chats.settings.collectAsState()
    val groups by node.chats.groups.collectAsState()
    return remember(contacts, states, messages, settings, groups) {
        val now = System.currentTimeMillis()
        val byConvo = messages.filter { !it.expired(now) }.groupBy { it.convo }
        fun unreadOf(convo: String, s: ChatSettings) = byConvo[convo].orEmpty().filter { !it.outgoing && it.kind == null && !it.drop && it.sentAtMs > s.lastReadMs }
        fun unread(convo: String, s: ChatSettings) = unreadOf(convo, s).size
        fun urgency(convo: String, s: ChatSettings) = unreadOf(convo, s).maxOfOrNull { it.precedence } ?: Precedence.ROUTINE
        val people = contacts.values.map { c ->
            val via = c.via?.let { "via " + AetherNode.formatNumber(node.numberOf(it)) + " · " } ?: ""
            val s = settings[c.convo] ?: ChatSettings(c.convo)
            TalkEntry(
                c.convo, via + c.name + (c.codename?.let { "  · $it" } ?: "") + if (c.verified) "  ✓" else "",
                states[c.convo] == LinkState.ONLINE, false, byConvo[c.convo]?.lastOrNull(), unread(c.convo, s), s, "contact_${c.name}",
                urgency(c.convo, s),
            )
        }
        val gs = groups.values.map { g ->
            val s = settings[g.convo] ?: ChatSettings(g.convo)
            TalkEntry(g.convo, g.name, !g.left, true, byConvo[g.convo]?.lastOrNull(), unread(g.convo, s), s, "group_${g.name}", urgency(g.convo, s), g.channel)
        }
        // Unread Flash, then Immediate traffic first; then pinned chats; then the newest.
        (people + gs).sortedWith(
            compareByDescending<TalkEntry> { if (it.urgency >= Precedence.IMMEDIATE) it.urgency.ordinal else 0 }
                .thenByDescending { it.settings.pinned }.thenByDescending { it.last?.sentAtMs ?: 0L },
        )
    }
}

/** The Talk conversation list: search, new group, pinned first, archived in their own row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TalkConversations(node: AetherNode, onOpenChat: (String) -> Unit) {
    val entries = talkEntries(node)
    val messages by node.messages.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<TalkEntry?>(null) }
    var newGroup by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                placeholder = { Text("Search chats and messages", color = Body) },
                shape = MaterialTheme.shapes.small, colors = fieldColors(),
                modifier = Modifier.weight(1f).testTag("talk_search"),
            )
            Spacer(Modifier.size(16.dp))
            Text(
                "New group +", style = MaterialTheme.typography.labelLarge, color = Accent,
                modifier = Modifier.clickable { newGroup = true }.padding(vertical = 8.dp).testTag("talk_new_group"),
            )
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            val titles = entries.filter { it.title.contains(q, ignoreCase = true) }
            val names = entries.associateBy { it.convo }
            val hits = messages.filter {
                it.kind == null && !it.drop && !it.deleted && !it.expired() && it.body.contains(q, ignoreCase = true) && it.convo in names
            }.takeLast(50).reversed()
            Kicker("Chats", modifier = Modifier.padding(top = 16.dp))
            if (titles.isEmpty()) Hint("No chats match.")
            titles.forEach { e -> TalkRow(node, e, onClick = { onOpenChat(e.convo) }, onLongClick = { options = e }) }
            Kicker("Messages", modifier = Modifier.padding(top = 16.dp))
            if (hits.isEmpty()) Hint("No messages match.")
            hits.forEachIndexed { i, m ->
                Column(Modifier.fillMaxWidth().clickable { onOpenChat(m.convo) }.testTag("talk_hit_$i")) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Kicker(names[m.convo]?.title ?: "", color = Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(m.body.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Hairline()
                }
            }
            return@Column
        }
        val active = entries.filter { !it.settings.archived }
        val archived = entries.filter { it.settings.archived }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) Hint("Connect to a nearby node, or enter an address with Connect +.")
        active.forEach { e -> TalkRow(node, e, onClick = { onOpenChat(e.convo) }, onLongClick = { options = e }) }
        if (archived.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clickable { showArchived = !showArchived }.testTag("talk_archived")) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Archived · ${archived.size}", color = Ink, modifier = Modifier.weight(1f))
                    val n = archived.filter { !it.settings.muted }.sumOf { it.unread }
                    if (n > 0) Kicker("$n unread", color = Accent)
                    Spacer(Modifier.size(12.dp))
                    Kicker(if (showArchived) "Hide" else "Show", color = Accent)
                }
                Hairline()
            }
            if (showArchived) archived.forEach { e -> TalkRow(node, e, onClick = { onOpenChat(e.convo) }, onLongClick = { options = e }) }
        }
        Spacer(Modifier.height(6.dp))
        Kicker("Long-press a chat to pin, mute or archive it")
    }

    options?.let { e -> ChatOptionsDialog(node, e) { options = null } }
    if (newGroup) NewGroupDialog(node, onDismiss = { newGroup = false }, onCreated = { newGroup = false; onOpenChat(it) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TalkRow(node: AetherNode, e: TalkEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val last = e.last
    val preview = when {
        last == null -> if (e.isGroup) "No messages yet" else "No messages yet · tap to open"
        e.isGroup && last.kind == null && !last.drop -> node.nameFor(node.groupOf(e.convo)?.via, last.peerKeyHex).let { if (last.outgoing) "You" else it } + ": " + previewOf(node, last)
        else -> previewOf(node, last)
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag(e.tag)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusMark(e.online)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (e.isGroup) { Spacer(Modifier.size(8.dp)); Kicker(if (e.channel) "Channel" else "Group") }
                    if (e.urgency >= Precedence.IMMEDIATE) {
                        Spacer(Modifier.size(8.dp))
                        Kicker(e.urgency.name, color = precedenceColor(e.urgency), modifier = Modifier.testTag("${e.tag}_urgent"))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(preview, style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                val flags = listOfNotNull("Pinned".takeIf { e.settings.pinned }, "Muted".takeIf { e.settings.muted })
                if (flags.isNotEmpty()) Kicker(flags.joinToString(" · "), modifier = Modifier.testTag("${e.tag}_flags"))
                if (e.unread > 0) {
                    Spacer(Modifier.height(4.dp))
                    // Muted chats keep a count but lose the accent marker.
                    Box(
                        Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp)
                            .then(
                                when {
                                    e.urgency >= Precedence.IMMEDIATE -> Modifier.background(precedenceColor(e.urgency))
                                    e.settings.muted -> Modifier.border(1.dp, RuleStrong)
                                    else -> Modifier.background(Accent)
                                },
                            )
                            .padding(horizontal = 6.dp).testTag("${e.tag}_unread"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (e.unread > 99) "99+" else e.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (e.settings.muted && e.urgency < Precedence.IMMEDIATE) Body else Paper,
                        )
                    }
                } else if (flags.isEmpty()) {
                    Kicker(if (e.isGroup) "" else if (e.online) "online" else "offline", color = if (e.online) Ink else Body)
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun ChatOptionsDialog(node: AetherNode, e: TalkEntry, onDismiss: () -> Unit) {
    val s = e.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text(e.title, style = MaterialTheme.typography.titleLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                listOf(
                    Triple(if (s.pinned) "Unpin" else "Pin to top", "chat_opt_pin") { node.chats.update(e.convo) { it.copy(pinned = !it.pinned) } },
                    Triple(if (s.muted) "Unmute" else "Mute", "chat_opt_mute") { node.chats.update(e.convo) { it.copy(muted = !it.muted) } },
                    Triple(if (s.archived) "Unarchive" else "Archive", "chat_opt_archive") { node.chats.update(e.convo) { it.copy(archived = !it.archived) } },
                ).forEach { (label, tag, action) ->
                    Column(Modifier.fillMaxWidth().clickable { action(); onDismiss() }.testTag(tag)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = Accent, modifier = Modifier.padding(vertical = 14.dp))
                        Hairline()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Body) } },
    )
}

/** "New group": a name and contacts of the real identity; this device becomes the group's admin. */
@Composable
internal fun NewGroupDialog(node: AetherNode, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val contacts by node.contacts.collectAsState()
    val pickable = remember(contacts) { contacts.values.filter { it.via == null }.sortedBy { it.name.lowercase() } }
    var name by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var channel by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = name.isNotBlank() && picked.isNotEmpty() && picked.size < 64 && !busy
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text(if (channel) "New unit channel" else "New group", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.replace("\n", "").take(64); error = null }, singleLine = true,
                    label = { Text("Group name") }, shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("group_name"),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { channel = !channel }.padding(vertical = 10.dp).testTag("group_new_channel"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusMark(channel)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Unit channel", color = Ink)
                        Text("Broadcast: only admins post.", style = MaterialTheme.typography.bodySmall, color = Body)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Kicker("Members · ${picked.size} picked")
                if (pickable.isEmpty()) Hint("No contacts yet. Connect to teammates first.")
                pickable.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (c.convo in picked) picked - c.convo else picked + c.convo }
                            .padding(vertical = 10.dp).testTag("group_pick_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(c.convo in picked)
                        Spacer(Modifier.size(12.dp))
                        Text(c.name + (c.codename?.let { " · $it" } ?: ""), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each message is encrypted separately to every member over their own session. Members who are offline get it when they next connect.",
                    style = MaterialTheme.typography.bodySmall, color = Body,
                )
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    busy = true
                    scope.launch {
                        node.createGroup(name, picked.toList(), channel)
                            .onSuccess { onCreated(it.convo) }
                            .onFailure { error = it.message ?: "Could not create the group"; busy = false }
                    }
                },
                modifier = Modifier.testTag("group_create"),
            ) { Text("Create ↗", color = if (valid) Accent else RuleStrong) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}
