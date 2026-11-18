package com.infinitum.aether.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.infinitum.aether.data.Bundle
import com.infinitum.aether.data.Burner
import com.infinitum.aether.data.BurnerStore
import com.infinitum.aether.data.Contact
import com.infinitum.aether.data.EventLog
import com.infinitum.aether.data.Profile
import com.infinitum.aether.data.ScopeKind
import com.infinitum.aether.data.Store
import com.infinitum.aether.data.StoredMessage
import com.infinitum.aether.data.ChatGroup
import com.infinitum.aether.data.ChatStore
import com.infinitum.aether.data.convo
import com.infinitum.aether.data.groupConvo
import com.infinitum.aether.data.isGroupConvo
import com.infinitum.aether.data.timerLabel
import com.infinitum.aether.data.convoKey
import com.infinitum.aether.data.hexToBytes
import com.infinitum.aether.data.randomId
import com.infinitum.aether.data.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aether_core.ChatMessage
import uniffi.aether_core.SocialEvent
import uniffi.aether_core.SocialKind
import uniffi.aether_core.decodeSocial
import uniffi.aether_core.encodeSocial
import uniffi.aether_core.socialEvent
import uniffi.aether_core.EideticMessage
import uniffi.aether_core.FieldMessage
import uniffi.aether_core.PositionKind
import uniffi.aether_core.PositionReport
import uniffi.aether_core.decodeEideticMessage
import uniffi.aether_core.decodeFieldMessage
import uniffi.aether_core.decodePosition
import uniffi.aether_core.encodeEideticMessage
import uniffi.aether_core.encodeFieldMessage
import uniffi.aether_core.encodePosition
import uniffi.aether_core.Identity
import uniffi.aether_core.KeyDrop
import uniffi.aether_core.MessageKind
import uniffi.aether_core.Precedence
import uniffi.aether_core.PeerCertStatus
import uniffi.aether_core.PeerCertificate
import uniffi.aether_core.burnCodeHash
import uniffi.aether_core.checkPeerCertificate
import uniffi.aether_core.decodeDeviceCertificate
import uniffi.aether_core.encodeHello
import uniffi.aether_core.burnerNumber
import uniffi.aether_core.burnerNumberMatches
import uniffi.aether_core.decodeKeyDrop
import uniffi.aether_core.encodeKeyDrop
import uniffi.aether_core.generateBurnCode
import uniffi.aether_core.suggestCodename
import uniffi.aether_core.verifyBurnCode
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class NearbyPeer(val serviceName: String, val host: String, val port: Int)

enum class LinkState { CONNECTING, ONLINE, OFFLINE }

private enum class Outcome { DONE, REFUSED, HANDSHAKE_FAILED, UNREACHABLE }

/**
 * Device-certificate settings for the real identity: this device's certificate (hex, from
 * `aether-ca sign`), the CA public keys it trusts, and whether peers without a valid certificate
 * are refused. Burner-number sessions never send the certificate, which would unmask the operator.
 */
data class CertSettings(val mine: String = "", val cas: List<String> = emptyList(), val require: Boolean = false)

/**
 * The device's ÆTHER node. It runs one listener per local identity: the operator's real
 * identity on [PORT], and one per active burner number on its own port, each advertised on
 * the local network (mDNS / NSD). Conversations are scoped by identity, so a teammate talking
 * to a burner number never sees the real identity or name. Sessions and keys live in the Rust core.
 */
class AetherNode(private val context: Context, private val store: Store, val profile: Profile) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val burnerStore = BurnerStore(context)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** A local identity this node answers as: the real one ([id] null) or a burner number. */
    private inner class Local(val id: String?, val identity: Identity, val serviceName: String) {
        val keyHex: String = identity.publicKey().toHex()
        val displayName: String get() = if (id == null) profile.displayName else burners.value.firstOrNull { it.id == id }?.label ?: "ÆTHER"
        @Volatile var server: ServerSocket? = null
        @Volatile var registration: NsdManager.RegistrationListener? = null
        @Volatile var port: Int = 0
    }

    private class Live(val link: PeerLink, val local: Local)

    private val main = Local(null, profile.identity, "AETHER-" + profile.identity.publicKey().toHex().take(12))
    private val locals = ConcurrentHashMap<String, Local>()      // burner id -> local
    private val links = ConcurrentHashMap<String, Live>()        // convo -> live link
    private val lastSeen = ConcurrentHashMap<String, String>()   // convo -> host:port
    private val burnFailures = ConcurrentHashMap<String, Int>()
    private var burnHash: ByteArray? = null

    val localKeyHex: String = main.keyHex
    val serviceName: String = main.serviceName

    private val _contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val contacts: StateFlow<Map<String, Contact>> = _contacts.asStateFlow()
    private val _messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    val messages: StateFlow<List<StoredMessage>> = _messages.asStateFlow()
    private val _linkStates = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    val linkStates: StateFlow<Map<String, LinkState>> = _linkStates.asStateFlow()
    private val _nearby = MutableStateFlow<Map<String, NearbyPeer>>(emptyMap())
    val nearby: StateFlow<Map<String, NearbyPeer>> = _nearby.asStateFlow()
    private val certPrefs = context.getSharedPreferences("aether.certs", Context.MODE_PRIVATE)
    private val _certSettings = MutableStateFlow(
        CertSettings(
            certPrefs.getString("mine", "") ?: "",
            (certPrefs.getString("cas", "") ?: "").split(',').filter { it.length == 64 },
            certPrefs.getBoolean("require", false),
        ),
    )
    val certSettings: StateFlow<CertSettings> = _certSettings.asStateFlow()
    /** The certificate each live conversation's peer presented, as checked when it connected. */
    private val _peerCerts = MutableStateFlow<Map<String, PeerCertificate>>(emptyMap())
    val peerCerts: StateFlow<Map<String, PeerCertificate>> = _peerCerts.asStateFlow()

    /** Validates and stores certificate settings; returns a status line for the screen. */
    fun saveCertSettings(mine: String, casText: String, require: Boolean): Result<String> = runCatching {
        val cert = mine.trim().lowercase()
        val cas = casText.split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        require(cas.all { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }) { "Each CA key is 64 hex characters" }
        var line = "No certificate: peers see this device as uncertified."
        if (cert.isNotEmpty()) {
            require(cert.length % 2 == 0 && cert.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "The certificate must be hex" }
            val info = runCatching { decodeDeviceCertificate(cert.hexToBytes()) }.getOrElse { error("That is not an ÆTHER device certificate") }
            require(info.devicePublicKey.toHex() == main.keyHex) { "That certificate is for a different key, not this device's" }
            val own = checkPeerCertificate(cert.hexToBytes(), cas.map { it.hexToBytes() }, info.devicePublicKey, System.currentTimeMillis().toULong())
            line = when (own.status) {
                PeerCertStatus.VALID -> "Certified as ${info.label} (${info.profile.name.lowercase()})."
                PeerCertStatus.UNTRUSTED -> "Certificate for ${info.label}; add its CA key to check it here."
                else -> error("This certificate is not usable: ${own.reason}")
            }
        }
        certPrefs.edit().putString("mine", cert).putString("cas", cas.joinToString(",")).putBoolean("require", require).apply()
        _certSettings.value = CertSettings(cert, cas, require)
        line
    }

    private fun checkCert(hello: uniffi.aether_core.Hello, key: ByteArray): PeerCertificate =
        checkPeerCertificate(hello.certificate, _certSettings.value.cas.map { it.hexToBytes() }, key, System.currentTimeMillis().toULong())

    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    private val _burners = MutableStateFlow<List<Burner>>(emptyList())
    val burners: StateFlow<List<Burner>> = _burners.asStateFlow()
    private val _bundles = MutableStateFlow<List<Bundle>>(emptyList())
    val bundles: StateFlow<List<Bundle>> = _bundles.asStateFlow()
    private val _burnCodeSet = MutableStateFlow(false)
    val burnCodeSet: StateFlow<Boolean> = _burnCodeSet.asStateFlow()

    fun start() {
        val replay = store.loadLogFull()
        val c = replay.contacts
        val m = replay.messages
        val (bundles, hash) = store.loadNumbersLog()
        _bundles.value = bundles
        burnHash = hash
        _burnCodeSet.value = hash != null
        val allContacts = LinkedHashMap(c)
        val allMessages = ArrayList(m)
        val live = ArrayList<Burner>()
        for ((b, bc, bm) in burnerStore.loadAll()) {
            live += b; allContacts.putAll(bc); allMessages += bm
        }
        _contacts.value = allContacts
        val now = System.currentTimeMillis()
        val kept = allMessages.filter { !it.expired(now) }
        _messages.value = kept.sortedBy { it.sentAtMs }
        // Expired messages leave the disk at once; a log full of superseded edits is folded too.
        if (kept.size != allMessages.size || replay.superseded > 200) scheduleCompact()
        scope.launch { while (isActive) { delay(PURGE_EVERY_MS); purgeExpired() } }
        // Rebuild the map picture and the field and eidetic inboxes from received history.
        for (msg in _messages.value) {
            if (msg.outgoing) continue
            val convo = msg.convo
            when (msg.kind) {
                StoredMessage.KIND_POSITION -> runCatching { decodePosition(msg.body) }.onSuccess { notePosition(convo, it) }
                StoredMessage.KIND_FIELD -> runCatching { decodeFieldMessage(msg.body) }.onSuccess { f -> _fieldInbox.update { it + (convo to f) } }
                StoredMessage.KIND_EIDETIC -> runCatching { decodeEideticMessage(msg.body) }.onSuccess { e -> _eideticInbox.update { it + (convo to e) } }
            }
        }
        _burners.value = live
        scope.launch { listen(main, PORT) }
        live.forEach(::startBurner)
        startDiscovery()
    }

    fun stop() {
        (listOf(main) + locals.values).forEach(::stopLocal)
        runCatching { discovery?.let(nsd::stopServiceDiscovery) }
        runCatching { multicastLock?.release() }
        links.values.forEach { it.link.close() }
        links.clear()
    }

    fun clearEvent() { _events.value = null }

    fun localAddresses(port: Int = PORT): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "${it.hostAddress}:$port" }
    }.getOrDefault(emptyList())

    private fun localFor(via: String?): Local? = if (via == null) main else locals[via]

    // ---- listeners ---------------------------------------------------------

    private fun listen(local: Local, port: Int) {
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        } catch (e: Exception) {
            Log.e(TAG, "listen failed", e); _events.value = "Could not listen on port $port"; return
        }
        local.server = s
        local.port = s.localPort
        register(local)
        while (scope.isActive && !s.isClosed) {
            val sock = try { s.accept() } catch (e: Exception) { break }
            scope.launch { adopt(sock, initiator = false, local = local, seenAt = "${sock.inetAddress.hostAddress}:$PORT") }
        }
    }

    private fun stopLocal(local: Local) {
        runCatching { local.registration?.let(nsd::unregisterService) }
        runCatching { local.server?.close() }
    }

    // ---- connections -------------------------------------------------------

    fun connect(host: String, port: Int = PORT) {
        scope.launch { dial(main, host, port) }
    }

    /**
     * Dials [host]:[port] as [local]; returns once the session ends. False if unreachable or the
     * peer's key fails [expectKey] (a pinned key) or [expectNumber] (a burner number's key).
     */
    private fun dial(local: Local, host: String, port: Int, expectKey: String? = null, expectNumber: String? = null): Boolean {
        // Post-quantum first; classical only for a peer that has never completed it with us, so a
        // broken first attempt can't strip ML-KEM from a known post-quantum contact.
        val first = dialOnce(local, host, port, expectKey, expectNumber, classic = false)
        if (first != Outcome.HANDSHAKE_FAILED) return first == Outcome.DONE
        if (expectKey != null && _contacts.value[convoKey(local.id, expectKey)]?.pq == true) {
            _events.value = "Post-quantum handshake failed at $host:$port; classical fallback refused for this contact"
            return false
        }
        Log.i(TAG, "post-quantum handshake failed at $host:$port; retrying classical")
        return dialOnce(local, host, port, expectKey, expectNumber, classic = true) == Outcome.DONE
    }

    private fun dialOnce(local: Local, host: String, port: Int, expectKey: String?, expectNumber: String?, classic: Boolean): Outcome {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "connect $host:$port failed", e)
            runCatching { sock.close() }
            _events.value = "Could not reach $host:$port"
            return Outcome.UNREACHABLE
        }
        return adopt(sock, initiator = true, local = local, seenAt = "$host:$port", expectKey = expectKey, expectNumber = expectNumber, classic = classic)
    }

    /** Dials a pinned contact at its dropped / last-seen addresses, accepting only its pinned key. */
    fun connectPinned(convo: String) {
        val c = _contacts.value[convo] ?: return
        val local = localFor(c.via) ?: return
        val targets = (listOfNotNull(lastSeen[convo]) + c.addresses).distinct()
        if (targets.isEmpty()) { _events.value = "No known address for ${c.codename ?: c.name}"; return }
        setState(convo, LinkState.CONNECTING)
        scope.launch {
            for (t in targets) {
                val host = t.substringBeforeLast(":")
                val port = t.substringAfterLast(":").toIntOrNull() ?: PORT
                if (dial(local, host, port, expectKey = c.keyHex)) return@launch
            }
            if (!links.containsKey(convo)) setState(convo, LinkState.OFFLINE)
        }
    }

    /**
     * Calls a burner [number] (10 digits) from burner [fromId], or from the real identity when
     * null. Found on the local network by its advert, or at [address] (`host:port`). The session
     * is accepted only if the peer proves the key the number was derived from.
     */
    fun dialNumber(number: String, fromId: String?, address: String? = null): Result<Unit> = runCatching {
        val local = localFor(fromId) ?: error("That number was burned")
        if (locals.values.any { burnerNumber(it.identity.publicKey()) == number }) error("That's one of your own numbers")
        val target = address?.takeIf { it.isNotBlank() }?.let { it.substringBeforeLast(":") to (it.substringAfterLast(":").toIntOrNull() ?: PORT) }
            ?: _nearby.value[numberService(number)]?.let { it.host to it.port }
            ?: error("${formatNumber(number)} isn't on this network. Add its address.")
        scope.launch { dial(local, target.first, target.second, expectNumber = number) }
    }

    private fun adopt(
        sock: Socket,
        initiator: Boolean,
        local: Local,
        seenAt: String,
        expectKey: String? = null,
        expectNumber: String? = null,
        classic: Boolean = false,
    ): Outcome {
        val knownPq = { k: ByteArray -> _contacts.value[convoKey(local.id, k.toHex())]?.pq == true }
        val expected: ((ByteArray) -> Boolean)? = when {
            expectKey != null -> { k -> k.toHex() == expectKey }
            expectNumber != null -> { k -> burnerNumberMatches(expectNumber, k) }
            else -> null
        }
        // Certificates belong to the real identity only: a burner session sends none and requires none.
        val cfg = _certSettings.value
        val requireCert = cfg.require && local.id == null
        val hello = encodeHello(local.displayName, if (local.id == null && cfg.mine.isNotEmpty()) cfg.mine.hexToBytes() else null)
        var certRefusal: PeerCertificate? = null
        // Checked after message 2: a classical dial also hangs up on a contact known to speak
        // post-quantum, and a dial that requires certificates hangs up on an uncertified peer.
        val accept: (ByteArray, uniffi.aether_core.Hello) -> Boolean = { k, h ->
            (expected?.invoke(k) ?: true) && !(classic && knownPq(k)) &&
                (!requireCert || checkCert(h, k).also { if (it.status != PeerCertStatus.VALID) certRefusal = it }.status == PeerCertStatus.VALID)
        }
        val link = try {
            PeerLink.establish(sock, local.identity, hello, initiator, accept, classic)
        } catch (e: RemoteRejectedException) {
            runCatching { sock.close() }
            _events.value = if (certRefusal != null) {
                "Refused $seenAt: no valid certificate (${certRefusal!!.reason})"
            } else if (expectNumber != null) {
                "Number mismatch at $seenAt: the peer doesn't hold ${formatNumber(expectNumber)}"
            } else {
                val pin = expectKey?.let { _contacts.value[convoKey(local.id, it)] }
                "Key mismatch at $seenAt: not ${pin?.codename ?: pin?.name ?: "the pinned operator"}"
            }
            return Outcome.REFUSED
        } catch (e: Exception) {
            Log.w(TAG, "handshake failed", e)
            _events.value = "Secure handshake failed"
            return Outcome.HANDSHAKE_FAILED
        }
        val key = link.remoteKey.toHex()
        val convo = convoKey(local.id, key)
        if (isLocalKey(key)) { link.close(); return Outcome.REFUSED }
        if (expectKey != null && key != expectKey) {
            link.close()
            val pin = _contacts.value[convoKey(local.id, expectKey)]
            _events.value = "Key mismatch at $seenAt: not ${pin?.codename ?: pin?.name ?: "the pinned operator"}"
            return Outcome.REFUSED
        }
        if (expectNumber != null && !burnerNumberMatches(expectNumber, link.remoteKey)) {
            link.close()
            _events.value = "Number mismatch at $seenAt: the peer doesn't hold ${formatNumber(expectNumber)}"
            return Outcome.REFUSED
        }
        if (!link.postQuantum && knownPq(link.remoteKey)) {
            link.close()
            _events.value = "Refused a classical session at $seenAt: this contact speaks post-quantum"
            return Outcome.REFUSED
        }
        val peerCert = checkCert(link.peerHello, link.remoteKey)
        if (requireCert && peerCert.status != PeerCertStatus.VALID) {
            // Responder side (the dialer side already hung up before message 3).
            link.close()
            _events.value = "Refused ${link.peerName} at $seenAt: no valid certificate (${peerCert.reason})"
            return Outcome.REFUSED
        }
        _peerCerts.update { it + (convo to peerCert) }
        lastSeen[convo] = seenAt
        links.put(convo, Live(link, local))?.link?.close()
        val known = _contacts.value[convo]
        if (known == null || known.name != link.peerName || (link.postQuantum && !known.pq)) {
            val c = known?.copy(name = link.peerName, pq = known.pq || link.postQuantum)
                ?: Contact(key, link.peerName, verified = false, via = local.id, pq = link.postQuantum)
            persist(c)
            _contacts.update { it + (convo to c) }
        }
        setState(convo, LinkState.ONLINE)
        flushPending(convo)
        _events.value = "Secure session with ${link.peerName}" + (local.id?.let { " via ${formatNumber(numberOf(it))}" } ?: "")
        try {
            while (true) {
                val msg = link.receive()
                when (msg.kind) {
                    MessageKind.TEXT -> record(StoredMessage(key, false, msg.sentAtMs.toLong(), msg.body, via = local.id))
                    MessageKind.KEY_DROP -> if (runCatching { decodeKeyDrop(msg.body) }.isSuccess) {
                        record(StoredMessage(key, false, msg.sentAtMs.toLong(), msg.body, drop = true, via = local.id))
                    } else {
                        Log.w(TAG, "ignored malformed key drop")
                    }
                    MessageKind.BURN -> if (!onRemoteBurn(convo, msg.body)) break
                    MessageKind.POSITION -> runCatching { decodePosition(msg.body) }.onSuccess { r ->
                        record(StoredMessage(key, false, msg.sentAtMs.toLong(), msg.body, via = local.id, kind = StoredMessage.KIND_POSITION))
                        notePosition(convo, r)
                    }.onFailure { Log.w(TAG, "ignored malformed position") }
                    MessageKind.FIELD -> runCatching { decodeFieldMessage(msg.body) }.onSuccess { f ->
                        record(StoredMessage(key, false, msg.sentAtMs.toLong(), msg.body, via = local.id, kind = StoredMessage.KIND_FIELD))
                        _fieldInbox.update { (it + (convo to f)).takeLast(500) }
                    }.onFailure { Log.w(TAG, "ignored malformed field message") }
                    MessageKind.EIDETIC -> runCatching { decodeEideticMessage(msg.body) }.onSuccess { e ->
                        record(StoredMessage(key, false, msg.sentAtMs.toLong(), msg.body, via = local.id, kind = StoredMessage.KIND_EIDETIC))
                        _eideticInbox.update { (it + (convo to e)).takeLast(500) }
                    }.onFailure { Log.w(TAG, "ignored malformed eidetic message") }
                    MessageKind.SOCIAL -> onSocial(local, key, convo, msg)
                    MessageKind.RECEIPT -> onReceipt(local, key, convo, msg.body)
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "link ended: ${e.message}")
        } finally {
            links.remove(convo)?.let { if (it.link === link) setState(convo, LinkState.OFFLINE) else links[convo] = it }
            clearTyping(key)
            link.close()
        }
        return Outcome.DONE
    }

    // ---- ÆTHER Maps™ and ÆTHER Field™ over Talk ---------------------------------------

    private val _teamPositions = MutableStateFlow<Map<String, PositionReport>>(emptyMap())
    /** Latest own-position report from each conversation's peer (convo → report). */
    val teamPositions: StateFlow<Map<String, PositionReport>> = _teamPositions.asStateFlow()
    private val _markers = MutableStateFlow<List<Pair<String, PositionReport>>>(emptyList())
    /** Markers teammates have shared (convo → marker), newest last. */
    val markers: StateFlow<List<Pair<String, PositionReport>>> = _markers.asStateFlow()
    private val _fieldInbox = MutableStateFlow<List<Pair<String, FieldMessage>>>(emptyList())
    /** Field packets and mission updates received from teammates (convo → message), newest last. */
    val fieldInbox: StateFlow<List<Pair<String, FieldMessage>>> = _fieldInbox.asStateFlow()
    private val _eideticInbox = MutableStateFlow<List<Pair<String, EideticMessage>>>(emptyList())
    /**
     * Eidetic MIL review requests and decisions received from teammates (convo → message), newest
     * last. The core decoder has already refused anything carrying a template or vector.
     */
    val eideticInbox: StateFlow<List<Pair<String, EideticMessage>>> = _eideticInbox.asStateFlow()

    private fun notePosition(convo: String, r: PositionReport) {
        if (r.kind == PositionKind.OWN_FIX) _teamPositions.update { it + (convo to r) }
        else _markers.update { (it + (convo to r)).takeLast(500) }
    }

    /** Shares a position or marker with the peer of [convo], end-to-end encrypted. */
    suspend fun sendPosition(convo: String, report: PositionReport): Result<Unit> = sendEncoded(
        convo, MessageKind.POSITION, StoredMessage.KIND_POSITION,
    ) { encodePosition(report) }

    /** Sends a field packet or mission update to the peer of [convo], end-to-end encrypted. */
    suspend fun sendField(convo: String, message: FieldMessage): Result<Unit> = sendEncoded(
        convo, MessageKind.FIELD, StoredMessage.KIND_FIELD,
    ) { encodeFieldMessage(message) }

    /** Sends an eidetic review request or decision to the peer of [convo], end-to-end encrypted. */
    suspend fun sendEidetic(convo: String, message: EideticMessage): Result<Unit> = sendEncoded(
        convo, MessageKind.EIDETIC, StoredMessage.KIND_EIDETIC,
    ) { encodeEideticMessage(message) }

    private suspend fun sendEncoded(convo: String, kind: MessageKind, tag: String, encode: () -> String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val live = links[convo] ?: error("Peer is offline")
                val body = encode()
                val now = System.currentTimeMillis()
                live.link.send(ChatMessage(kind, now.toULong(), body))
                record(StoredMessage(live.link.remoteKey.toHex(), true, now, body, via = live.local.id, kind = tag))
            }.onFailure { Log.w(TAG, "send $tag failed", it) }
        }

    /** Conversations with a live session, for "share to" pickers. */
    fun onlineConvos(): List<String> = links.keys.toList()

    /** Sends [text] to [convo] as a Talk message (a social Message with an id). */
    suspend fun send(convo: String, text: String): Boolean = sendText(convo, text).isSuccess

    /**
     * Drops a key card into [convo] under [codename]: the identity this conversation runs as when
     * [subjectConvo] is null, otherwise a known contact's. Encrypted end to end inside the session.
     */
    suspend fun sendDrop(convo: String, codename: String, subjectConvo: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val live = links[convo] ?: error("Peer is offline")
                val local = live.local
                val drop = if (subjectConvo == null) {
                    KeyDrop(codename, local.displayName, local.identity.publicKey(), localAddresses(local.port).take(4))
                } else {
                    val c = _contacts.value[subjectConvo] ?: error("Unknown operator")
                    val addrs = (listOfNotNull(lastSeen[subjectConvo]) + c.addresses).distinct().take(4)
                    KeyDrop(codename, c.name, c.keyHex.hexToBytes(), addrs)
                }
                val body = encodeKeyDrop(drop)
                val now = System.currentTimeMillis()
                live.link.send(ChatMessage(MessageKind.KEY_DROP, now.toULong(), body))
                record(StoredMessage(live.link.remoteKey.toHex(), true, now, body, drop = true, via = local.id))
            }.onFailure { Log.w(TAG, "drop failed", it) }
        }

    /** Pins a received drop's key as a contact, in the same identity scope as [convo]. */
    fun pinDrop(d: KeyDrop, convo: String): String? {
        val key = d.publicKey.toHex()
        if (isLocalKey(key)) return null
        val via = _contacts.value[convo]?.via
        val pinConvo = convoKey(via, key)
        val existing = _contacts.value[pinConvo]
        val c = (existing ?: Contact(key, d.name, verified = false, via = via)).copy(
            pinned = true,
            codename = d.codename,
            addresses = (d.addresses + (existing?.addresses ?: emptyList())).distinct().take(8),
        )
        persist(c)
        _contacts.update { it + (pinConvo to c) }
        _events.value = "Pinned ${d.codename}"
        return pinConvo
    }

    /**
     * This operator's real-identity key card as text, for a QR code shown face to face. It holds
     * only the public key, a codename, the display name and local addresses: no phone number.
     */
    fun myKeyCard(): String = encodeKeyDrop(
        KeyDrop(
            profile.displayName.take(32).ifBlank { "Operator" },
            profile.displayName.take(64).ifBlank { "Operator" },
            main.identity.publicKey(),
            localAddresses().take(4),
        ),
    )

    /**
     * Pins the operator on a scanned QR key card (real identity scope) and dials it. The session is
     * accepted only if the peer proves the scanned key. Returns the conversation.
     */
    fun pinScanned(text: String): Result<String> = runCatching {
        val d = decodeKeyDrop(text.trim())
        val convo = pinDrop(d, "") ?: error("That is your own key card")
        connectPinned(convo)
        convo
    }.onFailure { _events.value = it.message?.takeIf { m -> m.contains("own key") } ?: "Not an ÆTHER key card" }

    fun isLocalKey(keyHex: String) = keyHex == main.keyHex || locals.values.any { it.keyHex == keyHex }

    fun safetyNumber(convo: String): String? = links[convo]?.let { it.link.session.safetyNumber(it.local.identity) }

    fun markVerified(convo: String) {
        val c = _contacts.value[convo] ?: return
        val v = c.copy(verified = true)
        persist(v)
        _contacts.update { it + (convo to v) }
    }

    fun redial(convo: String) {
        val c = _contacts.value[convo] ?: return
        val local = localFor(c.via) ?: return
        val peer = _nearby.value.values.firstOrNull { it.serviceName == "AETHER-" + c.keyHex.take(12) }
            ?: _nearby.value[numberService(burnerNumber(c.keyHex.hexToBytes()))]
            ?: return connectPinned(convo)
        setState(convo, LinkState.CONNECTING)
        scope.launch { dial(local, peer.host, peer.port, expectKey = c.keyHex) }
    }

    // ---- ÆTHER Talk™ messenger (social events) -----------------------------------------
    //
    // Every Talk feature travels as a core SocialEvent inside the encrypted session
    // (MessageKind.SOCIAL). Groups are fanned out pairwise: the same event is encrypted to each
    // member over that member's own session, and queued for members who are offline.
    // Nothing here logs message text.

    /** Chat settings (pin, mute, archive, timer, read marker), groups and the group outbox. */
    val chats = ChatStore(store)
    private val msgLock = Any()
    private val _typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    /** convo → (typing sender's key → typing until, ms). Group chats are keyed by their group convo. */
    val typing: StateFlow<Map<String, Map<String, Long>>> = _typing.asStateFlow()
    private val _incomingFiles = MutableSharedFlow<Pair<String, SocialEvent>>(extraBufferCapacity = 1024)
    /** File ids whose messages were wiped (conversation, file id): the media store erases them. */
    private val _wipedFiles = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1024)
    val wipedFiles: SharedFlow<Pair<String, String>> = _wipedFiles.asSharedFlow()
    /**
     * Received FileOffer / FileChunk events, untouched, as (pairwise convo → event); the event's
     * `groupId` names the group when it was sent to one. For the media code to consume.
     */
    val incomingFiles: SharedFlow<Pair<String, SocialEvent>> = _incomingFiles.asSharedFlow()
    private val lastReadSent = ConcurrentHashMap<String, String>()
    private val lastTypingSent = ConcurrentHashMap<String, Long>()
    @Volatile private var compactPending = false

    private fun pairScope(convo: String): Pair<String?, String> =
        if (convo.startsWith("b:")) convo.split(":", limit = 3).let { it[1] to it[2] } else null to convo

    fun groupOf(convo: String): ChatGroup? = if (isGroupConvo(convo)) chats.groups.value[convo.removePrefix("g:")] else null

    /** This device's own identity key (hex) in [convo]: the real identity's, or the burner's it runs under. */
    fun ownKeyFor(convo: String): String {
        val via = groupOf(convo)?.via ?: if (isGroupConvo(convo)) null else pairScope(convo).first
        return localFor(via)?.keyHex ?: main.keyHex
    }

    /** Display name for [key] in the scope [via]: "You", a contact's name, or a short key. */
    fun nameFor(via: String?, key: String): String = when {
        key == (localFor(via)?.keyHex ?: main.keyHex) -> "You"
        else -> _contacts.value[convoKey(via, key)]?.let { it.codename ?: it.name } ?: ("Operator " + key.take(8))
    }

    fun isOnline(convo: String) = links.containsKey(convo)

    /**
     * Sends a social event to [convo] (a contact, or a group: fanned out to every member, queued
     * for those offline), then applies its effect here (records a Message, applies an Edit, …).
     * FileOffer / FileChunk are only sent. Typing and Read are never queued.
     */
    suspend fun sendSocial(convo: String, event: SocialEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val g = groupOf(convo)
            if (g != null) {
                check(!g.left) { "You are no longer in this group" }
                val e = event.copy(groupId = g.id)
                val body = encodeSocial(e)
                fanOut(g, g.members, body, now, queue = e.kind != SocialKind.TYPING && e.kind != SocialKind.READ)
                applyOwn(convo, e, now)
            } else {
                check(!isGroupConvo(convo)) { "Unknown group" }
                val live = links[convo] ?: error("Peer is offline")
                val body = encodeSocial(event)
                live.link.send(ChatMessage(MessageKind.SOCIAL, now.toULong(), body))
                applyOwn(convo, event, now)
            }
        }.onFailure { Log.w(TAG, "send social ${event.kind} failed: ${it.javaClass.simpleName}") }
    }

    private fun fanOut(g: ChatGroup, targets: Collection<String>, body: String, now: Long, queue: Boolean) {
        val me = localFor(g.via)?.keyHex ?: return
        for (m in targets.distinct()) {
            if (m == me) continue
            val c = convoKey(g.via, m)
            val live = links[c]
            val sent = live != null && runCatching { live.link.send(ChatMessage(MessageKind.SOCIAL, now.toULong(), body)) }.isSuccess
            if (!sent && queue) chats.enqueue(c, body, now)
        }
    }

    /** Sends queued group events to [convo]'s peer once its link is up. */
    private fun flushPending(convo: String) {
        val live = links[convo] ?: return
        for (p in chats.pendingFor(convo)) {
            if (runCatching { live.link.send(ChatMessage(MessageKind.SOCIAL, p.sentAtMs.toULong(), p.body)) }.isFailure) return
            chats.done(p.qid)
        }
    }

    /** Local effect of an event this device sent. */
    private fun applyOwn(convo: String, e: SocialEvent, now: Long) {
        val g = groupOf(convo)
        val (pairVia, peer) = if (g == null) pairScope(convo) else g.via to ownKeyFor(convo)
        val via = g?.via ?: pairVia
        when (e.kind) {
            SocialKind.MESSAGE -> record(
                StoredMessage(
                    peer, true, now, e.text, via = via, id = e.id, replyTo = e.replyTo.ifEmpty { null },
                    forwarded = e.forwarded, expiresAt = expiry(now, e.expiresS), groupId = g?.id,
                    fileId = e.target.ifEmpty { null }, precedence = e.precedence,
                ),
            )
            SocialKind.EDIT -> mutate(convo, e.target) { if (it.outgoing && !it.deleted) it.copy(body = e.text, edited = true) else it }
            SocialKind.DELETE -> mutate(convo, e.target) { if (it.outgoing) erased(it) else it }?.also { scheduleCompact() }
            SocialKind.REACT -> mutate(convo, e.target) { react(it, e.text, ownKeyFor(convo), remove = e.flag) }
            SocialKind.TIMER -> {
                chats.update(convo) { it.copy(timerS = e.expiresS.toInt()) }
                system(convo, via, peer, now, "You set disappearing messages to ${timerLabel(e.expiresS.toInt()).lowercase()}")
            }
            SocialKind.PIN -> {
                applyPin(convo, e.target, unpin = e.flag)
                system(convo, via, peer, now, if (e.flag) "You unpinned a message" else "You pinned a message")
            }
            SocialKind.WIPE -> {
                wipeLocal(convo)
                system(convo, via, peer, now, "You wiped this chat on every device")
            }
            else -> Unit
        }
    }

    private fun applyPin(convo: String, id: String, unpin: Boolean) =
        chats.update(convo) { s -> s.copy(pins = (s.pins - id).let { if (unpin) it else (it + id).takeLast(MAX_PINS) }) }

    /** Erases every message of [convo] on this device (and their files and pins), leaving nothing on disk. */
    private fun wipeLocal(convo: String) {
        val gone = synchronized(msgLock) {
            val mine = _messages.value.filter { it.convo == convo }
            for (m in mine) if (m.id != null) {
                val rec = EventLog.goneJson(convo, m.id)
                if (m.via == null) store.saveGone(convo, m.id) else if (locals.containsKey(m.via)) burnerStore.append(m.via, rec)
            }
            _messages.update { l -> l.filter { it.convo != convo } }
            mine
        }
        gone.mapNotNull { it.fileId }.forEach { _wipedFiles.tryEmit(convo to it) }
        chats.update(convo) { it.copy(pins = emptyList()) }
        clearTypingIn(convo)
        scheduleCompact()
    }

    private fun clearTypingIn(convo: String) = _typing.update { it - convo }

    private fun expiry(now: Long, s: UInt) = if (s == 0u) 0L else now + s.toLong() * 1000

    private fun erased(m: StoredMessage) = m.copy(body = "", deleted = true, reactions = emptyMap(), replyTo = null, edited = false, fileId = null)

    private fun react(m: StoredMessage, emoji: String, who: String, remove: Boolean): StoredMessage {
        if (m.deleted) return m
        val set = m.reactions[emoji].orEmpty()
        val next = if (remove) set - who else set + who
        return m.copy(reactions = (m.reactions - emoji).let { if (next.isEmpty()) it else it + (emoji to next) })
    }

    /** Adds a system line ("X set disappearing messages to 1 hour") to [convo]. */
    private fun system(convo: String, via: String?, peer: String, now: Long, text: String) {
        val g = groupOf(convo)
        record(StoredMessage(peer, false, now, text, via = via, kind = StoredMessage.KIND_SYSTEM, id = randomId(), groupId = g?.id))
    }

    /** Replaces message [id] of [convo] with [f] of it, persisting the new state. */
    private fun mutate(convo: String, id: String, f: (StoredMessage) -> StoredMessage): StoredMessage? = synchronized(msgLock) {
        val cur = _messages.value.lastOrNull { it.id == id && it.convo == convo } ?: return null
        val next = f(cur)
        if (next == cur) return cur
        if (!persistMessage(next)) return null
        _messages.update { l -> l.map { if (it === cur) next else it } }
        next
    }

    // ---- receiving --------------------------------------------------------------------

    private fun onSocial(local: Local, key: String, pairConvo: String, msg: ChatMessage) {
        val e = runCatching { decodeSocial(msg.body) }.getOrElse { Log.w(TAG, "ignored malformed social event"); return }
        val now = System.currentTimeMillis()
        val g = e.groupId.takeIf { it.isNotEmpty() }?.let { chats.groups.value[it] }
        if (e.groupId.isNotEmpty() && e.kind == SocialKind.GROUP) { onGroupEvent(local, key, e, now); return }
        // Group events from anyone who isn't a current member (or for a group we left) are ignored.
        if (e.groupId.isNotEmpty() && (g == null || g.via != local.id || g.left || key !in g.members)) return
        if (e.kind == SocialKind.FILE_OFFER || e.kind == SocialKind.FILE_CHUNK) { _incomingFiles.tryEmit(pairConvo to e); return }
        if (g != null && e.kind == SocialKind.LEAVE) { onLeave(g, key, now); return }
        val convo = g?.convo ?: pairConvo
        val admin = g == null || g.isAdmin(key)
        when (e.kind) {
            SocialKind.MESSAGE -> {
                if (g != null && g.channel && !admin) return // only admins post in a unit channel
                val dup = _messages.value.any { it.id == e.id && it.convo == convo }
                if (!dup) {
                    val m = StoredMessage(
                        key, false, msg.sentAtMs.toLong(), e.text, via = local.id, id = e.id,
                        replyTo = e.replyTo.ifEmpty { null }, forwarded = e.forwarded,
                        expiresAt = expiry(now, e.expiresS), groupId = g?.id, fileId = e.target.ifEmpty { null },
                        precedence = e.precedence,
                    )
                    record(m)
                    if (m.precedence == Precedence.IMMEDIATE || m.precedence == Precedence.FLASH) {
                        PrecedenceAlerts.post(context, convo, g?.name ?: nameFor(local.id, key), nameFor(local.id, key), m)
                    }
                }
                sendReceipt(pairConvo, msg.sentAtMs)
                setTypingState(convo, key, false)
            }
            SocialKind.EDIT -> mutate(convo, e.target) { if (!it.outgoing && it.peerKeyHex == key && !it.deleted) it.copy(body = e.text, edited = true) else it }
            SocialKind.DELETE -> mutate(convo, e.target) { if (!it.outgoing && it.peerKeyHex == key) erased(it) else it }?.also { scheduleCompact() }
            SocialKind.REACT -> mutate(convo, e.target) { react(it, e.text, key, remove = e.flag) }
            SocialKind.READ -> markReadUpTo(convo, e.target)
            SocialKind.TYPING -> setTypingState(convo, key, e.flag)
            SocialKind.TIMER -> {
                chats.update(convo) { it.copy(timerS = e.expiresS.toInt()) }
                system(convo, local.id, key, now, "${nameFor(local.id, key)} set disappearing messages to ${timerLabel(e.expiresS.toInt()).lowercase()}")
            }
            SocialKind.PIN -> {
                if (!admin) return
                applyPin(convo, e.target, unpin = e.flag)
                system(convo, local.id, key, now, "${nameFor(local.id, key)} ${if (e.flag) "unpinned" else "pinned"} a message")
            }
            SocialKind.WIPE -> {
                if (!admin) return
                wipeLocal(convo)
                system(convo, local.id, key, now, "${nameFor(local.id, key)} wiped this chat on every device")
            }
            else -> Unit
        }
    }

    private fun sendReceipt(pairConvo: String, sentAtMs: ULong) {
        val live = links[pairConvo] ?: return
        runCatching { live.link.send(ChatMessage(MessageKind.RECEIPT, System.currentTimeMillis().toULong(), sentAtMs.toString())) }
    }

    /** A delivery receipt: the peer has the message we sent at the timestamp in [body]. */
    private fun onReceipt(local: Local, key: String, pairConvo: String, body: String) {
        val ts = body.trim().toLongOrNull() ?: return
        val hits = _messages.value.filter { m ->
            m.outgoing && m.sentAtMs == ts && m.id != null && m.delivery == StoredMessage.DELIVERY_SENT &&
                (m.convo == pairConvo || (m.groupId != null && chats.groups.value[m.groupId]?.let { it.via == local.id && key in it.members } == true))
        }
        hits.forEach { h -> mutate(h.convo, h.id!!) { it.copy(delivery = maxOf(it.delivery, StoredMessage.DELIVERY_DELIVERED)) } }
    }

    /** The peer read everything up to our message [targetId]. */
    private fun markReadUpTo(convo: String, targetId: String) {
        val t = _messages.value.lastOrNull { it.convo == convo && it.id == targetId } ?: return
        _messages.value.filter { it.convo == convo && it.outgoing && it.id != null && it.sentAtMs <= t.sentAtMs && it.delivery < StoredMessage.DELIVERY_READ }
            .forEach { m -> mutate(convo, m.id!!) { it.copy(delivery = StoredMessage.DELIVERY_READ) } }
    }

    private fun setTypingState(convo: String, key: String, on: Boolean) = _typing.update { all ->
        val cur = all[convo].orEmpty()
        val next = if (on) cur + (key to System.currentTimeMillis() + TYPING_TTL_MS) else cur - key
        if (next.isEmpty()) all - convo else all + (convo to next)
    }

    private fun clearTyping(key: String) = _typing.update { all ->
        all.mapValues { it.value - key }.filterValues { it.isNotEmpty() }
    }

    // ---- actions (called from the chat screen) ------------------------------------------

    /** Sends [text] as a new message, replying to [replyTo] when set; uses the chat's disappearing timer. */
    suspend fun sendText(
        convo: String,
        text: String,
        replyTo: String? = null,
        forwarded: Boolean = false,
        precedence: Precedence = Precedence.ROUTINE,
    ): Result<Unit> {
        val e = socialEvent(SocialKind.MESSAGE).apply {
            id = randomId()
            this.text = text
            this.replyTo = replyTo ?: ""
            this.forwarded = forwarded
            this.precedence = precedence
            expiresS = chats.get(convo).timerS.toUInt()
        }
        val r = sendSocial(convo, e)
        if (r.isSuccess) setTyping(convo, false)
        return r
    }

    /**
     * Sends a prepared file to [convo]: the offer, every chunk, then the message that shows it
     * (with [caption]). [onChunk] gets the number of chunks sent so far.
     */
    suspend fun sendFile(
        convo: String,
        offer: SocialEvent,
        chunks: Sequence<SocialEvent>,
        caption: String = "",
        onChunk: (Int) -> Unit = {},
        precedence: Precedence = Precedence.ROUTINE,
    ): Result<Unit> {
        sendSocial(convo, offer).onFailure { return Result.failure(it) }
        var n = 0
        for (c in chunks) {
            sendSocial(convo, c).onFailure { return Result.failure(it) }
            onChunk(++n)
        }
        val e = socialEvent(SocialKind.MESSAGE).apply {
            id = randomId()
            text = caption
            target = offer.id
            this.precedence = precedence
            expiresS = chats.get(convo).timerS.toUInt()
        }
        return sendSocial(convo, e)
    }

    suspend fun editMessage(convo: String, id: String, text: String): Result<Unit> =
        sendSocial(convo, socialEvent(SocialKind.EDIT).apply { target = id; this.text = text })

    suspend fun deleteForEveryone(convo: String, id: String): Result<Unit> =
        sendSocial(convo, socialEvent(SocialKind.DELETE).apply { target = id })

    /** Removes [m] from this device only; its text leaves the disk at the next compaction (seconds). */
    fun deleteForMe(m: StoredMessage) {
        synchronized(msgLock) {
            if (m.id != null) {
                val gone = EventLog.goneJson(m.convo, m.id)
                if (m.via == null) store.saveGone(m.convo, m.id) else if (locals.containsKey(m.via)) burnerStore.append(m.via, gone)
            }
            _messages.update { l -> l.filter { it !== m } }
        }
        scheduleCompact()
    }

    suspend fun toggleReaction(convo: String, id: String, emoji: String): Result<Unit> {
        val m = _messages.value.lastOrNull { it.convo == convo && it.id == id } ?: return Result.failure(IllegalStateException("Message not found"))
        val mine = ownKeyFor(convo) in m.reactions[emoji].orEmpty()
        return sendSocial(convo, socialEvent(SocialKind.REACT).apply { target = id; text = emoji; flag = mine })
    }

    /** Forwards the text of [m] to [toConvo], marked "Forwarded". */
    suspend fun forward(m: StoredMessage, toConvo: String): Result<Unit> = sendText(toConvo, m.body, forwarded = true)

    /** Admins (anyone in a direct chat): pins [id] at the top of [convo] for everyone, or unpins it. */
    suspend fun togglePin(convo: String, id: String): Result<Unit> {
        if (!isAdminIn(convo)) return Result.failure(IllegalStateException("Only an admin can pin here"))
        val unpin = id in chats.get(convo).pins
        return sendSocial(convo, socialEvent(SocialKind.PIN).apply { target = id; flag = unpin })
    }

    /** Admins (anyone in a direct chat): erases [convo] on every device in it. */
    suspend fun wipeForEveryone(convo: String): Result<Unit> {
        if (!isAdminIn(convo)) return Result.failure(IllegalStateException("Only an admin can wipe this chat"))
        return sendSocial(convo, socialEvent(SocialKind.WIPE))
    }

    fun isAdminIn(convo: String): Boolean = groupOf(convo)?.let { !it.left && it.isAdmin(ownKeyFor(convo)) } ?: true

    /** False in a unit channel for non-admins, and in a group this device left. */
    fun canPostIn(convo: String): Boolean = groupOf(convo)?.let { !it.left && (!it.channel || it.isAdmin(ownKeyFor(convo))) } ?: true

    suspend fun setTimer(convo: String, seconds: Int): Result<Unit> =
        sendSocial(convo, socialEvent(SocialKind.TIMER).apply { expiresS = seconds.toUInt() })

    /** Called while [convo] is on screen: marks it read here and tells the sender(s). */
    suspend fun markRead(convo: String) {
        val newest = _messages.value.lastOrNull { it.convo == convo && !it.outgoing && it.kind == null } ?: return
        chats.update(convo) { if (newest.sentAtMs > it.lastReadMs) it.copy(lastReadMs = newest.sentAtMs) else it }
        val id = newest.id ?: return
        if (lastReadSent[convo] == id) return
        if (groupOf(convo) == null && !isOnline(convo)) return
        if (sendSocial(convo, socialEvent(SocialKind.READ).apply { target = id }).isSuccess) lastReadSent[convo] = id
    }

    /** Typing indicator: true is re-sent at most every few seconds; false is sent once. */
    suspend fun setTyping(convo: String, on: Boolean) {
        val now = System.currentTimeMillis()
        val last = lastTypingSent[convo]
        if (on && last != null && now - last < TYPING_RESEND_MS) return
        if (!on && last == null) return
        if (groupOf(convo)?.left == true || (groupOf(convo) == null && !isOnline(convo))) return
        if (on) lastTypingSent[convo] = now else lastTypingSent.remove(convo)
        sendSocial(convo, socialEvent(SocialKind.TYPING).apply { flag = on })
    }

    // ---- groups -------------------------------------------------------------------------

    /** Creates a group of the real identity with [memberConvos] (contacts) and this device as admin. */
    /** Creates a group (or, with [channel], a unit channel) of the real identity; this device is its admin. */
    suspend fun createGroup(name: String, memberConvos: List<String>, channel: Boolean = false): Result<ChatGroup> = withContext(Dispatchers.IO) {
        runCatching {
            val keys = memberConvos.mapNotNull { _contacts.value[it] }.filter { it.via == null }.map { it.keyHex.lowercase() }
            require(keys.isNotEmpty()) { "Pick at least one contact" }
            val g = ChatGroup(randomId(), name.trim(), (listOf(main.keyHex) + keys).distinct(), listOf(main.keyHex), via = null, channel = channel)
            sendGroupUpdate(g, g.members)
            chats.saveGroup(g)
            system(g.convo, null, main.keyHex, System.currentTimeMillis(), "You created ${if (channel) "the unit channel" else "the group"} “${g.name}”")
            g
        }
    }

    /**
     * Admin only: renames [convo]'s group, sets its members and admins and switches channel mode.
     * This device's own key always stays a member and an admin.
     */
    suspend fun updateGroup(
        convo: String,
        name: String,
        members: List<String>,
        admins: List<String>? = null,
        channel: Boolean? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val g = groupOf(convo) ?: error("Unknown group")
            val me = ownKeyFor(convo)
            check(g.isAdmin(me) && !g.left) { "Only an admin can change this group" }
            val nm = (listOf(me) + members.map { it.lowercase() }).distinct()
            val na = (listOf(me) + (admins ?: g.admins).map { it.lowercase() }).distinct().filter { it in nm }
            val next = g.copy(name = name.trim(), members = nm, admins = na, channel = channel ?: g.channel)
            sendGroupUpdate(next, g.members + next.members)
            chats.saveGroup(next)
            val now = System.currentTimeMillis()
            if (next.name != g.name) system(convo, g.via, me, now, "You renamed it to “${next.name}”")
            (next.members - g.members.toSet()).forEach { system(convo, g.via, me, now, "You added ${nameFor(g.via, it)}") }
            (g.members - next.members.toSet()).forEach { system(convo, g.via, me, now, "You removed ${nameFor(g.via, it)}") }
            (next.admins - g.admins.toSet()).forEach { system(convo, g.via, me, now, "${nameFor(g.via, it)} is now an admin") }
            (g.admins - next.admins.toSet()).filter { it in next.members }.forEach { system(convo, g.via, me, now, "${nameFor(g.via, it)} is no longer an admin") }
            if (next.channel != g.channel) system(convo, g.via, me, now, if (next.channel) "Now a unit channel: only admins post" else "Everyone can post again")
        }
    }

    private fun sendGroupUpdate(g: ChatGroup, targets: Collection<String>) {
        val e = socialEvent(SocialKind.GROUP).apply {
            groupId = g.id; text = g.name; members = g.members; admins = g.admins; channel = g.channel
        }
        fanOut(g, targets, encodeSocial(e), System.currentTimeMillis(), queue = true)
    }

    suspend fun leaveGroup(convo: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val g = groupOf(convo) ?: error("Unknown group")
            if (g.left) return@runCatching
            val e = socialEvent(SocialKind.LEAVE).apply { groupId = g.id }
            fanOut(g, g.members, encodeSocial(e), System.currentTimeMillis(), queue = true)
            chats.saveGroup(g.copy(left = true))
            system(convo, g.via, ownKeyFor(convo), System.currentTimeMillis(), "You left the group")
        }
    }

    private fun onGroupEvent(local: Local, key: String, e: SocialEvent, now: Long) {
        val me = local.keyHex
        val members = e.members.map { it.lowercase() }
        val g = chats.groups.value[e.groupId]
        if (g == null) {
            // A new group: only from a member, and only if it includes us.
            if (key !in members || me !in members) return
            val admins = e.admins.map { it.lowercase() }.filter { it in members }.ifEmpty { listOf(key) }
            val ng = ChatGroup(e.groupId, e.text, members, admins, via = local.id, channel = e.channel)
            chats.saveGroup(ng)
            system(ng.convo, local.id, key, now, "${nameFor(local.id, key)} added you to ${if (ng.channel) "the unit channel" else "the group"} “${ng.name}”")
            return
        }
        if (g.via != local.id || !g.isAdmin(key)) return
        val admins = e.admins.map { it.lowercase() }.filter { it in members }.ifEmpty { listOf(key) }
        if (me !in members) {
            if (!g.left) {
                chats.saveGroup(g.copy(members = members, left = true))
                system(g.convo, g.via, key, now, "${nameFor(g.via, key)} removed you")
            }
            return
        }
        chats.saveGroup(g.copy(name = e.text, members = members, admins = admins, channel = e.channel, left = false))
        val who = nameFor(g.via, key)
        if (g.left) system(g.convo, g.via, key, now, "$who added you back")
        if (g.name != e.text) system(g.convo, g.via, key, now, "$who renamed it to “${e.text}”")
        (members - g.members.toSet()).filter { it != me }.forEach { system(g.convo, g.via, key, now, "$who added ${nameFor(g.via, it)}") }
        (g.members - members.toSet()).forEach { system(g.convo, g.via, key, now, "$who removed ${nameFor(g.via, it)}") }
        (admins - g.admins.toSet()).forEach { system(g.convo, g.via, key, now, "${nameFor(g.via, it)} is now an admin") }
        if (e.channel != g.channel) system(g.convo, g.via, key, now, if (e.channel) "Now a unit channel: only admins post" else "Everyone can post again")
    }

    private fun onLeave(g: ChatGroup, key: String, now: Long) {
        val members = g.members - key
        // The last admin left: the first remaining member takes over (every device applies the same rule).
        val admins = (g.admins - key).ifEmpty { listOfNotNull(members.firstOrNull()) }
        chats.saveGroup(g.copy(members = members, admins = admins))
        system(g.convo, g.via, key, now, "${nameFor(g.via, key)} left")
    }

    // ---- disappearing messages and compaction -------------------------------------------

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(msgLock) {
            if (_messages.value.any { it.expired(now) }) {
                _messages.update { l -> l.filter { !it.expired(now) } }
                changed = true
            }
        }
        if (changed) scheduleCompact()
        _typing.update { all -> all.mapValues { (_, m) -> m.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
    }

    /** Rewrites the message vaults from the live list shortly, so purged and deleted text leaves the disk. */
    private fun scheduleCompact() {
        if (compactPending) return
        compactPending = true
        scope.launch {
            delay(1_500)
            compactPending = false
            runCatching {
                synchronized(msgLock) {
                    val all = _messages.value
                    store.compactLog(all.filter { it.via == null })
                    locals.keys.forEach { id -> burnerStore.compact(id, all.filter { it.via == id }) }
                }
            }.onFailure { Log.w(TAG, "compaction failed: ${it.javaClass.simpleName}") }
        }
    }

    // ---- burner numbers ----------------------------------------------------

    /** Slots in bundles that haven't expired. */
    val totalSlots: Int get() = _bundles.value.filter { it.active() }.sumOf { it.size }

    /** Creates a number in one tap. Fails when every slot in an active bundle is in use. */
    fun createNumber(label: String? = null, scope: ScopeKind = ScopeKind.PERSONAL, scopeName: String? = null, rotatedFrom: String? = null): Result<Burner> = runCatching {
        check(_burners.value.size < totalSlots) { if (totalSlots == 0) "No active number bundle on this device" else "All $totalSlots numbers are in use" }
        val bundle = _bundles.value.filter { it.active() }.firstOrNull { b -> _burners.value.count { it.bundleId == b.id } < b.size }
        val b = burnerStore.create(label?.trim()?.take(32)?.ifEmpty { null } ?: suggestCodename(), scope, scopeName?.trim()?.ifEmpty { null }, rotatedFrom, bundle?.id)
        _burners.update { it + b }
        startBurner(b)
        _events.value = "New number ${formatNumber(b.number)}"
        b
    }

    /** Burns [id] and issues a fresh number with the same label and assignment. */
    fun rotateNumber(id: String): Result<Burner> {
        val old = _burners.value.firstOrNull { it.id == id } ?: return Result.failure(IllegalStateException("Unknown number"))
        burnNumber(id, announce = false)
        return createNumber(old.label, old.scope, old.scopeName, rotatedFrom = old.id)
            .onSuccess { _events.value = "Rotated ${formatNumber(old.number)} → ${formatNumber(it.number)}" }
    }

    fun assignNumber(id: String, label: String, scope: ScopeKind, scopeName: String?) {
        val b = _burners.value.firstOrNull { it.id == id } ?: return
        val u = b.copy(label = label.trim().take(32).ifEmpty { b.label }, scope = scope, scopeName = scopeName?.trim()?.ifEmpty { null })
        burnerStore.saveMeta(u)
        _burners.update { list -> list.map { if (it.id == id) u else it } }
    }

    /** Destroys a number: its key, listener, sessions and conversations. */
    fun burnNumber(id: String, announce: Boolean = true) {
        val b = _burners.value.firstOrNull { it.id == id } ?: return
        locals.remove(id)?.let(::stopLocal)
        links.entries.filter { it.value.local.id == id }.forEach { (k, v) -> links.remove(k); v.link.close(); setState(k, LinkState.OFFLINE) }
        _contacts.update { m -> m.filterValues { it.via != id } }
        _messages.update { l -> l.filter { it.via != id } }
        _burners.update { l -> l.filter { it.id != id } }
        burnerStore.burn(id)
        chats.forgetVia(id)
        if (announce) _events.value = "Burned ${formatNumber(b.number)}"
    }

    fun burnAll() {
        val n = _burners.value.size
        _burners.value.map { it.id }.forEach { burnNumber(it, announce = false) }
        _events.value = "Burned all $n numbers"
    }

    /** Provisions an add-on bundle of 1, 2 or 5 numbers, renewed every [period] ("month" or "year"). */
    fun grantBundle(size: Int, period: String = "month") {
        require(size in BUNDLE_SIZES) { "Bundles hold 1, 2 or 5 numbers" }
        require(period in Bundle.PERIODS) { "Bundles renew monthly or yearly" }
        val now = System.currentTimeMillis()
        val b = Bundle(randomId(), size, period, now, Bundle.periodEnd(now, period))
        store.saveBundle(b)
        _bundles.update { it + b }
    }

    /** Renews a bundle for one more period, from its expiry or from now if it has lapsed. */
    fun renewBundle(id: String) {
        val b = _bundles.value.firstOrNull { it.id == id } ?: return
        val r = b.copy(expiresAt = Bundle.periodEnd(maxOf(b.expiresAt, System.currentTimeMillis()), b.period))
        store.saveBundle(r)
        _bundles.update { l -> l.map { if (it.id == id) r else it } }
    }

    /** Issues a new remote-burn code, replacing any earlier one. Shown once; only its hash is kept. */
    fun newBurnCode(): String {
        val code = generateBurnCode()
        val h = burnCodeHash(code)
        store.saveBurnCodeHash(h)
        burnHash = h
        _burnCodeSet.value = true
        return code
    }

    /** Sends a remote-burn request carrying [code] over the session in [convo]. */
    suspend fun sendRemoteBurn(convo: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            burnCodeHash(code) // validates the format before it leaves the device
            val live = links[convo] ?: error("Peer is offline")
            live.link.send(ChatMessage(MessageKind.BURN, System.currentTimeMillis().toULong(), code.trim()))
            _events.value = "Remote burn sent"
        }
    }

    /** Handles an incoming remote burn. Returns false when the link should be dropped. */
    private fun onRemoteBurn(convo: String, code: String): Boolean {
        val h = burnHash ?: return true
        if (verifyBurnCode(code, h)) {
            Log.w(TAG, "remote burn accepted")
            burnAll()
            _events.value = "Remote burn: every number on this device was burned"
            return true
        }
        val fails = burnFailures.merge(convo, 1, Int::plus) ?: 1
        Log.w(TAG, "remote burn rejected ($fails)")
        return fails < MAX_BURN_FAILURES
    }

    fun numberOf(burnerId: String): String = _burners.value.firstOrNull { it.id == burnerId }?.number ?: ""

    private fun startBurner(b: Burner) {
        val local = Local(b.id, b.identity, numberService(b.number))
        locals[b.id] = local
        scope.launch { listen(local, 0) }
    }

    // ---- persistence -------------------------------------------------------

    private fun persist(c: Contact) {
        if (c.via == null) store.saveContact(c) else if (locals.containsKey(c.via)) burnerStore.append(c.via, EventLog.contactJson(c))
    }

    private fun persistMessage(m: StoredMessage): Boolean {
        if (m.via == null) store.saveMessage(m)
        else if (locals.containsKey(m.via)) burnerStore.append(m.via, EventLog.messageJson(m))
        else return false
        return true
    }

    private fun record(m: StoredMessage) = synchronized(msgLock) {
        if (persistMessage(m)) _messages.update { it + m }
    }

    private fun setState(convo: String, s: LinkState) = _linkStates.update { it + (convo to s) }

    // ---- local discovery (mDNS / DNS-SD) ----------------------------------

    private fun register(local: Local) {
        val info = NsdServiceInfo().apply { serviceName = local.serviceName; serviceType = SERVICE_TYPE; port = local.port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "advertising ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "register failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        local.registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun isOwnService(name: String) = name == main.serviceName || locals.values.any { it.serviceName == name }

    private fun startDiscovery() {
        multicastLock = context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("aether-nsd")?.apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "discovery failed $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { _nearby.update { it - info.serviceName } }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (isOwnService(info.serviceName) || !info.serviceName.startsWith("AETHER-")) return
                resolve(info)
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "resolve failed $code") }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress ?: return
                _nearby.update { it + (i.serviceName to NearbyPeer(i.serviceName, host, i.port)) }
            }
        })
    }

    companion object {
        const val PORT = 7337
        val BUNDLE_SIZES = listOf(1, 2, 5)
        private const val SERVICE_TYPE = "_aether._tcp."
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_BURN_FAILURES = 3
        private const val PURGE_EVERY_MS = 5_000L
        private const val TYPING_TTL_MS = 6_000L
        private const val MAX_PINS = 20
        private const val TYPING_RESEND_MS = 3_000L
        /** Quick reactions offered in the chat, in order. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
        private const val TAG = "AetherNode"

        fun numberService(number: String) = "AETHER-N$number"
        fun isNumberService(name: String) = name.startsWith("AETHER-N") && name.length == "AETHER-N".length + 10
        fun formatNumber(number: String): String = uniffi.aether_core.formatBurnerNumber(number)
    }
}
