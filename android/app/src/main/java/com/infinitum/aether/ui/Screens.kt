package com.infinitum.aether.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infinitum.aether.CheckResult
import com.infinitum.aether.SelfTest
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.net.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.infinitum.aether.data.Bundle
import com.infinitum.aether.data.Burner
import com.infinitum.aether.data.ScopeKind
import com.infinitum.aether.data.StoredMessage
import com.infinitum.aether.data.convo
import com.infinitum.aether.data.convoKey
import com.infinitum.aether.data.toHex
import uniffi.aether_core.KeyDrop
import uniffi.aether_core.coreVersion
import uniffi.aether_core.decodeKeyDrop
import uniffi.aether_core.fingerprint
import uniffi.aether_core.parseBurnerNumber
import uniffi.aether_core.suggestCodename
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PRODUCT = "ÆTHER MIL™"
private const val APP = "ÆTHER Mobile MIL™"
private const val TAGLINE = "SECURE VOICE, TEXT & DATA · HARDENED COMMUNICATIONS · FIELD MESH"
private const val GUTTER = 24

private sealed interface Screen {
    data object Home : Screen
    data class Chat(val convo: String) : Screen
    data object Numbers : Screen
    data object Diagnostics : Screen
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AetherRoot(
    loading: Boolean,
    node: AetherNode?,
    fatalError: String?,
    keyBacking: () -> String,
    onCreateProfile: suspend (String) -> Unit,
    scratchDir: File,
) {
    Surface(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        color = Paper,
    ) {
        when {
            fatalError != null -> Centered { Text("$PRODUCT could not open its secure store.\n\n$fatalError", color = Alert) }
            loading -> Centered { CircularProgressIndicator(color = Ink, strokeWidth = 1.5.dp) }
            node == null -> Onboarding(onCreateProfile)
            else -> Shell(node, keyBacking, scratchDir)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(GUTTER.dp), contentAlignment = Alignment.Center) { content() }

// ---- Brand elements ---------------------------------------------------------------

/** Top bar used on every screen: product name (or back link) left, text actions right. */
@Composable
private fun BrandBar(
    onBack: (() -> Unit)? = null,
    backTag: String = "back",
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = GUTTER.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    "← Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp).testTag(backTag),
                )
            } else {
                Text(PRODUCT, style = MaterialTheme.typography.labelLarge, color = Ink, modifier = Modifier.testTag("brand"))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        Hairline()
    }
}

@Composable
private fun BarAction(label: String, tag: String, onClick: () -> Unit) = Text(
    label,
    style = MaterialTheme.typography.labelLarge,
    color = Ink,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp).testTag(tag),
)

@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Body) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)

@Composable
private fun Hairline(color: Color = Rule) = HorizontalDivider(thickness = 1.dp, color = color)

/** Numbered section header with a rule above it, as on the product pages ("01 / OVERVIEW"). */
@Composable
private fun Section(number: Int, title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 4.dp)) {
        Hairline(RuleStrong)
        Spacer(Modifier.height(10.dp))
        Kicker("%02d / %s".format(number, title))
    }
}

/** Square status mark: filled ink = live session, outlined = offline. */
@Composable
private fun StatusMark(online: Boolean) = Box(
    Modifier.size(8.dp).then(
        if (online) Modifier.background(Ink) else Modifier.border(1.dp, RuleStrong),
    ),
)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Button(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    colors = ButtonDefaults.buttonColors(containerColor = InkButton, contentColor = Paper, disabledContainerColor = Wash, disabledContentColor = RuleStrong),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    modifier = modifier.testTag(tag),
) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink,
    unfocusedBorderColor = RuleStrong,
    disabledBorderColor = Rule,
    focusedLabelColor = Ink,
    unfocusedLabelColor = Body,
    cursorColor = Ink,
)

// ---- Onboarding ---------------------------------------------------------------------

@Composable
private fun Onboarding(onCreate: suspend (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding()) {
        BrandBar()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Kicker("Military & defense / Field communications")
            Spacer(Modifier.height(6.dp))
            Kicker("Authorized organizations only", color = Ink)
            Spacer(Modifier.height(18.dp))
            Text(PRODUCT, style = MaterialTheme.typography.displaySmall, color = Ink)
            Spacer(Modifier.height(18.dp))
            Text(
                "Hardened field communications and mesh networking for authorized military and defense organizations. " +
                    "Every message is end-to-end encrypted. Your identity key is generated on this device, sealed by its " +
                    "hardware keystore, and never leaves it.",
                style = MaterialTheme.typography.bodyLarge,
                color = Body,
            )
            Spacer(Modifier.height(12.dp))
            Text(TAGLINE, style = MaterialTheme.typography.labelMedium, color = Body)
            Spacer(Modifier.height(40.dp))
            Hairline(RuleStrong)
            Spacer(Modifier.height(10.dp))
            Kicker("01 / Operator identity")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Call sign or display name") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("onboarding_name"),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (busy) "Generating keys…" else "Create secure identity  ↗",
                enabled = name.isNotBlank() && !busy,
                tag = "onboarding_create",
                modifier = Modifier.fillMaxWidth(),
            ) { busy = true; scope.launch { onCreate(name.trim()) } }
            Spacer(Modifier.height(40.dp))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 14.dp).navigationBarsPadding()) {
            Kicker("Military use only")
            Spacer(Modifier.weight(1f))
            Kicker(APP)
        }
    }
}

// ---- Shell ----------------------------------------------------------------------------

@Composable
private fun Shell(node: AetherNode, keyBacking: () -> String, scratchDir: File) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val snackbar = remember { SnackbarHostState() }
    val event by node.events.collectAsState()
    LaunchedEffect(event) {
        event?.let { snackbar.showSnackbar(it); node.clearEvent() }
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Paper,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = screen) {
                Screen.Home -> Home(
                    node,
                    onOpenChat = { screen = Screen.Chat(it) },
                    onDiagnostics = { screen = Screen.Diagnostics },
                    onNumbers = { screen = Screen.Numbers },
                )
                is Screen.Chat -> Chat(node, s.convo, onBack = { screen = Screen.Home })
                Screen.Numbers -> Numbers(node, onBack = { screen = Screen.Home })
                Screen.Diagnostics -> Diagnostics(keyBacking(), scratchDir, onBack = { screen = Screen.Home })
            }
        }
    }
}

@Composable
private fun Home(node: AetherNode, onOpenChat: (String) -> Unit, onDiagnostics: () -> Unit, onNumbers: () -> Unit) {
    val contacts by node.contacts.collectAsState()
    val nearby by node.nearby.collectAsState()
    val states by node.linkStates.collectAsState()
    val messages by node.messages.collectAsState()
    var showConnect by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BrandBar(actions = {
            BarAction("Numbers", "open_numbers", onNumbers)
            BarAction("Diagnostics", "open_diagnostics", onDiagnostics)
            BarAction("Connect +", "open_connect") { showConnect = true }
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp),
        ) {
            item {
                Spacer(Modifier.height(32.dp))
                Kicker("Military & defense / Field communications")
                Spacer(Modifier.height(14.dp))
                Text(PRODUCT, style = MaterialTheme.typography.displaySmall, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(TAGLINE, style = MaterialTheme.typography.labelMedium, color = Body)
                Spacer(Modifier.height(24.dp))
                IdentityBlock(node)
            }
            item { Section(1, "ÆTHER Mesh™ · nearby nodes") }
            val unknownNearby = nearby.values.filter { p ->
                !AetherNode.isNumberService(p.serviceName) && contacts.values.none { p.serviceName.endsWith(it.keyHex.take(12)) }
            }
            if (unknownNearby.isEmpty()) item { Hint("No ÆTHER MIL™ nodes found on this network yet.") }
            items(unknownNearby, key = { it.serviceName }) { p ->
                ListRow(
                    tag = "nearby_${p.serviceName}",
                    online = false,
                    title = p.serviceName,
                    titleMono = true,
                    subtitle = "${p.host}:${p.port}",
                    trailing = "Connect ↗",
                ) { node.connect(p.host, p.port) }
            }
            item { Section(2, "ÆTHER Talk™ · conversations") }
            if (contacts.isEmpty()) item { Hint("Connect to a nearby node, or enter an address with Connect +.") }
            items(contacts.values.toList(), key = { it.convo }) { c ->
                val last = messages.lastOrNull { it.convo == c.convo }
                val st = states[c.convo] ?: LinkState.OFFLINE
                val via = c.via?.let { "via " + AetherNode.formatNumber(node.numberOf(it)) + " · " } ?: ""
                ListRow(
                    tag = "contact_${c.name}",
                    online = st == LinkState.ONLINE,
                    title = c.name + (c.codename?.let { "  · $it" } ?: "") + if (c.verified) "  ✓" else "",
                    subtitle = via + when {
                        last == null -> if (c.pinned) "Pinned key · tap to connect" else "No messages yet"
                        last.drop -> "Key drop · " + (runCatching { decodeKeyDrop(last.body).codename }.getOrNull() ?: "")
                        else -> last.body
                    },
                    trailing = st.name.lowercase(),
                ) { onOpenChat(c.convo) }
            }
        }
    }

    if (showConnect) ConnectDialog(onDismiss = { showConnect = false }) { host, port ->
        node.connect(host, port); showConnect = false
    }
}

@Composable
private fun ListRow(
    tag: String,
    online: Boolean,
    title: String,
    subtitle: String,
    trailing: String,
    titleMono: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusMark(online)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
                Text(
                    title,
                    style = if (titleMono) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Kicker(trailing, color = if (online) Ink else Body)
        }
        Hairline()
    }
}

@Composable
private fun IdentityBlock(node: AetherNode) {
    val addrs = remember { node.localAddresses() }
    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Rule)).padding(20.dp)) {
        Kicker("Operator")
        Spacer(Modifier.height(4.dp))
        Text(node.profile.displayName, style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.testTag("profile_name"))
        Spacer(Modifier.height(18.dp))
        Kicker("Identity fingerprint")
        Spacer(Modifier.height(6.dp))
        Text(
            node.profile.identity.fingerprint(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            color = Ink,
            modifier = Modifier.testTag("fingerprint"),
        )
        Spacer(Modifier.height(18.dp))
        Kicker("Listening")
        Spacer(Modifier.height(6.dp))
        Text(
            addrs.joinToString("\n").ifEmpty { "No network" },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            color = Ink,
            modifier = Modifier.testTag("listen_addresses"),
        )
    }
}

@Composable
private fun ConnectDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var addr by rememberSaveable { mutableStateOf("") }
    val parsed = remember(addr) {
        val parts = addr.trim().split(":")
        val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val port = if (parts.size > 1) parts[1].toIntOrNull() else AetherNode.PORT
        if (host != null && port != null && port in 1..65535) host to port else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Connect to a node", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            OutlinedTextField(
                value = addr, onValueChange = { addr = it }, singleLine = true,
                label = { Text("host[:port]") }, placeholder = { Text("192.168.1.20:${AetherNode.PORT}") },
                shape = MaterialTheme.shapes.small, colors = fieldColors(),
                modifier = Modifier.testTag("connect_address"),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConnect(it.first, it.second) } }, enabled = parsed != null, modifier = Modifier.testTag("connect_submit")) {
                Text("Connect ↗", color = if (parsed != null) Ink else RuleStrong)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

// ---- Chat ------------------------------------------------------------------------------

@Composable
private fun Chat(node: AetherNode, convo: String, onBack: () -> Unit) {
    val contacts by node.contacts.collectAsState()
    val all by node.messages.collectAsState()
    val states by node.linkStates.collectAsState()
    val contact = contacts[convo]
    val burnerList by node.burners.collectAsState()
    val viaBurner = contact?.via?.let { id -> burnerList.firstOrNull { it.id == id } }
    val msgs = remember(all, convo) { all.filter { it.convo == convo } }
    val online = states[convo] == LinkState.ONLINE
    var draft by rememberSaveable { mutableStateOf("") }
    var showSafety by remember { mutableStateOf(false) }
    var showDrop by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BrandBar(onBack = onBack, backTag = "chat_back", actions = {
            if (online) BarAction("Drop key ↓", "chat_drop") { showDrop = true }
            BarAction(if (contact?.verified == true) "Verified ✓" else "Verify", "chat_safety") { showSafety = true }
        })
        Column(Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 18.dp)) {
            Text(contact?.name ?: "Peer", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMark(online)
                Spacer(Modifier.size(8.dp))
                Kicker(
                    if (online) "End-to-end encrypted · " + (if (contact?.pq == true) "post-quantum" else "classical") + " · online" else "Offline",
                    color = if (online) Ink else Body,
                    modifier = Modifier.testTag("chat_status"),
                )
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
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = GUTTER.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(msgs) { m ->
                if (m.drop) { DropCard(node, m, fmt); return@items }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 290.dp)
                            .background(if (m.outgoing) InkButton else Wash)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag(if (m.outgoing) "msg_out" else "msg_in"),
                    ) {
                        Text(m.body, style = MaterialTheme.typography.bodyLarge, color = if (m.outgoing) Paper else Ink)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            fmt.format(Date(m.sentAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.outgoing) RuleStrong else Body,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                placeholder = { Text(if (online) "Encrypted message" else "Peer offline", color = Body) },
                enabled = online,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.weight(1f).testTag("chat_input"),
                maxLines = 4,
            )
            Spacer(Modifier.size(10.dp))
            PrimaryButton("Send", enabled = online && draft.isNotBlank(), tag = "chat_send") {
                val text = draft.trim()
                scope.launch { if (node.send(convo, text)) draft = "" }
            }
        }
        if (!online) {
            TextButton(onClick = { node.redial(convo) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                Text("Reconnect if nearby ↗", color = Ink)
            }
        }
    }

    if (showDrop) DropDialog(
        node = node,
        convo = convo,
        onDismiss = { showDrop = false },
    )

    if (showSafety) {
        val sn = remember { node.safetyNumber(convo) }
        AlertDialog(
            onDismissRequest = { showSafety = false },
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
                    TextButton(onClick = { node.markVerified(convo); showSafety = false }, modifier = Modifier.testTag("safety_verify")) {
                        Text("Mark as verified ✓", color = Ink)
                    }
                } else {
                    TextButton(onClick = { showSafety = false }) { Text("Close", color = Ink) }
                }
            },
        )
    }
}

// ---- Key Drop ----------------------------------------------------------------------------

/** A dropped key card in the conversation: codename, operator, fingerprint, and a pin action. */
@Composable
private fun DropCard(node: AetherNode, m: StoredMessage, fmt: SimpleDateFormat) {
    val drop = remember(m.body) { runCatching { decodeKeyDrop(m.body) }.getOrNull() } ?: return
    val keyHex = remember(drop) { drop.publicKey.toHex() }
    val fp = remember(drop) { runCatching { fingerprint(drop.publicKey) }.getOrDefault("") }
    val contacts by node.contacts.collectAsState()
    val pinConvo = convoKey(m.via, keyHex)
    val pinned = contacts[pinConvo]?.let { it.pinned && it.codename == drop.codename } == true
    val fg = if (m.outgoing) Paper else Ink
    val sub = if (m.outgoing) RuleStrong else Body
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (m.outgoing) InkButton else Paper)
                .border(1.dp, Ink)
                .padding(16.dp)
                .testTag(if (m.outgoing) "drop_out" else "drop_in"),
        ) {
            Kicker(if (m.outgoing) "Key drop · sent" else "Key drop", color = sub)
            Spacer(Modifier.height(6.dp))
            Text(drop.codename, style = MaterialTheme.typography.headlineSmall, color = fg, modifier = Modifier.testTag("drop_codename"))
            Spacer(Modifier.height(2.dp))
            Text("Operator · ${drop.name}", style = MaterialTheme.typography.bodyMedium, color = fg)
            Spacer(Modifier.height(12.dp))
            Kicker("Fingerprint", color = sub)
            Spacer(Modifier.height(4.dp))
            Text(
                fp.split(" ").chunked(3).joinToString("\n") { it.joinToString(" ") },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                color = fg,
            )
            if (drop.addresses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Kicker("Last seen", color = sub)
                Spacer(Modifier.height(4.dp))
                Text(drop.addresses.joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = fg)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!m.outgoing) {
                    when {
                        node.isLocalKey(keyHex) -> Kicker("This is your key", color = Body)
                        pinned -> {
                            Kicker("Pinned ✓", color = Ink, modifier = Modifier.testTag("drop_pinned"))
                            Spacer(Modifier.size(16.dp))
                            Text(
                                "Connect ↗",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ink,
                                modifier = Modifier.clickable { node.connectPinned(pinConvo) }.testTag("drop_connect"),
                            )
                        }
                        else -> Button(
                            onClick = { node.pinDrop(drop, m.convo) },
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = InkButton, contentColor = Paper),
                            modifier = Modifier.testTag("drop_pin"),
                        ) { Text("Pin key ↓") }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(fmt.format(Date(m.sentAtMs)), style = MaterialTheme.typography.labelSmall, color = sub)
            }
        }
    }
}

/** Composer: pick whose key to drop and give it a codename. */
@Composable
private fun DropDialog(node: AetherNode, convo: String, onDismiss: () -> Unit) {
    val contacts by node.contacts.collectAsState()
    var codename by rememberSaveable { mutableStateOf(suggestCodename()) }
    var subject by rememberSaveable { mutableStateOf<String?>(null) } // null = my key
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val options = remember(contacts) {
        val via = contacts[convo]?.via
        val me = via?.let { "My number · " + AetherNode.formatNumber(node.numberOf(it)) } ?: "My key · ${node.profile.displayName}"
        listOf<Pair<String?, String>>(null to me) +
            contacts.values.filter { it.via == via && it.convo != convo }
                .map { it.convo to "${it.name}${it.codename?.let { c -> " · $c" } ?: ""}" }
    }
    val valid = codename.trim().length in 1..32
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Drop a key", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text(
                    "Share an operator's identity key under a codename. It travels end-to-end encrypted; " +
                        "your teammate pins it and can only connect to the holder of that exact key.",
                    color = Body,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = codename,
                        onValueChange = { codename = it.take(32); error = null },
                        singleLine = true,
                        label = { Text("Codename") },
                        shape = MaterialTheme.shapes.small,
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f).testTag("drop_codename_input"),
                    )
                    TextButton(onClick = { codename = suggestCodename() }, modifier = Modifier.testTag("drop_shuffle")) {
                        Text("↻", color = Ink, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Kicker("Key to drop")
                Spacer(Modifier.height(4.dp))
                options.forEachIndexed { i, (key, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { subject = key }.padding(vertical = 10.dp).testTag("drop_subject_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(subject == key)
                        Spacer(Modifier.size(12.dp))
                        Text(label, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    scope.launch {
                        node.sendDrop(convo, codename.trim(), subject)
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Drop failed" }
                    }
                },
                modifier = Modifier.testTag("drop_send"),
            ) { Text("Drop ↓", color = if (valid) Ink else RuleStrong) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

// ---- Burner numbers ----------------------------------------------------------------------

@Composable
private fun Numbers(node: AetherNode, onBack: () -> Unit) {
    val burners by node.burners.collectAsState()
    val bundles by node.bundles.collectAsState()
    val burnSet by node.burnCodeSet.collectAsState()
    val slots = bundles.filter { it.active() }.sumOf { it.size }
    var assign by remember { mutableStateOf<Burner?>(null) }
    var dialFrom by remember { mutableStateOf<Burner?>(null) }
    var shownCode by remember { mutableStateOf<String?>(null) }
    var showRemote by remember { mutableStateOf(false) }
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    Column(Modifier.fillMaxSize()) {
        BrandBar(onBack = onBack, backTag = "numbers_back", actions = {
            if (burners.isNotEmpty()) BarAction("Burn all ✕", "numbers_burn_all") { node.burnAll() }
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 40.dp),
        ) {
            item {
                Spacer(Modifier.height(32.dp))
                Kicker("$PRODUCT / Burner numbers")
                Spacer(Modifier.height(14.dp))
                Text("Numbers", style = MaterialTheme.typography.displaySmall, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Disposable ÆTHER mesh numbers. Teammates reach you by number and never see your identity key or name. " +
                        "Create, rotate or burn a number in one tap.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Body,
                )
                Spacer(Modifier.height(20.dp))
                Kicker("${burners.size} of $slots numbers in use", color = Ink, modifier = Modifier.testTag("numbers_slots"))
                Spacer(Modifier.height(14.dp))
                PrimaryButton("New number  +", enabled = burners.size < slots, tag = "number_new", modifier = Modifier.fillMaxWidth()) {
                    node.createNumber()
                }
            }
            item { Section(1, "Active numbers") }
            if (burners.isEmpty()) item { Hint(if (slots == 0) "No number bundle on this device yet." else "No active numbers.") }
            itemsIndexed(burners, key = { _, b -> b.id }) { i, b ->
                NumberRow(i, b, onDial = { dialFrom = b }, onAssign = { assign = b }, onRotate = { node.rotateNumber(b.id) }, onBurn = { node.burnNumber(b.id) })
            }
            item {
                Section(2, "Add-on bundles")
                Text("Numbers come in bundles of 1, 2 or 5, renewed monthly or yearly.", style = MaterialTheme.typography.bodyMedium, color = Body)
                Spacer(Modifier.height(8.dp))
                bundles.forEachIndexed { i, b ->
                    val active = b.active()
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("held_$i"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (b.size == 1) "1 number" else "${b.size} numbers") + if (b.period == "year") " · yearly" else " · monthly",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) Ink else Body,
                            )
                            Kicker(
                                (if (active) "Renews " else "Expired ") + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(b.expiresAt)),
                                color = if (active) Body else Alert,
                            )
                        }
                        if (debuggable) {
                            Text(
                                "Renew (test) ↻",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ink,
                                modifier = Modifier.clickable { node.renewBundle(b.id) }.padding(vertical = 8.dp).testTag("held_${i}_renew"),
                            )
                        }
                    }
                    Hairline()
                }
                if (debuggable) {
                    Spacer(Modifier.height(12.dp))
                    Kicker("Provision (test)")
                    AetherNode.BUNDLE_SIZES.forEach { size ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (size == 1) "1 number" else "$size numbers", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
                            Bundle.PERIODS.forEach { p ->
                                Text(
                                    if (p == "year") "Yearly +" else "Monthly +",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Ink,
                                    modifier = Modifier.clickable { node.grantBundle(size, p) }
                                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                                        .testTag(if (p == "year") "bundle_${size}_year" else "bundle_$size"),
                                )
                            }
                        }
                        Hairline()
                    }
                } else {
                    if (bundles.isEmpty()) Hint("No bundles on this device.")
                    Hint("Bundles are provisioned and renewed by your organization.")
                }
            }
            item {
                Section(3, "Remote burn")
                Text(
                    "Anyone holding this device's burn code can burn every number on it from any ÆTHER session, if the device is lost or taken. " +
                        "The code is shown once; only a hash is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Body,
                )
                Spacer(Modifier.height(10.dp))
                Kicker(if (burnSet) "Burn code set" else "No burn code", color = Ink, modifier = Modifier.testTag("burn_code_status"))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        if (burnSet) "Replace burn code ↻" else "Create burn code +",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink,
                        modifier = Modifier.clickable { shownCode = node.newBurnCode() }.padding(vertical = 8.dp).testTag("burn_code_new"),
                    )
                    Text(
                        "Burn a teammate's numbers ↗",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink,
                        modifier = Modifier.clickable { showRemote = true }.padding(vertical = 8.dp).testTag("remote_burn_open"),
                    )
                }
            }
        }
    }

    assign?.let { b -> AssignDialog(b, onDismiss = { assign = null }) { label, kind, name -> node.assignNumber(b.id, label, kind, name); assign = null } }
    dialFrom?.let { b -> DialDialog(node, b, onDismiss = { dialFrom = null }) }
    shownCode?.let { code ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = Paper,
            shape = MaterialTheme.shapes.small,
            title = { Text("Burn code", style = MaterialTheme.typography.titleLarge, color = Ink) },
            text = {
                Column {
                    Text("Write this down and give it to your commander. It's shown only once and replaces any earlier code.", color = Body)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        code,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                        color = Ink,
                        modifier = Modifier.fillMaxWidth().border(1.dp, Rule).padding(14.dp).testTag("burn_code_value"),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { shownCode = null }, modifier = Modifier.testTag("burn_code_done")) { Text("I've recorded it", color = Ink) } },
        )
    }
    if (showRemote) RemoteBurnDialog(node, onDismiss = { showRemote = false })
}

@Composable
private fun NumberRow(index: Int, b: Burner, onDial: () -> Unit, onAssign: () -> Unit, onRotate: () -> Unit, onBurn: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("number_$index")) {
        Spacer(Modifier.height(16.dp))
        Text(
            AetherNode.formatNumber(b.number),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mono),
            color = Ink,
            modifier = Modifier.testTag("number_${index}_value"),
        )
        Spacer(Modifier.height(4.dp))
        Text("${b.label} · ${b.scopeText}", style = MaterialTheme.typography.bodyMedium, color = Body)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(
                Triple("Message ↗", "dial", onDial),
                Triple("Assign", "assign", onAssign),
                Triple("Rotate ↻", "rotate", onRotate),
                Triple("Burn ✕", "burn", onBurn),
            ).forEach { (label, tag, action) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tag == "burn") Alert else Ink,
                    modifier = Modifier.clickable(onClick = action).padding(vertical = 8.dp).testTag("number_${index}_$tag"),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Hairline()
    }
}

@Composable
private fun AssignDialog(b: Burner, onDismiss: () -> Unit, onSave: (String, ScopeKind, String?) -> Unit) {
    var label by rememberSaveable { mutableStateOf(b.label) }
    var kind by rememberSaveable { mutableStateOf(b.scope) }
    var name by rememberSaveable { mutableStateOf(b.scopeName ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Assign ${AetherNode.formatNumber(b.number)}", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = label, onValueChange = { label = it.take(32) }, singleLine = true,
                    label = { Text("Label") }, shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("assign_label"),
                )
                Spacer(Modifier.height(14.dp))
                Kicker("Assigned to")
                ScopeKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clickable { kind = k }.padding(vertical = 10.dp).testTag("assign_${k.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(kind == k)
                        Spacer(Modifier.size(12.dp))
                        Text(k.label, color = Ink)
                    }
                }
                if (kind != ScopeKind.PERSONAL) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(32) }, singleLine = true,
                        label = { Text(if (kind == ScopeKind.TEAM) "Team name" else "Mission name") },
                        shape = MaterialTheme.shapes.small, colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("assign_name"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, kind, name.takeIf { kind != ScopeKind.PERSONAL }) }, modifier = Modifier.testTag("assign_save")) {
                Text("Save", color = Ink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

@Composable
private fun DialDialog(node: AetherNode, from: Burner, onDismiss: () -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = remember(number) { runCatching { parseBurnerNumber(number) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Message a number", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text(
                    "From ${AetherNode.formatNumber(from.number)} (${from.label}). They'll see only this number. " +
                        "The session opens only if the other side holds the key behind the number you dial.",
                    color = Body,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = number, onValueChange = { number = it.take(20); error = null }, singleLine = true,
                    label = { Text("ÆN number") }, placeholder = { Text("ÆN 733 482 9173") },
                    shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("dial_number"),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it.take(64); error = null }, singleLine = true,
                    label = { Text("Address if not nearby (host:port)") },
                    shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("dial_address"),
                )
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    node.dialNumber(parsed!!, from.id, address.trim().ifEmpty { null })
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: "Could not dial" }
                },
                modifier = Modifier.testTag("dial_submit"),
            ) { Text("Connect ↗", color = if (parsed != null) Ink else RuleStrong) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

@Composable
private fun RemoteBurnDialog(node: AetherNode, onDismiss: () -> Unit) {
    val contacts by node.contacts.collectAsState()
    val states by node.linkStates.collectAsState()
    val online = contacts.values.filter { states[it.convo] == LinkState.ONLINE }
    var target by remember { mutableStateOf(online.firstOrNull()?.convo) }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Remote burn", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text("Burns every number on a teammate's device. You need a live session with it and its burn code.", color = Body)
                Spacer(Modifier.height(12.dp))
                if (online.isEmpty()) Text("No live sessions.", color = Body)
                online.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { target = c.convo }.padding(vertical = 10.dp).testTag("remote_burn_target_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(target == c.convo)
                        Spacer(Modifier.size(12.dp))
                        Text(c.name, color = Ink)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.take(40); error = null }, singleLine = true,
                    label = { Text("Burn code") }, shape = MaterialTheme.shapes.small, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("remote_burn_code"),
                )
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Alert) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null && code.isNotBlank(),
                onClick = {
                    val t = target ?: return@TextButton
                    scope.launch {
                        node.sendRemoteBurn(t, code)
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Remote burn failed" }
                    }
                },
                modifier = Modifier.testTag("remote_burn_send"),
            ) { Text("Burn ✕", color = Alert) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Body) } },
    )
}

// ---- Diagnostics -------------------------------------------------------------------------

@Composable
private fun Diagnostics(keyBacking: String, scratchDir: File, onBack: () -> Unit) {
    var results by remember { mutableStateOf<List<CheckResult>?>(null) }
    LaunchedEffect(Unit) { results = withContext(Dispatchers.Default) { SelfTest.run(scratchDir) } }
    Column(Modifier.fillMaxSize()) {
        BrandBar(onBack = onBack, backTag = "diag_back")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(32.dp))
            Kicker("$APP / System")
            Spacer(Modifier.height(14.dp))
            Text("Diagnostics", style = MaterialTheme.typography.displaySmall, color = Ink)
            Section(1, "Hardened security architecture")
            SpecRow("Device", APP)
            SpecRow("Security core", coreVersion())
            SpecRow("Hardware-backed keys", keyBacking)
            SpecRow("ÆTHER Talk™ session", "Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s + ML-KEM-768 (v1 fallback)")
            SpecRow("ÆTHER Vault™", "XChaCha20-Poly1305")
            SpecRow("Transport", "Wi-Fi / LAN (mesh, radio: planned)")
            Section(2, "Self-test")
            val r = results
            if (r == null) {
                Box(Modifier.padding(vertical = 16.dp)) { CircularProgressIndicator(color = Ink, strokeWidth = 1.5.dp) }
            } else {
                r.forEach { c ->
                    Column(Modifier.fillMaxWidth().testTag("check_${c.name}")) {
                        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (c.passed) Ink else Alert))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = Body)
                            }
                            Kicker(if (c.passed) "Pass" else "Fail", color = if (c.passed) Ink else Alert)
                        }
                        Hairline()
                    }
                }
                val ok = r.all { it.passed }
                Spacer(Modifier.height(16.dp))
                Kicker(
                    if (ok) "All ${r.size} checks passed" else "${r.count { !it.passed }} check(s) failed",
                    color = if (ok) Ink else Alert,
                    modifier = Modifier.testTag("selftest_summary"),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---- Bits ----------------------------------------------------------------------------------

@Composable
private fun SpecRow(k: String, v: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(k, style = MaterialTheme.typography.bodySmall, color = Body, modifier = Modifier.weight(0.42f))
            Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Ink, modifier = Modifier.weight(0.58f))
        }
        Hairline()
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Body, modifier = Modifier.padding(vertical = 14.dp))
