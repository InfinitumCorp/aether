// ÆTHER Talk™ in the browser: the military messenger feature set (from FabriC++) carried as
// MessageKind 9 events. Every event is encoded and checked by the Rust core (WebAssembly) and
// travels end-to-end encrypted inside a Noise session; groups are fanned out pairwise, so the
// bridge and the mesh never see membership or content.
//
// Messages, groups, pins and timers live in this browser's local storage; files in IndexedDB.

export const KIND = { TEXT: 1, RECEIPT: 2, PING: 3, DROP: 4, BURN: 5, SOCIAL: 9 };
export const TIMERS = [[0, 'Off'], [30, '30 seconds'], [300, '5 minutes'], [3600, '1 hour'], [28800, '8 hours'], [86400, '1 day'], [604800, '1 week']];
export const PREC = { R: 'Routine', P: 'Priority', O: 'Immediate', Z: 'Flash' };
export const REACTIONS = ['👍', '👎', '✅', '❌', '⚠️', '🎯'];
export const CHUNK = 32 * 1024;
export const MAX_FILE = 25 * 1024 * 1024;
const TYPING_TTL = 6000;
const TYPING_RESEND = 4000;

const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
export const rid = () => hex(crypto.getRandomValues(new Uint8Array(8)));
export const isGroup = (c) => c.startsWith('g:');
export const timerLabel = (s) => (TIMERS.find(([v]) => v === s) || [0, s + ' s'])[1];

function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

// ---- files (IndexedDB, keyed "<convo>|<sender>:<file id>") -------------------------------------
let dbp = null;
function db() {
  dbp ||= new Promise((res, rej) => {
    const r = indexedDB.open('aether-files', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('f');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbp;
}
async function fileOp(mode, fn) {
  const d = await db();
  return new Promise((res, rej) => {
    const tx = d.transaction('f', mode);
    const rq = fn(tx.objectStore('f'));
    tx.oncomplete = () => res(rq?.result);
    tx.onerror = () => rej(tx.error);
  });
}
export const fileKey = (c, from, id) => `${c}|${from}:${id}`;
export const getFile = (key) => fileOp('readonly', (s) => s.get(key)).catch(() => null);
const putFile = (key, v) => fileOp('readwrite', (s) => s.put(v, key));
const dropFiles = (c) => fileOp('readwrite', (s) => s.delete(IDBKeyRange.bound(c + '|', c + '|￿'))).catch(() => {});
export const wipeFiles = () => fileOp('readwrite', (s) => s.clear()).catch(() => {});

/**
 * The messenger. `env` gives it the page's state: { core, store, me(), contacts(), links, onChange(convo), onAlert(convo, msg) }.
 */
export function createTalk(env) {
  const { core, store } = env;
  const myKey = () => hex(env.me().identity.publicKey());
  let groups = store.get('aether.groups', {});
  const typing = new Map();       // convo -> Map(key -> until)
  const lastTyping = new Map();   // convo -> ms we last sent "typing"
  const lastRead = new Map();     // convo -> id we last sent "read" for
  const inbound = new Map();      // "<sender>:<file id>" -> transfer in progress

  // ---- storage ------------------------------------------------------------------------------
  const saveGroups = () => store.set('aether.groups', groups);
  const msgsOf = (c) => store.get('aether.msgs.' + c, []);
  const putMsgs = (c, l) => store.set('aether.msgs.' + c, l.slice(-500));
  const settingsOf = (c) => ({ timer: 0, pins: [], read: 0, ...store.get('aether.chat.' + c, {}) });
  const setSettings = (c, f) => store.set('aether.chat.' + c, f(settingsOf(c)));
  function addMsg(c, m) { const l = msgsOf(c); l.push(m); putMsgs(c, l); }
  function mutate(c, id, f) {
    const l = msgsOf(c);
    const i = l.findLastIndex((m) => m.id === id);
    if (i < 0) return null;
    const n = f(l[i]);
    if (!n || n === l[i]) return null;
    l[i] = n;
    putMsgs(c, l);
    return n;
  }
  const system = (c, text, ts = Date.now()) => addMsg(c, { id: 's' + rid(), sys: true, ts, body: text });
  const groupOf = (c) => (isGroup(c) ? groups[c.slice(2)] : null);
  const nameOf = (k) => {
    if (k === myKey()) return 'You';
    const c = env.contacts()[k];
    return c ? c.codename || c.name : 'Operator ' + k.slice(0, 8);
  };
  const erased = (m) => ({ ...m, body: '', deleted: true, reactions: {}, replyTo: null, edited: false, file: null });
  function react(m, emoji, who, remove) {
    if (m.deleted) return m;
    const r = { ...(m.reactions || {}) };
    const set = new Set(r[emoji] || []);
    remove ? set.delete(who) : set.add(who);
    if (set.size) r[emoji] = [...set]; else delete r[emoji];
    return { ...m, reactions: r };
  }
  function social(kind, fields = {}) {
    const e = new core.Social(kind);
    for (const [k, v] of Object.entries(fields)) e[k] = v;
    return e;
  }

  /** Purges expired disappearing messages of `c` (and their files); returns the live list. */
  function live(c) {
    const now = Date.now();
    const l = msgsOf(c);
    const keep = l.filter((m) => !m.exp || m.exp > now);
    if (keep.length !== l.length) {
      putMsgs(c, keep);
      for (const m of l) if (m.exp && m.exp <= now && m.file) fileOp('readwrite', (s) => s.delete(fileKey(c, m.from, m.file))).catch(() => {});
    }
    return keep;
  }

  function wipeLocal(c) {
    putMsgs(c, []);
    setSettings(c, (s) => ({ ...s, pins: [] }));
    dropFiles(c);
  }

  // ---- sending ------------------------------------------------------------------------------
  function sendFrame(k, kind, ts, body) {
    const l = env.links.get(k);
    if (l?.state !== 'online') return false;
    try { l.fs.send(l.session.encrypt(kind, ts, body)); return true; } catch { return false; }
  }

  function fanOut(members, body, ts, queue) {
    const mine = myKey();
    const q = store.get('aether.pending', []);
    for (const m of new Set(members)) {
      if (m === mine) continue;
      if (!sendFrame(m, KIND.SOCIAL, ts, body) && queue) q.push({ k: m, body, ts });
    }
    store.set('aether.pending', q.slice(-1000));
  }

  /** Sends queued group events to `k` once its link is up. */
  function flush(k) {
    const q = store.get('aether.pending', []);
    const rest = [];
    for (const p of q) if (p.k !== k || !sendFrame(k, KIND.SOCIAL, p.ts, p.body)) rest.push(p);
    if (rest.length !== q.length) store.set('aether.pending', rest);
  }

  /** Sends event `e` to conversation `c` (fanned out for a group) and applies it here. Frees `e`. */
  function send(c, e) {
    try {
      const ts = Date.now();
      const g = groupOf(c);
      if (g) {
        if (g.left) throw new Error('You are no longer in this group');
        e.groupId = g.id;
        const kind = e.kind;
        // Typing and read are never queued; neither are file chunks (too large for storage).
        fanOut(g.members, core.encodeSocial(e), ts, !['typing', 'read', 'file', 'chunk'].includes(kind));
      } else {
        if (env.links.get(c)?.state !== 'online') throw new Error('Not connected');
        if (!sendFrame(c, KIND.SOCIAL, ts, core.encodeSocial(e))) throw new Error('Not connected');
      }
      applyOwn(c, e, ts);
    } finally {
      e.free();
    }
  }

  function applyOwn(c, e, ts) {
    const me = myKey();
    switch (e.kind) {
      case 'msg':
        addMsg(c, {
          id: e.id, o: true, ts, from: me, body: e.text, replyTo: e.replyTo || null, fwd: e.forwarded,
          prec: e.precedence, exp: e.expiresS ? ts + e.expiresS * 1000 : 0, file: e.target || null, dl: 0,
        });
        break;
      case 'edit': mutate(c, e.target, (m) => (m.o && !m.deleted ? { ...m, body: e.text, edited: true } : null)); break;
      case 'del': mutate(c, e.target, (m) => (m.o ? erased(m) : null)); break;
      case 'react': mutate(c, e.target, (m) => react(m, e.text, me, e.flag)); break;
      case 'timer':
        setSettings(c, (s) => ({ ...s, timer: e.expiresS }));
        system(c, `You set disappearing messages to ${timerLabel(e.expiresS).toLowerCase()}`, ts);
        break;
      case 'pin':
        setSettings(c, (s) => ({ ...s, pins: e.flag ? s.pins.filter((p) => p !== e.target) : [...s.pins.filter((p) => p !== e.target), e.target] }));
        system(c, e.flag ? 'You unpinned a message' : 'You pinned a message', ts);
        break;
      case 'wipe':
        wipeLocal(c);
        system(c, 'You wiped this chat on every device', ts);
        break;
      default: return; // typing, read, file offers and chunks change nothing on screen here
    }
    env.onChange(c);
  }

  // ---- receiving ----------------------------------------------------------------------------
  /** Handles a decrypted frame from peer `k`; returns true if it was a Talk frame. */
  function receive(k, m) {
    if (m.kind === KIND.RECEIPT) { onReceipt(k, m.body); return true; }
    if (m.kind !== KIND.SOCIAL) return false;
    let e;
    try { e = core.decodeSocial(m.body); } catch { return true; } // malformed: ignored
    try { onSocial(k, e, m.sentAtMs); } finally { e.free(); }
    return true;
  }

  function onSocial(k, e, sentAt) {
    const gid = e.groupId;
    if (gid && e.kind === 'group') return onGroupEvent(k, e);
    const g = gid ? groups[gid] : null;
    // Group events from anyone who isn't a current member (or for a group we left) are ignored.
    if (gid && (!g || g.left || !g.members.includes(k))) return;
    const c = g ? 'g:' + gid : k;
    const admin = !g || g.admins.includes(k);
    switch (e.kind) {
      case 'file': case 'chunk': onFile(c, k, e); return;
      case 'leave': if (g) onLeave(g, k); return;
      case 'msg': {
        if (g?.channel && !admin) return; // only admins post in a unit channel
        if (!msgsOf(c).some((x) => x.id === e.id)) {
          const msg = {
            id: e.id, o: false, ts: sentAt, from: k, body: e.text, replyTo: e.replyTo || null, fwd: e.forwarded,
            prec: e.precedence, exp: e.expiresS ? Date.now() + e.expiresS * 1000 : 0, file: e.target || null,
          };
          addMsg(c, msg);
          if (msg.prec === 'O' || msg.prec === 'Z') env.onAlert(c, msg);
        }
        sendFrame(k, KIND.RECEIPT, Date.now(), String(sentAt));
        setTypingState(c, k, false);
        break;
      }
      case 'edit': mutate(c, e.target, (m) => (!m.o && m.from === k && !m.deleted ? { ...m, body: e.text, edited: true } : null)); break;
      case 'del': mutate(c, e.target, (m) => (!m.o && m.from === k ? erased(m) : null)); break;
      case 'react': mutate(c, e.target, (m) => react(m, e.text, k, e.flag)); break;
      case 'read': markReadUpTo(c, e.target); break;
      case 'typing': setTypingState(c, k, e.flag); break;
      case 'timer':
        setSettings(c, (s) => ({ ...s, timer: e.expiresS }));
        system(c, `${nameOf(k)} set disappearing messages to ${timerLabel(e.expiresS).toLowerCase()}`);
        break;
      case 'pin':
        if (!admin) return;
        setSettings(c, (s) => ({ ...s, pins: e.flag ? s.pins.filter((p) => p !== e.target) : [...s.pins.filter((p) => p !== e.target), e.target] }));
        system(c, `${nameOf(k)} ${e.flag ? 'unpinned' : 'pinned'} a message`);
        break;
      case 'wipe':
        if (!admin) return;
        wipeLocal(c);
        system(c, `${nameOf(k)} wiped this chat on every device`);
        break;
      default: return;
    }
    env.onChange(c);
  }

  function onReceipt(k, body) {
    const ts = Number(body);
    if (!Number.isFinite(ts)) return;
    const convos = [k, ...Object.values(groups).filter((g) => g.members.includes(k)).map((g) => 'g:' + g.id)];
    for (const c of convos) {
      const l = msgsOf(c);
      let hit = false;
      for (const m of l) if (m.o && m.ts === ts && !(m.dl >= 1)) { m.dl = 1; hit = true; }
      if (hit) { putMsgs(c, l); env.onChange(c); }
    }
  }

  function markReadUpTo(c, id) {
    const l = msgsOf(c);
    const t = l.findLast((m) => m.id === id);
    if (!t) return;
    let hit = false;
    for (const m of l) if (m.o && m.ts <= t.ts && !(m.dl >= 2)) { m.dl = 2; hit = true; }
    if (hit) putMsgs(c, l);
  }

  function setTypingState(c, k, on) {
    const t = typing.get(c) || new Map();
    on ? t.set(k, Date.now() + TYPING_TTL) : t.delete(k);
    t.size ? typing.set(c, t) : typing.delete(c);
  }

  function onGroupEvent(k, e) {
    const mine = myKey();
    const members = e.members.map((x) => x.toLowerCase());
    const admins = e.admins.map((x) => x.toLowerCase());
    const g = groups[e.groupId];
    const c = 'g:' + e.groupId;
    if (!g) {
      // A new group: only from a member, and only if it includes us.
      if (!members.includes(k) || !members.includes(mine)) return;
      groups[e.groupId] = { id: e.groupId, name: e.text, members, admins, channel: e.channel, left: false, ts: Date.now() };
      system(c, `${nameOf(k)} added you to ${e.channel ? 'the channel' : 'the group'} “${e.text}”`);
    } else {
      if (!g.admins.includes(k)) return; // only admins change a group
      if (!members.includes(mine)) {
        groups[e.groupId] = { ...g, left: true };
        system(c, `${nameOf(k)} removed you`);
      } else {
        if (e.text !== g.name) system(c, `${nameOf(k)} renamed it to “${e.text}”`);
        members.filter((m) => !g.members.includes(m)).forEach((m) => system(c, `${nameOf(k)} added ${nameOf(m)}`));
        g.members.filter((m) => !members.includes(m)).forEach((m) => system(c, `${nameOf(k)} removed ${nameOf(m)}`));
        admins.filter((m) => !g.admins.includes(m)).forEach((m) => system(c, `${nameOf(m)} is now an admin`));
        if (e.channel !== g.channel) system(c, e.channel ? 'Now a unit channel: only admins post' : 'Everyone can post again');
        groups[e.groupId] = { ...g, name: e.text, members, admins, channel: e.channel, left: false };
      }
    }
    saveGroups();
    env.onChange(c);
  }

  function onLeave(g, k) {
    g.members = g.members.filter((m) => m !== k);
    g.admins = g.admins.filter((m) => m !== k);
    // The last admin left: the first remaining member takes over (every device applies the same rule).
    if (!g.admins.length && g.members.length) g.admins = [g.members[0]];
    saveGroups();
    system('g:' + g.id, `${nameOf(k)} left`);
    env.onChange('g:' + g.id);
  }

  async function onFile(c, k, e) {
    const key = `${k}:${e.kind === 'file' ? e.id : e.target}`;
    if (e.kind === 'file') {
      if (e.size > MAX_FILE) return;
      inbound.set(key, { c, from: k, id: e.id, name: e.text, mime: e.mime, size: e.size, sha: e.sha256, total: e.total, parts: new Array(e.total), got: 0 });
      return;
    }
    const f = inbound.get(key);
    if (!f || e.total !== f.total || f.parts[e.index]) return;
    try { f.parts[e.index] = unb64(e.text); } catch { inbound.delete(key); return; }
    if (++f.got < f.total) return;
    inbound.delete(key);
    const blob = new Blob(f.parts, { type: f.mime });
    if (blob.size !== f.size) return;
    const digest = hex(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())));
    if (digest !== f.sha) return; // corrupted or tampered: dropped
    await putFile(fileKey(f.c, f.from, f.id), { name: f.name, mime: f.mime, size: f.size, blob });
    env.onChange(f.c);
  }

  // ---- actions (called from the chat screen) ------------------------------------------------
  const api = {
    groups: () => groups,
    groupOf,
    nameOf,
    myKey,
    msgsOf: live,
    rawMsgsOf: msgsOf,
    putMsgs,
    settingsOf,
    flush,
    receive,
    isAdmin: (c) => { const g = groupOf(c); return !g || (g.admins.includes(myKey()) && !g.left); },
    canPost(c) {
      const g = groupOf(c);
      if (!g) return env.links.get(c)?.state === 'online';
      return !g.left && (!g.channel || g.admins.includes(myKey()));
    },
    typingIn(c) {
      const t = typing.get(c);
      if (!t) return [];
      const now = Date.now();
      return [...t].filter(([, until]) => until > now).map(([k]) => nameOf(k));
    },
    unread(c) {
      const read = settingsOf(c).read;
      return live(c).filter((m) => !m.o && !m.sys && m.ts > read);
    },

    sendText(c, text, { replyTo = null, forwarded = false, precedence = 'R' } = {}) {
      send(c, social('msg', { id: rid(), text, replyTo: replyTo || '', forwarded, precedence, expiresS: settingsOf(c).timer }));
      api.setTyping(c, false);
    },
    async sendFile(c, file, { caption = '', precedence = 'R' } = {}) {
      if (!file.size || file.size > MAX_FILE) throw new Error('Files are 1 byte to 25 MB.');
      const buf = new Uint8Array(await file.arrayBuffer());
      const sha = hex(new Uint8Array(await crypto.subtle.digest('SHA-256', buf)));
      const id = rid();
      const total = core.socialChunkCount(buf.length);
      const name = (file.name || 'file').replace(/[\\/\n\r|]/g, '_').slice(0, 200) || 'file';
      const mime = /^[\w.+-]+\/[\w.+-]+$/.test(file.type) && file.type.length <= 100 ? file.type : 'application/octet-stream';
      send(c, social('file', { id, text: name, mime, size: buf.length, sha256: sha, total }));
      for (let i = 0; i < total; i++) {
        send(c, social('chunk', { target: id, text: b64(buf.subarray(i * CHUNK, (i + 1) * CHUNK)), index: i, total }));
      }
      await putFile(fileKey(c, myKey(), id), { name, mime, size: buf.length, blob: new Blob([buf], { type: mime }) });
      send(c, social('msg', { id: rid(), text: caption, target: id, precedence, expiresS: settingsOf(c).timer }));
    },
    edit: (c, id, text) => send(c, social('edit', { target: id, text })),
    deleteForEveryone: (c, id) => send(c, social('del', { target: id })),
    deleteForMe(c, id) {
      const l = msgsOf(c);
      const m = l.find((x) => x.id === id);
      putMsgs(c, l.filter((x) => x.id !== id));
      if (m?.file) fileOp('readwrite', (s) => s.delete(fileKey(c, m.from, m.file))).catch(() => {});
      env.onChange(c);
    },
    toggleReaction(c, id, emoji) {
      const m = msgsOf(c).findLast((x) => x.id === id);
      if (!m) return;
      const mine = (m.reactions?.[emoji] || []).includes(myKey());
      send(c, social('react', { target: id, text: emoji, flag: mine }));
    },
    setTimer: (c, s) => send(c, social('timer', { expiresS: s })),
    togglePin(c, id) {
      const pinned = settingsOf(c).pins.includes(id);
      send(c, social('pin', { target: id, flag: pinned }));
    },
    wipeForEveryone: (c) => send(c, social('wipe')),
    /** Called while `c` is on screen: marks it read here and tells the sender(s). */
    markRead(c) {
      const l = live(c).filter((m) => !m.o && !m.sys);
      const newest = l.at(-1);
      if (!newest) return;
      if (newest.ts > settingsOf(c).read) setSettings(c, (s) => ({ ...s, read: newest.ts }));
      if (!newest.id || lastRead.get(c) === newest.id) return;
      if (!groupOf(c) && env.links.get(c)?.state !== 'online') return;
      try { send(c, social('read', { target: newest.id })); lastRead.set(c, newest.id); } catch { /* offline */ }
    },
    setTyping(c, on) {
      const now = Date.now();
      const last = lastTyping.get(c);
      if (on && last && now - last < TYPING_RESEND) return;
      if (!on && !last) return;
      if (!api.canPost(c)) return;
      on ? lastTyping.set(c, now) : lastTyping.delete(c);
      try { send(c, social('typing', { flag: on })); } catch { /* offline */ }
    },

    // ---- groups ----
    createGroup(name, keys, channel) {
      const mine = myKey();
      const members = [...new Set([mine, ...keys.map((k) => k.toLowerCase())])];
      if (members.length < 2) throw new Error('Pick at least one contact.');
      const g = { id: rid(), name: name.trim().slice(0, 64), members, admins: [mine], channel: !!channel, left: false, ts: Date.now() };
      sendGroup(g, g.members);
      groups[g.id] = g;
      saveGroups();
      system('g:' + g.id, `You created ${channel ? 'the channel' : 'the group'} “${g.name}”`);
      return 'g:' + g.id;
    },
    /** Admin only: rename, change members, admins and channel mode. */
    updateGroup(c, { name, members, admins, channel }) {
      const g = groupOf(c);
      const mine = myKey();
      if (!g || g.left || !g.admins.includes(mine)) throw new Error('Only an admin can change this group.');
      const nm = [...new Set([mine, ...members])];
      const na = [...new Set([mine, ...admins])].filter((a) => nm.includes(a));
      const next = { ...g, name: name.trim().slice(0, 64), members: nm, admins: na, channel: !!channel };
      sendGroup(next, [...new Set([...g.members, ...nm])]);
      groups[g.id] = next;
      saveGroups();
      if (next.name !== g.name) system(c, `You renamed it to “${next.name}”`);
      nm.filter((m) => !g.members.includes(m)).forEach((m) => system(c, `You added ${nameOf(m)}`));
      g.members.filter((m) => !nm.includes(m)).forEach((m) => system(c, `You removed ${nameOf(m)}`));
      na.filter((m) => !g.admins.includes(m)).forEach((m) => system(c, `${nameOf(m)} is now an admin`));
      if (next.channel !== g.channel) system(c, next.channel ? 'Now a unit channel: only admins post' : 'Everyone can post again');
      env.onChange(c);
    },
    leaveGroup(c) {
      const g = groupOf(c);
      if (!g || g.left) return;
      const e = social('leave', { groupId: g.id });
      try { fanOut(g.members, core.encodeSocial(e), Date.now(), true); } finally { e.free(); }
      groups[g.id] = { ...g, left: true };
      saveGroups();
      system(c, 'You left');
      env.onChange(c);
    },
  };

  function sendGroup(g, targets) {
    const e = social('group', { groupId: g.id, text: g.name, members: g.members, admins: g.admins, channel: g.channel });
    try { fanOut(targets, core.encodeSocial(e), Date.now(), true); } finally { e.free(); }
  }

  return api;
}
