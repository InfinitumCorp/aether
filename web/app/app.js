// ÆTHER MIL Web: browser client. All cryptography runs in the ÆTHER Rust core (WebAssembly);
// this file moves opaque byte buffers between the bridge, the core and the page.
import init, * as core from './pkg/aether_web.js';
import { KIND, TIMERS, PREC, REACTIONS, createTalk, fileKey, getFile, wipeFiles, isGroup, rid } from './talk.js';

const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();
const dec = new TextDecoder();
const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (s) => Uint8Array.from(s.match(/../g) || [], (h) => parseInt(h, 16));
const DEFAULT_BRIDGE = 'ws://127.0.0.1:7340';
const isHex = (s, n) => typeof s === 'string' && s.length % 2 === 0 && /^[0-9a-f]*$/.test(s) && (n === undefined || s.length === n);

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
const talk = createTalk({
  core,
  store,
  me: () => me,
  contacts: () => contacts,
  links,
  onChange: (c) => refresh(c),
  onAlert: (c, m) => alertPrecedence(c, m),
});
function saveContacts() { store.set('aether.contacts', contacts); }
const bridgeUrl = () => store.get('aether.bridge', DEFAULT_BRIDGE);

// ---- views ----------------------------------------------------------------------------------
function show(v) {
  document.querySelectorAll('.view').forEach((s) => { s.hidden = s.id !== 'v-' + v; });
  if (v === 'home') renderHome();
  if (v === 'about') { renderDiag(); renderCerts(); }
  if (v === 'connect') $('c-bridge').value = bridgeUrl();
  window.scrollTo(0, 0);
}
document.querySelectorAll('[data-nav]').forEach((b) => b.addEventListener('click', (e) => {
  e.preventDefault();
  if (!me) return show('enroll');
  current = null;
  show(b.dataset.nav);
}));

const title = (c) => {
  const g = talk.groupOf(c);
  if (g) return (g.channel ? '▣ ' : '◆ ') + g.name;
  const ct = contacts[c];
  return ct ? (ct.codename ? `${ct.name} · ${ct.codename}` : ct.name) : 'Unknown';
};

function preview(c, m) {
  if (!m) return '';
  if (m.sys) return m.body;
  const who = isGroup(c) && !m.sys ? (m.o ? 'You: ' : talk.nameOf(m.from) + ': ') : m.o ? 'You: ' : '';
  const prec = m.prec && m.prec !== 'R' ? `[${PREC[m.prec].toUpperCase()}] ` : '';
  const body = m.deleted ? 'Message deleted' : m.drop ? 'Key Drop' : m.file ? '📎 ' + (m.body || 'Attachment') : m.body;
  return (prec + who + body).slice(0, 90);
}

function renderHome() {
  $('me-name').textContent = me.name;
  $('me-fp').textContent = me.identity.fingerprint();
  $('me-bridge').textContent = bridgeUrl();
  $('alerts').hidden = !('Notification' in window) || Notification.permission !== 'default';
  const q = $('convo-search').value.trim().toLowerCase();
  const rows = [...Object.keys(contacts), ...Object.values(talk.groups()).map((g) => 'g:' + g.id)].map((c) => {
    const last = talk.msgsOf(c).at(-1);
    const unread = talk.unread(c);
    return { c, last, n: unread.length, flash: unread.some((m) => m.prec === 'Z'), urgent: unread.some((m) => m.prec === 'Z' || m.prec === 'O'), ts: last?.ts || talk.groupOf(c)?.ts || 0 };
  }).filter((r) => !q || title(r.c).toLowerCase().includes(q) || preview(r.c, r.last).toLowerCase().includes(q))
    .sort((a, b) => b.flash - a.flash || b.urgent - a.urgent || b.ts - a.ts);
  const ul = $('convos');
  ul.textContent = '';
  $('convos-empty').hidden = rows.length > 0 || !!q;
  for (const r of rows) {
    const c = contacts[r.c];
    const g = talk.groupOf(r.c);
    const li = document.createElement('li');
    const b = document.createElement('button');
    if (r.urgent) b.className = r.flash ? 'urgent flash' : 'urgent';
    b.innerHTML = '<span><span class="name"></span><br><span class="meta"></span></span><span class="side"><span class="meta state"></span><span class="badge"></span></span>';
    b.querySelector('.name').textContent = title(r.c);
    b.querySelector('.meta').textContent = r.last ? preview(r.c, r.last) : g ? `${g.members.length} members` : (c.number ? core.formatBurnerNumber(c.number) : c.addr || '');
    b.querySelector('.state').textContent = g ? (g.left ? 'left' : g.channel ? 'channel' : 'group') : (c.verified ? 'Verified · ' : '') + (links.get(r.c)?.state || 'offline');
    const badge = b.querySelector('.badge');
    badge.textContent = r.n ? String(r.n) : '';
    badge.hidden = !r.n;
    b.addEventListener('click', () => openChat(r.c));
    li.append(b);
    ul.append(li);
  }
}
$('convo-search').addEventListener('input', renderHome);
$('alerts').addEventListener('click', async () => { try { await Notification.requestPermission(); } catch { /* */ } renderHome(); });

/** Immediate and Flash traffic: a banner (and a system notification when allowed) unless that chat is open. */
function alertPrecedence(c, m) {
  if (current === c && document.visibilityState === 'visible') return;
  const text = `${PREC[m.prec].toUpperCase()} · ${title(c)}: ${m.file ? 'Attachment' : m.body}`.slice(0, 140);
  const a = $('alert');
  a.textContent = text;
  a.className = 'alert' + (m.prec === 'Z' ? ' flash' : '');
  a.hidden = false;
  a.onclick = () => { a.hidden = true; openChat(c); };
  clearTimeout(alertPrecedence.t);
  alertPrecedence.t = setTimeout(() => { a.hidden = true; }, m.prec === 'Z' ? 30000 : 12000);
  try { if (Notification.permission === 'granted') new Notification('ÆTHER MIL · ' + PREC[m.prec], { body: text, tag: c, requireInteraction: m.prec === 'Z' }); } catch { /* */ }
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
    const hello = core.decodeHello(hs.readMessage(await fs.next()));  // <- e, ee, s, es (+ ML-KEM ciphertext, hello)
    const peerName = hello.name;
    const rk = hs.remotePublicKey();
    // Check the responder before revealing anything about ourselves (message 3 carries our key and name).
    if (!rk) throw new Error('No remote key');
    if (expectKey && hex(rk) !== expectKey) throw new IdentityRefused('That node is not the pinned key. Hung up before revealing this identity.');
    if (expectNumber && core.burnerNumber(rk) !== expectNumber) throw new IdentityRefused('That node does not hold this number. Hung up before revealing this identity.');
    if (classic && contacts[hex(rk)]?.pq) throw new IdentityRefused('This contact speaks the post-quantum handshake. Refusing a classical session.');
    const cfg = certConfig();
    const cert = core.checkPeerCertificate(hello.certificate, unhex(cfg.cas.join('')), rk, Date.now());
    if (cfg.require && cert.status !== 'valid') throw new IdentityRefused(`That node has no valid certificate (${cert.reason}). Hung up before revealing this identity.`);
    fs.send(hs.writeMessage(core.encodeHello(me.name, unhex(cfg.mine)))); // -> s, se (, psk) (+hello)
    const session = hs.finish();
    const k = hex(session.remotePublicKey());
    const prev = contacts[k] || {};
    contacts[k] = { ...prev, name: (peerName || 'Unnamed peer').slice(0, 64), addr, number: expectNumber || prev.number || null, verified: !!prev.verified, pq: !!prev.pq || session.postQuantum() };
    saveContacts();
    adopt(k, fs, session, { status: cert.status, label: cert.label, reason: cert.reason });
    return k;
  } catch (e) {
    fs.close();
    throw e;
  } finally {
    hs.free();
  }
}

function adopt(k, fs, session, cert) {
  links.get(k)?.fs.close();
  const link = { fs, session, state: 'online', cert };
  links.set(k, link);
  fs.onclose = () => { if (links.get(k) === link) { link.state = 'offline'; refresh(k); } };
  (async () => {
    for (;;) {
      let f;
      try { f = await fs.next(1e9); } catch { break; }
      let m;
      try { m = session.decrypt(f); } catch { fs.close(); break; } // authentication failure: drop the link
      if (talk.receive(k, m)) continue;
      if (m.kind === KIND.TEXT) addMsg(k, { id: 'l' + rid(), local: true, o: false, ts: m.sentAtMs, from: k, body: m.body });
      else if (m.kind === KIND.DROP) addMsg(k, { id: 'l' + rid(), local: true, o: false, ts: m.sentAtMs, from: k, body: m.body, drop: true });
      else continue;
      refresh(k);
    }
  })();
  talk.flush(k);
  refresh(k);
}

function send(k, kind, body) {
  const l = links.get(k);
  if (!l || l.state !== 'online') throw new Error('Not connected');
  const ts = Date.now();
  l.fs.send(l.session.encrypt(kind, ts, body));
  addMsg(k, { id: 'l' + rid(), local: true, o: true, ts, from: talk.myKey(), body, drop: kind === KIND.DROP });
  refresh(k);
}

function refresh(k) {
  if (current === k) renderChat();
  else if (!$('v-home').hidden) renderHome();
}

// ---- chat -----------------------------------------------------------------------------------
let replyTo = null;   // message id being replied to
let editing = null;   // message id being edited
let urls = [];        // object URLs of the attachments on screen
let rendered = '';    // signature of what the chat shows, so the ticker only redraws on change

function openChat(k) {
  current = k;
  replyTo = editing = null;
  $('chat-verify-box').hidden = true;
  $('chat-search').value = '';
  $('chat-search').hidden = true;
  $('send-text').value = '';
  show('chat');
  renderChat(true);
}

function chatSignature(c) {
  const l = talk.msgsOf(c);
  return [l.length, l.at(-1)?.id, l.reduce((n, m) => n + (m.dl || 0) + (m.edited ? 1 : 0) + Object.keys(m.reactions || {}).length, 0), talk.settingsOf(c).pins.join()].join('|');
}

function renderChat(scroll = true) {
  const c = current;
  const g = talk.groupOf(c);
  const ct = contacts[c];
  const l = links.get(c);
  const online = l?.state === 'online';
  $('chat-name').textContent = title(c);
  if (g) {
    $('chat-state').textContent = `■ End-to-end encrypted · ${g.members.length} members` + (g.channel ? ' · unit channel' : '') + (g.left ? ' · you left' : '');
    $('chat-state').title = '';
    $('chat-sub').textContent = 'Admins: ' + g.admins.map(talk.nameOf).join(', ');
  } else {
    const pq = online ? l.session.postQuantum() : !!ct.pq;
    const cert = online ? l.cert : null;
    const certText = !cert ? '' : cert.status === 'valid' ? ` · certified ${cert.label}` : cert.status === 'absent' ? ' · uncertified' : ` · certificate ${cert.status}`;
    $('chat-state').textContent = (online ? '■ End-to-end encrypted · online' : '□ Offline') + (pq ? ' · post-quantum' : online ? ' · classical' : '') + certText + (ct.verified ? ' · verified' : '');
    $('chat-state').title = cert && cert.status !== 'valid' ? cert.reason : '';
    $('chat-sub').textContent = [ct.number ? 'via ' + core.formatBurnerNumber(ct.number) : null, ct.pinned ? 'pinned key' : null, ct.addr].filter(Boolean).join(' · ');
  }
  $('chat-reconnect').hidden = !!g || online;
  $('chat-drop').hidden = !!g;
  $('chat-drop').disabled = !online;
  $('chat-verify').hidden = !!g;
  $('chat-info').hidden = !g;
  $('chat-wipe').hidden = !talk.isAdmin(c) || (!g && !online);
  const canPost = talk.canPost(c);
  const s = talk.settingsOf(c);
  $('chat-timer').value = String(s.timer);
  $('chat-timer').disabled = !canPost || !talk.isAdmin(c);
  renderTyping();

  // Pinned bar: the newest pinned message still on this device.
  const all = talk.msgsOf(c);
  const byId = new Map(all.map((m) => [m.id, m]));
  const pins = s.pins.map((id) => byId.get(id)).filter((m) => m && !m.deleted);
  const bar = $('pinned');
  bar.hidden = !pins.length;
  if (pins.length) {
    const p = pins.at(-1);
    bar.querySelector('.pin-count').textContent = pins.length > 1 ? `Pinned · ${pins.length}` : 'Pinned';
    bar.querySelector('.pin-text').textContent = preview(c, p);
    bar.onclick = () => document.querySelector(`[data-id="${CSS.escape(p.id)}"]`)?.scrollIntoView({ block: 'center' });
  }

  // Composer.
  $('f-send').hidden = !canPost;
  $('send-note').hidden = canPost;
  $('send-note').textContent = g?.left ? 'You are no longer in this group.' : g?.channel ? 'Unit channel: only admins post.' : 'Offline. Reconnect to send.';
  const rb = $('reply-bar');
  const target = byId.get(editing || replyTo);
  rb.hidden = !target;
  if (target) rb.querySelector('.rb-text').textContent = (editing ? 'Editing · ' : 'Reply to ' + talk.nameOf(target.from || (target.o ? talk.myKey() : c)) + ' · ') + preview(c, target).replace(/^You: /, '');

  // Messages.
  urls.forEach(URL.revokeObjectURL);
  urls = [];
  const q = $('chat-search').value.trim().toLowerCase();
  const ol = $('msgs');
  ol.textContent = '';
  for (const m of all) {
    if (q && !(m.body || '').toLowerCase().includes(q)) continue;
    ol.append(bubble(c, g, m, byId));
  }
  if (!all.length) {
    const li = document.createElement('li');
    li.className = 'msg sys';
    li.textContent = 'No messages. Everything here is end-to-end encrypted.';
    ol.append(li);
  }
  rendered = chatSignature(c);
  if (scroll && !q) ol.lastElementChild?.scrollIntoView({ block: 'end' });
  if (document.visibilityState === 'visible') talk.markRead(c);
}

function renderTyping() {
  const who = talk.typingIn(current);
  $('chat-typing').textContent = who.length ? `${who.join(', ')} ${who.length > 1 ? 'are' : 'is'} typing…` : '';
}

function bubble(c, g, m, byId) {
  const li = document.createElement('li');
  if (m.sys) { li.className = 'msg sys'; li.textContent = m.body; return li; }
  li.className = 'msg' + (m.o ? ' out' : '') + (m.drop ? ' drop' : '') + (m.prec && m.prec !== 'R' ? ' prec-' + m.prec : '') + (m.deleted ? ' deleted' : '');
  li.dataset.id = m.id || '';
  const add = (tag, cls, text) => { const e = document.createElement(tag); e.className = cls; e.textContent = text; li.append(e); return e; };
  if (m.prec && m.prec !== 'R') add('span', 'prec', PREC[m.prec].toUpperCase());
  if (g && !m.o) add('p', 'from', talk.nameOf(m.from));
  if (m.fwd) add('p', 'fwd', '↪ Forwarded');
  if (m.replyTo && !m.deleted) {
    const r = byId.get(m.replyTo);
    const bq = add('blockquote', 'reply', r ? `${r.o ? 'You' : talk.nameOf(r.from || c)}: ${preview(c, r).replace(/^.*?: /, '')}` : 'Original message unavailable');
    if (r) bq.addEventListener('click', (e) => { e.stopPropagation(); document.querySelector(`[data-id="${CSS.escape(r.id)}"]`)?.scrollIntoView({ block: 'center' }); });
  }
  if (m.drop) {
    const box = document.createElement('div');
    renderDrop(box, m);
    li.append(box);
  } else if (m.deleted) {
    add('p', 'body muted', 'Message deleted');
  } else {
    if (m.file) renderFile(li, c, m);
    if (m.body) add('p', 'body', m.body);
  }
  const reacts = Object.entries(m.reactions || {});
  if (reacts.length) {
    const div = document.createElement('div');
    div.className = 'reacts';
    for (const [emoji, who] of reacts) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'react' + (who.includes(talk.myKey()) ? ' mine' : '');
      b.textContent = `${emoji} ${who.length}`;
      b.title = who.map(talk.nameOf).join(', ');
      b.addEventListener('click', (e) => { e.stopPropagation(); act(() => talk.toggleReaction(c, m.id, emoji)); });
      div.append(b);
    }
    li.append(div);
  }
  const t = document.createElement('time');
  const ticks = m.o && !m.local ? [' · ✓ sent', ' · ✓✓ delivered', ' · ✓✓ read'][m.dl || 0] : '';
  t.textContent = new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + (m.edited ? ' · edited' : '') + (m.exp ? ' · ⏱ ' + untilText(m.exp) : '') + ticks;
  if (m.dl === 2) t.className = 'read';
  li.append(t);
  li.addEventListener('click', (e) => { if (!e.target.closest('a, button, audio, video')) openMenu(c, m); });
  return li;
}

function untilText(exp) {
  const s = Math.max(0, Math.round((exp - Date.now()) / 1000));
  return s < 60 ? s + 's' : s < 3600 ? Math.round(s / 60) + 'm' : s < 86400 ? Math.round(s / 3600) + 'h' : Math.round(s / 86400) + 'd';
}

function renderFile(li, c, m) {
  const box = document.createElement('div');
  box.className = 'file';
  box.textContent = 'Attachment…';
  li.append(box);
  getFile(fileKey(c, m.o ? talk.myKey() : m.from, m.file)).then((f) => {
    if (!f) { box.textContent = '📎 Attachment (not received)'; return; }
    const url = URL.createObjectURL(f.blob);
    urls.push(url);
    box.textContent = '';
    if (f.mime.startsWith('image/')) {
      const img = document.createElement('img');
      img.src = url;
      img.alt = f.name;
      box.append(img);
    } else if (f.mime.startsWith('audio/')) {
      const a = document.createElement('audio');
      a.controls = true;
      a.src = url;
      box.append(a);
    } else if (f.mime.startsWith('video/')) {
      const v = document.createElement('video');
      v.controls = true;
      v.src = url;
      box.append(v);
    }
    const a = document.createElement('a');
    a.href = url;
    a.download = f.name;
    a.textContent = `📎 ${f.name} · ${f.size < 1048576 ? Math.ceil(f.size / 1024) + ' KB' : (f.size / 1048576).toFixed(1) + ' MB'}`;
    box.append(a);
  });
}

function act(fn) {
  try {
    const r = fn();
    if (r?.catch) r.catch((e) => alert(e.message));
  } catch (e) { alert(e.message); }
}

/** The long-press menu of WhatsApp/Telegram: react, reply, copy, forward, edit, pin, delete. */
function openMenu(c, m) {
  if (m.sys) return;
  const box = $('msg-actions');
  box.textContent = '';
  const canPost = talk.canPost(c);
  const wire = !m.local && !m.deleted; // the message exists on the peer's device under this id
  const btn = (label, fn, cls = 'btn-secondary') => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = cls;
    b.textContent = label;
    b.addEventListener('click', () => { $('dlg-msg').close(); act(fn); });
    box.append(b);
  };
  if (wire && canPost) {
    const row = document.createElement('div');
    row.className = 'react-row';
    for (const emoji of REACTIONS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'react';
      b.textContent = emoji;
      b.addEventListener('click', () => { $('dlg-msg').close(); act(() => talk.toggleReaction(c, m.id, emoji)); });
      row.append(b);
    }
    box.append(row);
  }
  if (wire && canPost) btn('Reply', () => { replyTo = m.id; editing = null; renderChat(false); $('send-text').focus(); });
  if (m.body && !m.deleted && !m.drop) btn('Copy', () => navigator.clipboard.writeText(m.body));
  if (m.body && !m.deleted && !m.drop) btn('Forward', () => openForward(m));
  if (wire && m.o && m.body && !m.file && canPost) btn('Edit', () => { editing = m.id; replyTo = null; $('send-text').value = m.body; renderChat(false); $('send-text').focus(); });
  if (wire && talk.isAdmin(c) && canPost) btn(talk.settingsOf(c).pins.includes(m.id) ? 'Unpin' : 'Pin for everyone', () => talk.togglePin(c, m.id));
  btn('Delete for me', () => talk.deleteForMe(c, m.id));
  if (wire && m.o && canPost) btn('Delete for everyone', () => { if (confirm('Delete this message on every device?')) talk.deleteForEveryone(c, m.id); });
  $('msg-when').textContent = new Date(m.ts).toLocaleString() + (m.prec && m.prec !== 'R' ? ' · ' + PREC[m.prec] : '');
  $('dlg-msg').showModal();
}

function openForward(m) {
  const ul = $('fwd-list');
  ul.textContent = '';
  const targets = [...Object.keys(contacts), ...Object.values(talk.groups()).map((g) => 'g:' + g.id)].filter((c) => talk.canPost(c));
  if (!targets.length) { alert('No chat to forward to: connect to a contact first.'); return; }
  for (const c of targets) {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.innerHTML = '<span class="name"></span>';
    b.querySelector('.name').textContent = title(c);
    b.addEventListener('click', () => { $('dlg-fwd').close(); act(() => { talk.sendText(c, m.body, { forwarded: true }); openChat(c); }); });
    li.append(b);
    ul.append(li);
  }
  $('dlg-fwd').showModal();
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
  const text = $('send-text').value.trim();
  if (!text) return;
  const c = current;
  try {
    if (editing) talk.edit(c, editing, text);
    else talk.sendText(c, text, { replyTo, precedence: $('send-prec').value });
    $('send-text').value = '';
    replyTo = editing = null;
    $('send-prec').value = 'R';
    renderChat();
  } catch (err) { alert(err.message); }
});
$('send-text').addEventListener('input', () => { if ($('send-text').value.trim()) talk.setTyping(current, true); });
$('send-text').addEventListener('blur', () => { if (current) talk.setTyping(current, false); });
$('rb-close').addEventListener('click', () => { if (editing) $('send-text').value = ''; replyTo = editing = null; renderChat(false); });
$('send-attach').addEventListener('click', () => $('send-file').click());
$('send-file').addEventListener('change', async () => {
  const f = $('send-file').files[0];
  $('send-file').value = '';
  if (!f) return;
  const c = current;
  $('send-note').hidden = false;
  $('send-note').textContent = `Sending ${f.name}…`;
  try {
    await talk.sendFile(c, f, { caption: $('send-text').value.trim(), precedence: $('send-prec').value });
    $('send-text').value = '';
  } catch (err) { alert(err.message); }
  if (current === c) renderChat();
});
for (const [v, label] of TIMERS) $('chat-timer').append(new Option('⏱ ' + label, String(v)));
$('chat-timer').addEventListener('change', () => act(() => talk.setTimer(current, Number($('chat-timer').value))));
$('chat-find').addEventListener('click', () => { const s = $('chat-search'); s.hidden = !s.hidden; s.value = ''; if (!s.hidden) s.focus(); renderChat(false); });
$('chat-search').addEventListener('input', () => renderChat(false));
$('chat-wipe').addEventListener('click', () => {
  if (confirm(`Wipe “${title(current)}” on every device in it? Messages, files and pins are erased and cannot be recovered.`)) act(() => talk.wipeForEveryone(current));
});

// ---- groups and unit channels ----------------------------------------------------------------
let groupEdit = null; // convo of the group being edited, or null to create one

function openGroup(c) {
  groupEdit = c;
  const g = c ? talk.groupOf(c) : null;
  const admin = !g || talk.isAdmin(c);
  $('grp-title').textContent = g ? (g.channel ? 'Unit channel' : 'Group') : 'New group';
  $('grp-name').value = g ? g.name : '';
  $('grp-name').disabled = !admin;
  $('grp-channel').checked = !!g?.channel;
  $('grp-channel').disabled = !admin;
  $('grp-save').hidden = !admin;
  $('grp-leave').hidden = !g || g.left;
  const keys = [...new Set([...(g ? g.members : []), ...Object.keys(contacts)])].filter((k) => k !== talk.myKey());
  const ul = $('grp-members');
  ul.textContent = '';
  if (!keys.length) ul.innerHTML = '<li class="hint">No contacts yet. Connect to teammates first.</li>';
  for (const k of keys) {
    const li = document.createElement('li');
    li.innerHTML = '<label class="check"><input type="checkbox" class="m"> <span></span></label><label class="check adm"><input type="checkbox" class="a"> admin</label>';
    li.dataset.key = k;
    li.querySelector('span').textContent = talk.nameOf(k);
    li.querySelector('.m').checked = !!g?.members.includes(k);
    li.querySelector('.a').checked = !!g?.admins.includes(k);
    li.querySelectorAll('input').forEach((i) => { i.disabled = !admin; });
    li.querySelector('.adm').hidden = !g;
    ul.append(li);
  }
  $('dlg-group').showModal();
}

$('new-group').addEventListener('click', () => openGroup(null));
$('chat-info').addEventListener('click', () => openGroup(current));
$('grp-save').addEventListener('click', () => {
  const name = $('grp-name').value.trim();
  if (!name) { alert('Give the group a name.'); return; }
  const rows = [...$('grp-members').querySelectorAll('li[data-key]')];
  const members = rows.filter((r) => r.querySelector('.m').checked).map((r) => r.dataset.key);
  const admins = rows.filter((r) => r.querySelector('.m').checked && r.querySelector('.a').checked).map((r) => r.dataset.key);
  try {
    if (groupEdit) talk.updateGroup(groupEdit, { name, members, admins, channel: $('grp-channel').checked });
    else { const c = talk.createGroup(name, members, $('grp-channel').checked); $('dlg-group').close(); openChat(c); return; }
    $('dlg-group').close();
  } catch (e) { alert(e.message); }
});
$('grp-leave').addEventListener('click', () => {
  if (!confirm('Leave this group? You stop receiving its messages.')) return;
  talk.leaveGroup(groupEdit);
  $('dlg-group').close();
});

// Disappearing messages, typing indicators and receipts: redraw only when something changed.
setInterval(() => {
  if (!me) return;
  if (current && !$('v-chat').hidden) {
    renderTyping();
    if (chatSignature(current) !== rendered) renderChat(false);
  } else if (!$('v-home').hidden) renderHome();
}, 2000);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && current && !$('v-chat').hidden) talk.markRead(current); });

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
function certConfig() {
  const c = store.get('aether.cert', {});
  return { mine: isHex(c.mine) ? c.mine : '', cas: Array.isArray(c.cas) ? c.cas.filter((k) => isHex(k, 64)) : [], require: !!c.require };
}

function renderCerts(msg) {
  const cfg = certConfig();
  $('cert-mykey').value = hex(me.identity.publicKey());
  $('cert-mine').value = cfg.mine;
  $('cert-cas').value = cfg.cas.join(', ');
  $('cert-require').checked = cfg.require;
  const st = $('cert-status');
  st.className = 'status';
  if (msg) { st.textContent = msg; return; }
  if (!cfg.mine) { st.textContent = 'No certificate: peers see this browser as uncertified.'; return; }
  const own = core.checkPeerCertificate(unhex(cfg.mine), unhex(cfg.cas.join('')), me.identity.publicKey(), Date.now());
  const info = core.decodeDeviceCertificate(unhex(cfg.mine));
  const until = new Date(info.notAfterMs).toISOString().slice(0, 10);
  st.textContent = own.status === 'valid'
    ? `Certified as ${info.label} (${info.profile}) until ${until}.`
    : own.status === 'untrusted'
      ? `Certificate for ${info.label} until ${until}; add its CA key above to check it here.`
      : `This certificate is not usable here: ${own.reason}.`;
  if (own.status === 'invalid') st.className = 'status err';
}

$('cert-save').addEventListener('click', () => {
  const mine = $('cert-mine').value.trim().toLowerCase();
  const cas = $('cert-cas').value.split(/[\s,]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
  const st = $('cert-status');
  st.className = 'status err';
  if (mine && !isHex(mine)) { st.textContent = 'The certificate must be hex.'; return; }
  if (mine) {
    try {
      const info = core.decodeDeviceCertificate(unhex(mine));
      if (hex(info.devicePublicKey) !== hex(me.identity.publicKey())) { st.textContent = "That certificate is for a different key, not this browser's."; return; }
    } catch { st.textContent = 'That is not an ÆTHER device certificate.'; return; }
  }
  const bad = cas.find((k) => !isHex(k, 64));
  if (bad) { st.textContent = 'Each CA key is 64 hex characters.'; return; }
  store.set('aether.cert', { mine, cas, require: $('cert-require').checked });
  renderCerts();
});

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
  check('Device certificate in the handshake hello', () => {
    const peer = core.Identity.generate();
    const h = core.decodeHello(core.encodeHello('Peer', new Uint8Array()));
    const none = core.checkPeerCertificate(h.certificate, new Uint8Array(), peer.publicKey(), Date.now());
    return h.name === 'Peer' && none.status === 'absent' && core.decodeHello(enc.encode('Legacy')).name === 'Legacy';
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
  check('ÆTHER Talk event round trip (precedence, groups, pins)', () => {
    const e = new core.Social('msg');
    e.id = 'diag-1'; e.text = 'a | b'; e.precedence = 'Z'; e.replyTo = 'diag-0';
    const d = core.decodeSocial(core.encodeSocial(e));
    const pin = new core.Social('pin');
    pin.target = 'diag-1';
    const p = core.decodeSocial(core.encodeSocial(pin));
    let bad = false;
    try { const r = new core.Social('react'); r.target = 'x'; r.text = 'ok'; core.encodeSocial(r); } catch { bad = true; }
    return d.text === 'a | b' && d.precedence === 'Z' && d.replyTo === 'diag-0' && p.kind === 'pin' && bad;
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
  wipeFiles().finally(() => location.reload());
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
