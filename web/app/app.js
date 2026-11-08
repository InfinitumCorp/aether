// ÆTHER MIL Web: browser client. All cryptography runs in the ÆTHER Rust core (WebAssembly);
// this file moves opaque byte buffers between the bridge, the core and the page.
import init, * as core from './pkg/aether_web.js';

const KIND = { TEXT: 1, RECEIPT: 2, PING: 3, DROP: 4, BURN: 5 };
const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();
const dec = new TextDecoder();
const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (s) => Uint8Array.from(s.match(/../g) || [], (h) => parseInt(h, 16));
const DEFAULT_BRIDGE = 'ws://127.0.0.1:7340';

// ---- storage (per-browser; see Diagnostics note) --------------------------------------------
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
  wipe() { try { Object.keys(localStorage).filter((k) => k.startsWith('aether.')).forEach((k) => localStorage.removeItem(k)); } catch { /* */ } },
};

let me = null;            // { identity, name }
let contacts = {};        // keyHex -> { name, verified, pinned, codename, addr, number }
const links = new Map();  // keyHex -> { ws, session, state }
let current = null;       // keyHex of open chat

const msgsOf = (k) => store.get('aether.msgs.' + k, []);
function addMsg(k, m) { const l = msgsOf(k); l.push(m); store.set('aether.msgs.' + k, l.slice(-500)); }
function saveContacts() { store.set('aether.contacts', contacts); }
const bridgeUrl = () => store.get('aether.bridge', DEFAULT_BRIDGE);

// ---- views ----------------------------------------------------------------------------------
function show(v) {
  document.querySelectorAll('.view').forEach((s) => { s.hidden = s.id !== 'v-' + v; });
  if (v === 'home') renderHome();
  if (v === 'about') renderDiag();
  if (v === 'connect') $('c-bridge').value = bridgeUrl();
  window.scrollTo(0, 0);
}
document.querySelectorAll('[data-nav]').forEach((b) => b.addEventListener('click', (e) => {
  e.preventDefault();
  if (!me) return show('enroll');
  current = null;
  show(b.dataset.nav);
}));

function renderHome() {
  $('me-name').textContent = me.name;
  $('me-fp').textContent = me.identity.fingerprint();
  $('me-bridge').textContent = bridgeUrl();
  const ul = $('convos');
  ul.textContent = '';
  const keys = Object.keys(contacts);
  $('convos-empty').hidden = keys.length > 0;
  for (const k of keys) {
    const c = contacts[k];
    const last = msgsOf(k).at(-1);
    const state = links.get(k)?.state || 'offline';
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.innerHTML = '<span><span class="name"></span><br><span class="meta"></span></span><span class="meta state"></span>';
    b.querySelector('.name').textContent = c.codename ? `${c.name} · ${c.codename}` : c.name;
    b.querySelector('.meta').textContent = last ? (last.drop ? 'Key Drop' : last.body).slice(0, 80) : (c.number ? core.formatBurnerNumber(c.number) : c.addr || '');
    b.querySelector('.state').textContent = (c.verified ? 'Verified · ' : '') + state;
    b.addEventListener('click', () => openChat(k));
    li.append(b);
    ul.append(li);
  }
}

// ---- connection -----------------------------------------------------------------------------
class FrameSocket {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.ws.binaryType = 'arraybuffer';
    this.queue = [];
    this.waiters = [];
    this.closed = null;
    this.opened = new Promise((res, rej) => {
      this.ws.addEventListener('message', (e) => {
        if (typeof e.data === 'string') { if (JSON.parse(e.data).t === 'open') res(); return; }
        const f = new Uint8Array(e.data);
        const w = this.waiters.shift();
        w ? w.res(f) : this.queue.push(f);
      });
      this.ws.addEventListener('close', (e) => {
        this.closed = e.reason || `closed (${e.code})`;
        rej(new Error(this.closed));
        this.waiters.splice(0).forEach((w) => w.rej(new Error(this.closed)));
        this.onclose?.();
      });
      this.ws.addEventListener('error', () => rej(new Error('Bridge unreachable at ' + url)));
    });
  }
  next(timeoutMs = 10000) {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    if (this.closed) return Promise.reject(new Error(this.closed));
    return new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('Handshake timed out')), timeoutMs);
      this.waiters.push({ res: (f) => { clearTimeout(t); res(f); }, rej: (e) => { clearTimeout(t); rej(e); } });
    });
  }
  send(bytes) { this.ws.send(bytes); }
  close() { try { this.ws.close(); } catch { /* */ } }
}

/** Dials addr through the bridge and runs the Noise XX handshake as initiator. */
class IdentityRefused extends Error {}

// Dials with the hybrid post-quantum handshake (v2) and falls back to classical v1 only for a
// peer that has never completed v2 with us, so an attacker can't strip ML-KEM from a known
// post-quantum contact by breaking the first attempt.
async function dial(addr, opts = {}) {
  try {
    return await dialOnce(addr, opts, false);
  } catch (e) {
    if (e instanceof IdentityRefused) throw e;
    const known = opts.expectKey && contacts[opts.expectKey];
    if (known?.pq) throw new Error('This contact speaks the post-quantum handshake and this attempt did not. Not falling back.');
    return await dialOnce(addr, opts, true);
  }
}

async function dialOnce(addr, { expectKey = null, expectNumber = null } = {}, classic) {
  const fs = new FrameSocket(`${bridgeUrl()}?to=${encodeURIComponent(addr)}`);
  await fs.opened;
  const hs = classic ? core.Handshake.initiatorClassic(me.identity) : core.Handshake.initiator(me.identity);
  try {
    fs.send(hs.writeMessage(new Uint8Array()));                       // -> e (+ ML-KEM key in v2)
    const peerName = dec.decode(hs.readMessage(await fs.next()));     // <- e, ee, s, es (+ ML-KEM ciphertext, name)
    const rk = hs.remotePublicKey();
    // Check the responder before revealing anything about ourselves (message 3 carries our key and name).
    if (!rk) throw new Error('No remote key');
    if (expectKey && hex(rk) !== expectKey) throw new IdentityRefused('That node is not the pinned key. Hung up before revealing this identity.');
    if (expectNumber && core.burnerNumber(rk) !== expectNumber) throw new IdentityRefused('That node does not hold this number. Hung up before revealing this identity.');
    if (classic && contacts[hex(rk)]?.pq) throw new IdentityRefused('This contact speaks the post-quantum handshake. Refusing a classical session.');
    fs.send(hs.writeMessage(enc.encode(me.name)));                    // -> s, se (, psk) (+name)
    const session = hs.finish();
    const k = hex(session.remotePublicKey());
    const prev = contacts[k] || {};
    contacts[k] = { ...prev, name: (peerName || 'Unnamed peer').slice(0, 64), addr, number: expectNumber || prev.number || null, verified: !!prev.verified, pq: !!prev.pq || session.postQuantum() };
    saveContacts();
    adopt(k, fs, session);
    return k;
  } catch (e) {
    fs.close();
    throw e;
  } finally {
    hs.free();
  }
}

function adopt(k, fs, session) {
  links.get(k)?.fs.close();
  const link = { fs, session, state: 'online' };
  links.set(k, link);
  fs.onclose = () => { if (links.get(k) === link) { link.state = 'offline'; refresh(k); } };
  (async () => {
    for (;;) {
      let f;
      try { f = await fs.next(1e9); } catch { break; }
      let m;
      try { m = session.decrypt(f); } catch { fs.close(); break; } // authentication failure: drop the link
      if (m.kind === KIND.TEXT) addMsg(k, { o: false, ts: m.sentAtMs, body: m.body });
      else if (m.kind === KIND.DROP) addMsg(k, { o: false, ts: m.sentAtMs, body: m.body, drop: true });
      refresh(k);
    }
  })();
  refresh(k);
}

function send(k, kind, body) {
  const l = links.get(k);
  if (!l || l.state !== 'online') throw new Error('Not connected');
  const ts = Date.now();
  l.fs.send(l.session.encrypt(kind, ts, body));
  addMsg(k, { o: true, ts, body, drop: kind === KIND.DROP });
  refresh(k);
}

function refresh(k) {
  if (current === k) renderChat();
  else if (!$('v-home').hidden) renderHome();
}

// ---- chat -----------------------------------------------------------------------------------
function openChat(k) { current = k; $('chat-verify-box').hidden = true; show('chat'); renderChat(); }

function renderChat() {
  const k = current;
  const c = contacts[k];
  const l = links.get(k);
  const online = l?.state === 'online';
  $('chat-name').textContent = c.codename ? `${c.name} · ${c.codename}` : c.name;
  const pq = online ? links.get(current).session.postQuantum() : !!c.pq;
  $('chat-state').textContent = (online ? '■ End-to-end encrypted · online' : '□ Offline') + (pq ? ' · post-quantum' : online ? ' · classical' : '') + (c.verified ? ' · verified' : '');
  $('chat-sub').textContent = [c.number ? 'via ' + core.formatBurnerNumber(c.number) : null, c.pinned ? 'pinned key' : null, c.addr].filter(Boolean).join(' · ');
  $('chat-reconnect').hidden = online;
  $('chat-drop').disabled = !online;
  $('f-send').querySelector('button').disabled = !online;
  const ol = $('msgs');
  ol.textContent = '';
  for (const m of msgsOf(k)) {
    const li = document.createElement('li');
    li.className = 'msg' + (m.o ? ' out' : '') + (m.drop ? ' drop' : '');
    if (m.drop) renderDrop(li, m);
    else li.textContent = m.body;
    const t = document.createElement('time');
    t.textContent = new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    li.append(t);
    ol.append(li);
  }
  ol.lastElementChild?.scrollIntoView({ block: 'end' });
}

function renderDrop(li, m) {
  let d;
  try { d = core.decodeKeyDrop(m.body); } catch { li.textContent = 'Malformed Key Drop'; return; }
  const key = hex(d.publicKey);
  li.innerHTML = '<p class="kicker">Key Drop</p><div class="code"></div><div class="who"></div><div class="mono fp"></div>';
  li.querySelector('.code').textContent = d.codename;
  li.querySelector('.who').textContent = d.name + (d.addresses.length ? ' · ' + d.addresses.join(', ') : '');
  li.querySelector('.fp').textContent = key === hex(me.identity.publicKey()) ? 'Your key' : key.slice(0, 16) + '…';
  if (!m.o && key !== hex(me.identity.publicKey())) {
    const pinned = contacts[key]?.pinned;
    const b = document.createElement('button');
    b.className = 'link';
    b.textContent = pinned ? 'Pinned · connect ↗' : 'Pin key';
    b.addEventListener('click', async () => {
      if (!pinned) {
        contacts[key] = { ...(contacts[key] || {}), name: contacts[key]?.name || d.name, pinned: true, codename: d.codename, addr: d.addresses[0] || contacts[key]?.addr || null, verified: !!contacts[key]?.verified };
        saveContacts();
        renderChat();
        return;
      }
      const addr = contacts[key].addr;
      if (!addr) return alert('No address in this drop. Use Connect + with the node address.');
      try { await dial(addr, { expectKey: key }); openChat(key); } catch (e) { alert(e.message); }
    });
    li.append(b);
  }
}

$('f-send').addEventListener('submit', (e) => {
  e.preventDefault();
  const t = $('send-text').value.trim();
  if (!t) return;
  try { send(current, KIND.TEXT, t); $('send-text').value = ''; } catch (err) { alert(err.message); }
});

$('chat-verify').addEventListener('click', () => {
  const l = links.get(current);
  const box = $('chat-verify-box');
  if (!l) { alert('Connect first to compute the safety number.'); return; }
  $('chat-safety').textContent = l.session.safetyNumber(me.identity);
  box.hidden = !box.hidden;
});
$('chat-mark').addEventListener('click', () => { contacts[current].verified = true; saveContacts(); $('chat-verify-box').hidden = true; renderChat(); });
$('chat-reconnect').addEventListener('click', async () => {
  const c = contacts[current];
  try { await dial(c.addr, { expectKey: current, expectNumber: c.number }); } catch (e) { alert(e.message); }
});

$('chat-drop').addEventListener('click', () => { $('drop-code').value = core.suggestCodename(); $('dlg-drop').showModal(); });
$('drop-shuffle').addEventListener('click', () => { $('drop-code').value = core.suggestCodename(); });
$('dlg-drop').addEventListener('close', () => {
  if ($('dlg-drop').returnValue !== 'send') return;
  try {
    const body = core.encodeKeyDrop($('drop-code').value.trim(), me.name, me.identity.publicKey(), '');
    send(current, KIND.DROP, body);
  } catch (e) { alert(e.message); }
});

// ---- connect --------------------------------------------------------------------------------
$('f-connect').addEventListener('submit', async (e) => {
  e.preventDefault();
  const st = $('c-status');
  st.className = 'status';
  const addr = $('c-addr').value.trim();
  const rawNum = $('c-number').value.trim();
  const number = rawNum ? core.parseBurnerNumber(rawNum) : null;
  if (rawNum && !number) { st.className = 'status err'; st.textContent = 'A burner number has 10 digits.'; return; }
  store.set('aether.bridge', $('c-bridge').value.trim() || DEFAULT_BRIDGE);
  st.textContent = 'Connecting…';
  try {
    const k = await dial(addr, { expectNumber: number });
    st.textContent = '';
    openChat(k);
  } catch (err) {
    st.className = 'status err';
    st.textContent = err.message;
  }
});

// ---- diagnostics ----------------------------------------------------------------------------
function renderDiag() {
  const out = [];
  const check = (name, fn) => { try { out.push([name, fn() ? 'Pass' : 'Fail']); } catch (e) { out.push([name, 'Fail: ' + e.message]); } };
  check('Core loaded (WebAssembly)', () => !!core.coreVersion());
  check('Secure random source', () => !!crypto.getRandomValues);
  check('Noise XX handshake and session round trip', () => {
    const a = core.Identity.generate(), b = core.Identity.generate();
    const i = core.Handshake.initiator(a), r = core.Handshake.responder(b);
    r.readMessage(i.writeMessage(new Uint8Array()));
    i.readMessage(r.writeMessage(enc.encode('B')));
    r.readMessage(i.writeMessage(enc.encode('A')));
    const si = i.finish(), sr = r.finish();
    const m = sr.decrypt(si.encrypt(KIND.TEXT, Date.now(), 'ping'));
    return m.body === 'ping' && si.safetyNumber(a) === sr.safetyNumber(b);
  });
  check('Post-quantum handshake (X25519 + ML-KEM-768)', () => {
    const a = core.Identity.generate(), b = core.Identity.generate();
    const i = core.Handshake.initiator(a), r = core.Handshake.responder(b);
    r.readMessage(i.writeMessage(new Uint8Array()));
    i.readMessage(r.writeMessage(new Uint8Array()));
    r.readMessage(i.writeMessage(new Uint8Array()));
    const si = i.finish(), sr = r.finish();
    const c = core.Handshake.initiatorClassic(a), rc = core.Handshake.responder(b);
    rc.readMessage(c.writeMessage(new Uint8Array()));
    c.readMessage(rc.writeMessage(new Uint8Array()));
    rc.readMessage(c.writeMessage(new Uint8Array()));
    const sc = c.finish();
    return si.postQuantum() && sr.postQuantum() && !sc.postQuantum() && sr.decrypt(si.encrypt(KIND.TEXT, 0, 'pq')).body === 'pq';
  });
  check('Tampered frame rejected', () => {
    const a = core.Identity.generate(), b = core.Identity.generate();
    const i = core.Handshake.initiator(a), r = core.Handshake.responder(b);
    r.readMessage(i.writeMessage(new Uint8Array()));
    i.readMessage(r.writeMessage(new Uint8Array()));
    r.readMessage(i.writeMessage(new Uint8Array()));
    const si = i.finish(), sr = r.finish();
    const f = si.encrypt(KIND.TEXT, 0, 'x');
    f[f.length - 1] ^= 1;
    try { sr.decrypt(f); return false; } catch { return true; }
  });
  check('Burner number derivation', () => /^\d{10}$/.test(core.burnerNumber(me.identity.publicKey())));
  const ol = $('diag');
  ol.textContent = '';
  for (const [n, r] of out) {
    const li = document.createElement('li');
    li.innerHTML = '<span></span><span class="mono"></span>';
    li.children[0].textContent = n;
    li.children[1].textContent = r;
    ol.append(li);
  }
  document.body.dataset.diag = out.every(([, r]) => r === 'Pass') ? 'pass' : 'fail';
}
$('wipe').addEventListener('click', () => {
  if (!confirm('Wipe this browser identity and every message stored here?')) return;
  links.forEach((l) => l.fs.close());
  store.wipe();
  location.reload();
});

// ---- boot -----------------------------------------------------------------------------------
$('f-enroll').addEventListener('submit', (e) => {
  e.preventDefault();
  const name = $('enroll-name').value.trim().slice(0, 64);
  if (!name) return;
  const identity = core.Identity.generate();
  store.set('aether.id', { s: hex(identity.exportSecret()), n: name });
  me = { identity, name };
  show('home');
});

await init();
$('core-ver').textContent = 'core ' + core.coreVersion();
const saved = store.get('aether.id', null);
if (saved) {
  me = { identity: core.Identity.fromSecret(unhex(saved.s)), name: saved.n };
  contacts = store.get('aether.contacts', {});
  show('home');
} else {
  show('enroll');
}
