package com.infinitum.aether.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infinitum.aether.data.MediaStore
import com.infinitum.aether.data.SOCIAL_TIMERS
import com.infinitum.aether.data.StoredMessage
import com.infinitum.aether.data.convo
import com.infinitum.aether.data.isGroupConvo
import com.infinitum.aether.data.timerLabel
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.net.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.aether_core.PeerCertStatus
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import com.infinitum.aether.net.PrecedenceAlerts
import uniffi.aether_core.Precedence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ÆTHER Talk™ chat: a contact conversation or a group, with the Fabric-style message actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TalkChat(node: AetherNode, convo: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val contacts by node.contacts.collectAsState()
    val all by node.messages.collectAsState()
    val states by node.linkStates.collectAsState()
    val groups by node.chats.groups.collectAsState()
    val settingsMap by node.chats.settings.collectAsState()
    val typingMap by node.typing.collectAsState()
    val isGroup = isGroupConvo(convo)
    val group = if (isGroup) groups[convo.removePrefix("g:")] else null
    val contact = contacts[convo]
    val burnerList by node.burners.collectAsState()
    val via = group?.via ?: contact?.via
    val viaBurner = contact?.via?.let { id -> burnerList.firstOrNull { it.id == id } }
    val settings = settingsMap[convo]
    val timer = settings?.timerS ?: 0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val msgs = remember(all, convo, now / 5_000) { all.filter { it.convo == convo && !it.expired() } }
    val byId = remember(msgs) { msgs.filter { it.id != null }.associateBy { it.id!! } }
    val online = if (isGroup) group?.left == false else states[convo] == LinkState.ONLINE
    val ownKey = remember(convo, group?.via) { node.ownKeyFor(convo) }
    // In a unit channel only admins post; everyone can still react, read and see the traffic.
    val canSend = if (isGroup) group?.let { !it.left && (!it.channel || it.isAdmin(ownKey)) } == true else online
    val isAdmin = group?.let { !it.left && it.isAdmin(ownKey) } ?: true
    var precedence by remember(convo) { mutableStateOf(Precedence.ROUTINE) }
    var confirmWipe by remember { mutableStateOf(false) }
    val peerCert = node.peerCerts.collectAsState().value[convo]
    val typers = typingMap[convo].orEmpty().filterValues { it > now }.keys
    var draft by rememberSaveable(convo) { mutableStateOf("") }
    var replyTo by remember(convo) { mutableStateOf<StoredMessage?>(null) }
    var editing by remember(convo) { mutableStateOf<StoredMessage?>(null) }
    var selected by remember { mutableStateOf<StoredMessage?>(null) }
    var forwarding by remember { mutableStateOf<StoredMessage?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    var showDrop by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val mine = remember(convo, group) { node.ownKeyFor(convo) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val media = remember { (ctx.applicationContext as com.infinitum.aether.AetherApp).features.media }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    // Read receipts: while this chat is on screen (resumed), mark the newest incoming message read.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    val newestIn = msgs.lastOrNull { !it.outgoing && it.kind == null }
    LaunchedEffect(newestIn?.id, newestIn?.sentAtMs, life, online) {
        if (newestIn != null && life.isAtLeast(Lifecycle.State.RESUMED)) node.markRead(convo)
    }
    // Precedence notifications are skipped for the chat on screen.
    DisposableEffect(convo, life) {
        PrecedenceAlerts.openConvo = if (life.isAtLeast(Lifecycle.State.RESUMED)) convo else null
        onDispose { if (PrecedenceAlerts.openConvo == convo) PrecedenceAlerts.openConvo = null }
    }
    // Typing: "on" while the draft changes (the node throttles it), "off" after 4 s idle.
    LaunchedEffect(draft) {
        if (draft.isBlank() || editing != null) { node.setTyping(convo, false); return@LaunchedEffect }
        node.setTyping(convo, true)
        delay(4_000)
        node.setTyping(convo, false)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BrandBar(onBack = onBack, backTag = "chat_back", actions = {
            if (isGroup) {
                BarAction("Info", "chat_group_info") { showInfo = true }
            } else {
                if (online) BarAction("Drop key ↓", "chat_drop") { showDrop = true }
                BarAction(if (contact?.verified == true) "Verified ✓" else "Verify", "chat_safety") { showSafety = true }
            }
            if (canSend && isAdmin) BarAction("Timer", "chat_timer") { showTimer = true }
            if (isAdmin && (if (isGroup) group?.left == false else online)) BarAction("Wipe", "chat_wipe") { confirmWipe = true }
        })
        Column(Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 18.dp)) {
            Text(group?.name ?: contact?.name ?: "Peer", style = MaterialTheme.typography.headlineMedium, color = Ink, modifier = Modifier.testTag("chat_title"))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMark(online)
                Spacer(Modifier.size(8.dp))
                if (typers.isNotEmpty()) {
                    Kicker(
                        if (isGroup) typers.joinToString(", ") { node.nameFor(via, it) } + " typing…" else "typing…",
                        color = Accent, modifier = Modifier.testTag("chat_typing"),
                    )
                } else {
                    Kicker(
                        when {
                            isGroup && group?.left == true -> "You are no longer in this group"
                            isGroup && group?.channel == true -> "Unit channel · ${group.members.size} members · " + (if (isAdmin) "you post as admin" else "only admins post")
                            isGroup -> "Group · ${group?.members?.size ?: 0} members · end-to-end encrypted pairwise"
                            online -> "End-to-end encrypted · " + (if (contact?.pq == true) "post-quantum" else "classical") +
                                when (peerCert?.status) {
                                    null -> ""
                                    PeerCertStatus.VALID -> " · certified ${peerCert.label}"
                                    PeerCertStatus.ABSENT -> " · uncertified"
                                    else -> " · certificate ${peerCert.status.name.lowercase()}"
                                } + " · online"
                            else -> "Offline"
                        },
                        color = if (online) Ink else Body,
                        modifier = Modifier.testTag("chat_status"),
                    )
                }
            }
            if (timer > 0) {
                Spacer(Modifier.height(6.dp))
                Kicker("Disappearing messages · ${timerLabel(timer)}", color = Ink, modifier = Modifier.testTag("chat_timer_status"))
            }
            if (viaBurner != null) {
                Spacer(Modifier.height(6.dp))
                Kicker("Via ${AetherNode.formatNumber(viaBurner.number)} · ${viaBurner.label}", color = Ink, modifier = Modifier.testTag("chat_via"))
            }
            if (contact?.pinned == true) {
                Spacer(Modifier.height(6.dp))
                Kicker("Pinned key · ${contact.codename ?: ""}", color = Ink, modifier = Modifier.testTag("chat_pinned"))
            }
        }
        Hairline()
        // Pinned bar: the newest pinned message still on this device; tap to jump to it.
        val pinned = remember(settings?.pins, byId) { settings?.pins.orEmpty().mapNotNull { byId[it] }.filter { !it.deleted } }
        pinned.lastOrNull()?.let { p ->
            Row(
                Modifier.fillMaxWidth().background(Wash).clickable {
                    val i = msgs.indexOf(p)
                    if (i >= 0) scope.launch { list.animateScrollToItem(i) }
                }.padding(horizontal = GUTTER.dp, vertical = 10.dp).testTag("chat_pinned_bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(32.dp).background(Ink))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Kicker(if (pinned.size > 1) "Pinned · ${pinned.size}" else "Pinned", color = Ink)
                    Text(previewOf(node, p), style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isAdmin && p.id != null) Text(
                    "✕", color = Body, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { scope.launch { node.togglePin(convo, p.id).onFailure { error = it.message } } }.padding(8.dp).testTag("chat_unpin"),
                )
            }
            Hairline()
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = GUTTER.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(msgs) { i, m ->
                when {
                    m.drop -> DropCard(node, m, fmt)
                    m.kind == StoredMessage.KIND_SYSTEM -> Text(
                        m.body, style = MaterialTheme.typography.labelSmall, color = Body,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("msg_system"),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    else -> Bubble(
                        node, m, i, isGroup, via, mine, fmt, byId,
                        onLongPress = { selected = m },
                        onReact = { emoji -> m.id?.let { id -> scope.launch { node.toggleReaction(convo, id, emoji).onFailure { error = it.message } } } },
                        media = media,
                    )
                }
            }
        }
        Hairline()
        val bar = editing ?: replyTo
        if (bar != null) {
            Row(
                Modifier.fillMaxWidth().background(Wash).padding(horizontal = GUTTER.dp, vertical = 10.dp).testTag(if (editing != null) "chat_editing" else "chat_replying"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(32.dp).background(Accent))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Kicker(if (editing != null) "Editing message" else "Replying to " + senderName(node, bar, via), color = Accent)
                    Text(previewOf(node, bar), style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "✕", color = Ink, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { if (editing != null) draft = ""; editing = null; replyTo = null }.padding(8.dp).testTag("chat_bar_cancel"),
                )
            }
        }
        error?.let {
            Text(it, color = Alert, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = GUTTER.dp, vertical = 4.dp).testTag("chat_error"))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSend && editing == null) {
                // Photos, files and voice messages: seal locally, then send offer → chunks → message.
                fun send(prep: suspend () -> Result<MediaStore.Outgoing>) {
                    val caption = draft.trim()
                    scope.launch {
                        val out = prep().getOrElse { error = it.message ?: "Could not attach"; return@launch }
                        node.sendFile(convo, out.offer, out.chunks, caption, onChunk = { n ->
                            media.markProgress(out.fileId, minOf(out.size, n.toLong() * 32_768L /* core SOCIAL_CHUNK_BYTES */))
                        }, precedence = precedence).onSuccess {
                            precedence = Precedence.ROUTINE
                            media.markSent(out.fileId)
                            if (caption.isNotEmpty()) draft = ""
                        }.onFailure {
                            media.markFailed(out.fileId)
                            error = it.message ?: "Not sent"
                        }
                    }
                }
                AttachButton(
                    onPicked = { uri -> send { media.prepareOutgoing(uri) } },
                    onVoice = { file, _ -> send { media.prepareOutgoing(file, "Voice message.m4a", "audio/mp4") } },
                )
                Spacer(Modifier.size(8.dp))
            }
            if (canSend && editing == null) {
                PrecedenceChip(precedence) { precedence = Precedence.entries[(precedence.ordinal + 1) % Precedence.entries.size] }
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(8000); error = null },
                placeholder = {
                    Text(
                        when {
                            canSend -> "Encrypted message"
                            isGroup && group?.left == false -> "Only admins post in this channel"
                            isGroup -> "You left this group"
                            else -> "Peer offline"
                        },
                        color = Body,
                    )
                },
                enabled = canSend,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.weight(1f).testTag("chat_input"),
                maxLines = 4,
            )
            Spacer(Modifier.size(10.dp))
            PrimaryButton(if (editing != null) "Save" else "Send", enabled = canSend && draft.isNotBlank(), tag = "chat_send") {
                val text = draft.trim()
                val ed = editing
                val re = replyTo
                scope.launch {
                    val r = if (ed?.id != null) node.editMessage(convo, ed.id, text) else node.sendText(convo, text, re?.id, precedence = precedence)
                    r.onSuccess { draft = ""; editing = null; replyTo = null; precedence = Precedence.ROUTINE }.onFailure { error = it.message ?: "Not sent" }
                }
            }
        }
        if (!isGroup && !online) {
            TextButton(onClick = { node.redial(convo) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                Text("Reconnect if nearby ↗", color = Accent)
            }
        }
    }

    selected?.let { m ->
        MessageActions(
            m, canSend, mine,
            pinned = m.id != null && m.id in settings?.pins.orEmpty(),
            canPin = isAdmin && (if (isGroup) group?.left == false else online),
            onPin = { m.id?.let { id -> scope.launch { node.togglePin(convo, id).onFailure { error = it.message } } } },
            onDismiss = { selected = null },
            onReply = { replyTo = m; editing = null },
            onEdit = { editing = m; replyTo = null; draft = m.body },
            onForward = { forwarding = m },
            onReact = { emoji -> m.id?.let { id -> scope.launch { node.toggleReaction(convo, id, emoji).onFailure { error = it.message } } } },
            onDeleteMine = { node.deleteForMe(m) },
            onDeleteAll = { m.id?.let { id -> scope.launch { node.deleteForEveryone(convo, id).onFailure { error = it.message } } } },
        )
    }
    forwarding?.let { m -> ForwardDialog(node, m, from = convo, onDismiss = { forwarding = null }, onSent = { forwarding = null; onOpenChat(it) }) }
    if (showTimer) TimerDialog(timer, onDismiss = { showTimer = false }) { s ->
        showTimer = false
        scope.launch { node.setTimer(convo, s).onFailure { error = it.message ?: "Timer not set" } }
    }
    if (showInfo && group != null) GroupInfoDialog(node, convo, onDismiss = { showInfo = false })
    if (showDrop) DropDialog(node = node, convo = convo, onDismiss = { showDrop = false })
    if (showSafety) SafetyDialog(node, convo, onDismiss = { showSafety = false })
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Wipe for everyone?", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Text(
                "Every message, file and pin in “${group?.name ?: contact?.name ?: "this chat"}” is erased on this device and on every device in it" +
                    (if (isGroup) " (members who are offline when they reconnect)" else "") + ". This cannot be undone.",
                color = Body,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { confirmWipe = false; scope.launch { node.wipeForEveryone(convo).onFailure { error = it.message ?: "Not wiped" } } },
                modifier = Modifier.testTag("chat_wipe_confirm"),
            ) { Text("Wipe", color = Alert) }
        },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel", color = Body) } },
    )
}

/** Precedence of the next message; tap to step Routine → Priority → Immediate → Flash. */
@Composable
private fun PrecedenceChip(p: Precedence, onTap: () -> Unit) {
    val color = precedenceColor(p)
    Text(
        p.name.first().toString(),
        style = MaterialTheme.typography.titleMedium,
        color = if (p == Precedence.FLASH) Paper else color,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.width(40.dp).border(1.dp, color).background(if (p == Precedence.FLASH) color else Paper)
            .clickable(onClick = onTap).padding(vertical = 10.dp).testTag("chat_precedence_${p.name.lowercase()}"),
    )
}

internal fun precedenceColor(p: Precedence): Color = when (p) {
    Precedence.FLASH -> Alert
    Precedence.IMMEDIATE -> Color(0xFFC45500)
    Precedence.PRIORITY -> Color(0xFF8A5A00)
    Precedence.ROUTINE -> Ink
}

private fun senderName(node: AetherNode, m: StoredMessage, via: String?) = if (m.outgoing) "You" else node.nameFor(via, m.peerKeyHex)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    node: AetherNode,
    m: StoredMessage,
    index: Int,
    isGroup: Boolean,
    via: String?,
    mine: String,
    fmt: SimpleDateFormat,
    byId: Map<String, StoredMessage>,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    media: MediaStore,
) {
    val fg = if (m.outgoing) Paper else Ink
    val sub = if (m.outgoing) Wash else Body
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
        val urgent = m.precedence == Precedence.IMMEDIATE || m.precedence == Precedence.FLASH
        Column(
            Modifier.widthIn(max = 290.dp)
                .background(if (m.outgoing) InkButton else Wash)
                .then(if (urgent) Modifier.border(2.dp, precedenceColor(m.precedence)) else Modifier)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(if (m.outgoing) "msg_out" else "msg_in"),
        ) {
            if (m.precedence != Precedence.ROUTINE && !m.deleted) {
                val c = precedenceColor(m.precedence)
                Text(
                    m.precedence.name, style = MaterialTheme.typography.labelSmall,
                    color = if (m.precedence == Precedence.FLASH) Paper else if (m.outgoing) Paper else c,
                    modifier = Modifier.then(if (m.precedence == Precedence.FLASH) Modifier.background(c) else Modifier.border(1.dp, if (m.outgoing) Paper else c))
                        .padding(horizontal = 6.dp, vertical = 1.dp).testTag("msg_precedence"),
                )
                Spacer(Modifier.height(6.dp))
            }
            if (isGroup && !m.outgoing) {
                Kicker(node.nameFor(via, m.peerKeyHex), color = Accent, modifier = Modifier.testTag("msg_sender"))
                Spacer(Modifier.height(4.dp))
            }
            if (m.forwarded && !m.deleted) {
                Kicker("Forwarded", color = sub, modifier = Modifier.testTag("msg_forwarded"))
                Spacer(Modifier.height(4.dp))
            }
            val quoted = m.replyTo?.let { byId[it] }
            if (m.replyTo != null && !m.deleted) {
                Row(Modifier.padding(bottom = 6.dp).testTag("msg_quote")) {
                    Box(Modifier.width(2.dp).height(30.dp).background(if (m.outgoing) Paper else Accent))
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Kicker(quoted?.let { senderName(node, it, via) } ?: "Reply", color = sub)
                        Text(
                            quoted?.let { previewOf(node, it) } ?: "Original message not available",
                            style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (m.deleted) {
                Text("Message deleted", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = sub, modifier = Modifier.testTag("msg_deleted"))
            } else {
                val file = m.fileId?.let { media.files.collectAsState().value[it] }
                if (file != null) {
                    MediaBubble(file, media)
                    if (m.body.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                val text = chatBodyText(m)
                if (text.isNotBlank() || file == null) {
                    Text(text.ifEmpty { if (m.fileId != null) "Attachment (not received)" else "" }, style = MaterialTheme.typography.bodyLarge, color = fg)
                }
            }
        }
        // Meta line under the bubble, on paper, so the read mark can use the accent.
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt.format(Date(m.sentAtMs)), style = MaterialTheme.typography.labelSmall, color = Body)
            if (m.edited && !m.deleted) Text("  · edited", style = MaterialTheme.typography.labelSmall, color = Body, modifier = Modifier.testTag("msg_edited"))
            if (m.expiresAt > 0) Text("  · ⏱", style = MaterialTheme.typography.labelSmall, color = Body)
            if (m.outgoing && m.id != null && !m.deleted) {
                val (mark, color, tag) = when (m.delivery) {
                    StoredMessage.DELIVERY_READ -> Triple("✓✓", Accent, "msg_read")
                    StoredMessage.DELIVERY_DELIVERED -> Triple("✓✓", Body, "msg_delivered")
                    else -> Triple("✓", Body, "msg_sent")
                }
                Text("  $mark", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.testTag("${tag}_$index"))
            }
        }
        if (m.reactions.isNotEmpty() && !m.deleted) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.reactions.entries.sortedByDescending { it.value.size }.forEach { (emoji, who) ->
                    val me = mine in who
                    Text(
                        "$emoji ${who.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (me) Accent else Ink,
                        modifier = Modifier.border(1.dp, if (me) Accent else Rule).clickable { onReact(emoji) }
                            .padding(horizontal = 8.dp, vertical = 2.dp).testTag("reaction_${index}_$emoji"),
                    )
                }
            }
        }
    }
}

/** Long-press menu: quick reactions, then Reply, Copy, Edit, Forward, Delete. */
@Composable
private fun MessageActions(
    m: StoredMessage,
    canSend: Boolean,
    mine: String,
    pinned: Boolean,
    canPin: Boolean,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onReact: (String) -> Unit,
    onDeleteMine: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val social = m.id != null && !m.deleted
    val isText = m.kind == null && !m.drop && !m.deleted && m.body.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text(if (confirmDelete) "Delete message?" else "Message", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!confirmDelete) {
                    if (social && canSend) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            AetherNode.QUICK_REACTIONS.forEachIndexed { i, e ->
                                val on = mine in m.reactions[e].orEmpty()
                                Text(
                                    e, style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.then(if (on) Modifier.border(1.dp, Accent) else Modifier)
                                        .clickable { onReact(e); onDismiss() }.padding(6.dp).testTag("react_$i"),
                                )
                            }
                        }
                        Hairline()
                    }
                    val actions = buildList {
                        if (social && canSend) add(Triple("Reply", "action_reply", onReply))
                        if (isText) add(Triple("Copy", "action_copy") { clipboard.setText(AnnotatedString(m.body)) })
                        if (social && m.outgoing && isText && canSend) add(Triple("Edit", "action_edit", onEdit))
                        if (isText) add(Triple("Forward", "action_forward", onForward))
                        if (social && canPin) add(Triple(if (pinned) "Unpin" else "Pin for everyone", "action_pin", onPin))
                        add(Triple("Delete…", "action_delete") { confirmDelete = true })
                    }
                    actions.forEach { (label, tag, act) ->
                        Column(Modifier.fillMaxWidth().clickable { act(); if (tag != "action_delete") onDismiss() }.testTag(tag)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tag == "action_delete") Alert else Accent, modifier = Modifier.padding(vertical = 14.dp))
                            Hairline()
                        }
                    }
                } else {
                    Text("Delete for me removes it from this device. Delete for everyone also removes it on the other devices.", color = Body)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clickable { onDeleteMine(); onDismiss() }.testTag("action_delete_me")) {
                        Text("Delete for me", style = MaterialTheme.typography.bodyLarge, color = Alert, modifier = Modifier.padding(vertical = 14.dp))
                        Hairline()
                    }
                    if (social && m.outgoing && canSend) {
                        Column(Modifier.fillMaxWidth().clickable { onDeleteAll(); onDismiss() }.testTag("action_delete_all")) {
                            Text("Delete for everyone", style = MaterialTheme.typography.bodyLarge, color = Alert, modifier = Modifier.padding(vertical = 14.dp))
                            Hairline()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("action_close")) { Text("Cancel", color = Body) } },
    )
}

/** Forward: pick another conversation that can take a message now. */
@Composable
private fun ForwardDialog(node: AetherNode, m: StoredMessage, from: String, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val entries = talkEntries(node).filter { it.convo != from && it.online }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Forward to", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) Hint("No other conversation is online right now.")
                entries.forEachIndexed { i, e ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { node.forward(m, e.convo).onSuccess { onSent(e.convo) }.onFailure { error = it.message ?: "Not sent" } }
                        }.testTag("forward_$i"),
                    ) {
                        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusMark(e.online)
                            Spacer(Modifier.size(12.dp))
                            Text(e.title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (e.isGroup) { Spacer(Modifier.size(8.dp)); Kicker("Group") }
                        }
                        Hairline()
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

@Composable
private fun TimerDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Disappearing messages", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text("New messages in this chat are removed from every device after this time.", color = Body)
                Spacer(Modifier.height(8.dp))
                SOCIAL_TIMERS.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 10.dp).testTag("timer_$s"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(s == current)
                        Spacer(Modifier.size(12.dp))
                        Text(timerLabel(s), color = Ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

/** Group info: name and members (editable by the admin), and Leave. */
@Composable
private fun GroupInfoDialog(node: AetherNode, convo: String, onDismiss: () -> Unit) {
    val groups by node.chats.groups.collectAsState()
    val contacts by node.contacts.collectAsState()
    val g = groups[convo.removePrefix("g:")] ?: return
    val me = node.ownKeyFor(convo)
    val admin = g.isAdmin(me) && !g.left
    var name by rememberSaveable(g.id) { mutableStateOf(g.name) }
    var members by remember(g.id, g.members) { mutableStateOf(g.members.filter { it != me }.toSet()) }
    var admins by remember(g.id, g.admins) { mutableStateOf(g.admins.filter { it != me }.toSet()) }
    var channel by remember(g.id, g.channel) { mutableStateOf(g.channel) }
    var confirmLeave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val candidates = remember(contacts, g) {
        (g.members.filter { it != me } + contacts.values.filter { it.via == g.via }.map { it.keyHex.lowercase() }).distinct()
    }
    val changed = name.trim() != g.name || members != g.members.filter { it != me }.toSet() ||
        admins.intersect(members) != g.admins.filter { it != me }.toSet() || channel != g.channel
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text(if (confirmLeave) "Leave “${g.name}”?" else if (g.channel) "Unit channel" else "Group info", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (confirmLeave) {
                    Text("You stop receiving this group's messages. The chat stays on this device.", color = Body)
                    return@Column
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.replace("\n", "").take(64) }, singleLine = true, enabled = admin,
                    label = { Text("Group name") }, shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("group_info_name"),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().then(if (admin) Modifier.clickable { channel = !channel } else Modifier).padding(vertical = 8.dp).testTag("group_channel"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusMark(channel)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Unit channel", color = Ink)
                        Text("Only admins post. Everyone reads and reacts.", style = MaterialTheme.typography.bodySmall, color = Body)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Kicker("Admins · " + g.admins.joinToString(", ") { node.nameFor(g.via, it) })
                Spacer(Modifier.height(6.dp))
                Kicker("Members · ${members.size + 1}")
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(true); Spacer(Modifier.size(12.dp)); Text("You", color = Ink)
                }
                candidates.forEachIndexed { i, k ->
                    val on = k in members
                    if (!admin && !on) return@forEachIndexed
                    Row(
                        Modifier.fillMaxWidth().then(if (admin) Modifier.clickable { members = if (on) members - k else members + k } else Modifier)
                            .padding(vertical = 10.dp).testTag("group_member_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(on)
                        Spacer(Modifier.size(12.dp))
                        Text(node.nameFor(g.via, k), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val isAdm = k in admins
                        if (admin && on) {
                            Text(
                                if (isAdm) "Admin ✓" else "Make admin", style = MaterialTheme.typography.labelLarge,
                                color = if (isAdm) Ink else Accent,
                                modifier = Modifier.clickable { admins = if (isAdm) admins - k else admins + k }.padding(6.dp).testTag("group_admin_$i"),
                            )
                        } else if (isAdm) Kicker("Admin")
                    }
                }
                if (!admin && !g.left) Hint("Only admins rename it, change members and roles, pin and wipe.")
                if (!g.left) {
                    Column(Modifier.fillMaxWidth().clickable { confirmLeave = true }.testTag("group_leave")) {
                        Hairline()
                        Text("Leave group", style = MaterialTheme.typography.bodyLarge, color = Alert, modifier = Modifier.padding(vertical = 14.dp))
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = {
            when {
                confirmLeave -> TextButton(
                    onClick = { scope.launch { node.leaveGroup(convo).onSuccess { onDismiss() }.onFailure { error = it.message; confirmLeave = false } } },
                    modifier = Modifier.testTag("group_leave_confirm"),
                ) { Text("Leave", color = Alert) }
                admin -> TextButton(
                    enabled = changed && name.isNotBlank() && members.isNotEmpty(),
                    onClick = { scope.launch { node.updateGroup(convo, name, members.toList(), admins.intersect(members).toList(), channel).onSuccess { onDismiss() }.onFailure { error = it.message ?: "Not saved" } } },
                    modifier = Modifier.testTag("group_info_save"),
                ) { Text("Save", color = if (changed && name.isNotBlank() && members.isNotEmpty()) Accent else RuleStrong) }
                else -> TextButton(onClick = onDismiss) { Text("Close", color = Ink) }
            }
        },
        dismissButton = { TextButton(onClick = { if (confirmLeave) confirmLeave = false else onDismiss() }) { Text("Cancel", color = Body) } },
    )
}

@Composable
private fun SafetyDialog(node: AetherNode, convo: String, onDismiss: () -> Unit) {
    val contact = node.contacts.collectAsState().value[convo]
    val sn = remember { node.safetyNumber(convo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Safety number", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text(
                    if (sn != null) "Compare this number with ${contact?.name ?: "your peer"} in person or over a trusted channel. " +
                        "If it matches on both devices, nobody is intercepting this conversation."
                    else "Connect to this peer to compute the safety number.",
                    color = Body,
                )
                if (sn != null) {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().border(1.dp, Rule).padding(14.dp)) {
                        Text(
                            sn.chunked(24).joinToString("\n") { it.trim() },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                            color = Ink,
                            modifier = Modifier.testTag("safety_number"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (sn != null && contact?.verified != true) {
                TextButton(onClick = { node.markVerified(convo); onDismiss() }, modifier = Modifier.testTag("safety_verify")) {
                    Text("Mark as verified ✓", color = Ink)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = Ink) }
            }
        },
    )
}
