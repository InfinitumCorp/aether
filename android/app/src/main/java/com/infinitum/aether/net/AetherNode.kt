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
import com.infinitum.aether.data.convo
import com.infinitum.aether.data.convoKey
import com.infinitum.aether.data.hexToBytes
import com.infinitum.aether.data.randomId
import com.infinitum.aether.data.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aether_core.ChatMessage
import uniffi.aether_core.Identity
import uniffi.aether_core.KeyDrop
import uniffi.aether_core.MessageKind
import uniffi.aether_core.burnCodeHash
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
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    private val _burners = MutableStateFlow<List<Burner>>(emptyList())
    val burners: StateFlow<List<Burner>> = _burners.asStateFlow()
    private val _bundles = MutableStateFlow<List<Bundle>>(emptyList())
    val bundles: StateFlow<List<Bundle>> = _bundles.asStateFlow()
    private val _burnCodeSet = MutableStateFlow(false)
    val burnCodeSet: StateFlow<Boolean> = _burnCodeSet.asStateFlow()

    fun start() {
        val (c, m) = store.loadLog()
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
        _messages.value = allMessages.sortedBy { it.sentAtMs }
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
        // A classical dial also hangs up before message 3 on a contact known to speak post-quantum.
        val accept: ((ByteArray) -> Boolean)? =
            if (classic) { k -> (expected?.invoke(k) ?: true) && !knownPq(k) } else expected
        val link = try {
            PeerLink.establish(sock, local.identity, local.displayName, initiator, accept, classic)
        } catch (e: RemoteRejectedException) {
            runCatching { sock.close() }
            _events.value = if (expectNumber != null) {
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
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "link ended: ${e.message}")
        } finally {
            links.remove(convo)?.let { if (it.link === link) setState(convo, LinkState.OFFLINE) else links[convo] = it }
            link.close()
        }
        return Outcome.DONE
    }

    suspend fun send(convo: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val live = links[convo] ?: return@withContext false
        val now = System.currentTimeMillis()
        try {
            live.link.send(ChatMessage(MessageKind.TEXT, now.toULong(), text))
            record(StoredMessage(live.link.remoteKey.toHex(), true, now, text, via = live.local.id))
            true
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            false
        }
    }

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

    private fun record(m: StoredMessage) {
        if (m.via == null) store.saveMessage(m)
        else if (locals.containsKey(m.via)) burnerStore.append(m.via, EventLog.messageJson(m))
        else return
        _messages.update { it + m }
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
        private const val TAG = "AetherNode"

        fun numberService(number: String) = "AETHER-N$number"
        fun isNumberService(name: String) = name.startsWith("AETHER-N") && name.length == "AETHER-N".length + 10
        fun formatNumber(number: String): String = uniffi.aether_core.formatBurnerNumber(number)
    }
}
