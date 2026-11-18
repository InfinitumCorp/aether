// End-to-end test of ÆTHER Talk in the browser client: three operators, real Noise sessions from
// the WebAssembly core, frames passed in memory instead of through the bridge.
// Run: node web/test/talk.test.mjs
import fs from 'node:fs';
import assert from 'node:assert/strict';
import init, * as core from '../app/pkg/aether_web.js';
import { KIND, createTalk } from '../app/talk.js';

await init({ module_or_path: fs.readFileSync(new URL('../app/pkg/aether_web_bg.wasm', import.meta.url)) });
const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

function operator(name) {
  const data = new Map();
  const store = {
    get: (k, d) => (data.has(k) ? JSON.parse(data.get(k)) : d),
    set: (k, v) => data.set(k, JSON.stringify(v)),
  };
  const o = { name, identity: core.Identity.generate(), store, links: new Map(), contacts: {}, alerts: [] };
  o.key = hex(o.identity.publicKey());
  o.talk = createTalk({ core, store, me: () => o, contacts: () => o.contacts, links: o.links, onChange: () => {}, onAlert: (c, m) => o.alerts.push([c, m.prec]) });
  return o;
}

// Frames wait in a queue (like the network) until pump() delivers them, in order.
const wireQueue = [];
function pump() { while (wireQueue.length) wireQueue.shift()(); }

/** Connects a and b with a real Noise XX session. */
function connect(a, b) {
  const i = core.Handshake.initiator(a.identity);
  const r = core.Handshake.responder(b.identity);
  r.readMessage(i.writeMessage(new Uint8Array()));
  i.readMessage(r.writeMessage(new Uint8Array()));
  r.readMessage(i.writeMessage(new Uint8Array()));
  const sa = i.finish();
  const sb = r.finish();
  const wire = (from, to, session, peerSession) => ({
    state: 'online',
    session,
    fs: { send: (frame) => wireQueue.push(() => { const m = peerSession.decrypt(frame); to.talk.receive(from.key, m); }) },
  });
  a.links.set(b.key, wire(a, b, sa, sb));
  b.links.set(a.key, wire(b, a, sb, sa));
  a.contacts[b.key] = { name: b.name };
  b.contacts[a.key] = { name: a.name };
}
const disconnect = (a, b) => { a.links.delete(b.key); b.links.delete(a.key); };

const alpha = operator('Alpha');
const bravo = operator('Bravo');
const charlie = operator('Charlie');
connect(alpha, bravo);
connect(alpha, charlie);
connect(bravo, charlie);

// Direct chat: message, receipt, reply, edit, reaction, read, typing, delete, precedence alert.
alpha.talk.sendText(bravo.key, 'Contact at grid 4471', { precedence: 'Z' });
pump();
let a1 = alpha.talk.msgsOf(bravo.key)[0];
let b1 = bravo.talk.msgsOf(alpha.key)[0];
assert.equal(b1.body, 'Contact at grid 4471');
assert.equal(b1.prec, 'Z');
assert.equal(a1.dl, 1, 'delivery receipt');
assert.deepEqual(bravo.alerts, [[alpha.key, 'Z']]);
bravo.talk.sendText(alpha.key, 'Copy', { replyTo: b1.id });
pump();
assert.equal(alpha.talk.msgsOf(bravo.key)[1].replyTo, a1.id);
alpha.talk.edit(bravo.key, a1.id, 'Contact at grid 4472');
pump();
assert.equal(bravo.talk.msgsOf(alpha.key)[0].body, 'Contact at grid 4472');
assert.ok(bravo.talk.msgsOf(alpha.key)[0].edited);
bravo.talk.toggleReaction(alpha.key, a1.id, '🎯');
pump();
assert.deepEqual(alpha.talk.msgsOf(bravo.key)[0].reactions, { '🎯': [bravo.key] });
bravo.talk.toggleReaction(alpha.key, a1.id, '🎯');
pump();
assert.deepEqual(alpha.talk.msgsOf(bravo.key)[0].reactions, {});
bravo.talk.markRead(alpha.key);
pump();
assert.equal(alpha.talk.msgsOf(bravo.key)[0].dl, 2, 'read receipt');
bravo.talk.setTyping(alpha.key, true);
pump();
assert.deepEqual(alpha.talk.typingIn(bravo.key), ['Bravo']);
alpha.talk.deleteForEveryone(bravo.key, a1.id);
pump();
assert.ok(bravo.talk.msgsOf(alpha.key)[0].deleted);
assert.equal(bravo.talk.msgsOf(alpha.key)[0].body, '');

// Pins and the disappearing timer.
alpha.talk.togglePin(bravo.key, bravo.talk.msgsOf(alpha.key)[1].id);
pump();
assert.equal(bravo.talk.settingsOf(alpha.key).pins.length, 1);
alpha.talk.setTimer(bravo.key, 30);
pump();
assert.equal(bravo.talk.settingsOf(alpha.key).timer, 30);
alpha.talk.sendText(bravo.key, 'Burns in 30 s');
pump();
assert.ok(bravo.talk.msgsOf(alpha.key).at(-1).exp > Date.now());

// Wipe for everyone.
bravo.talk.wipeForEveryone(alpha.key);
pump();
assert.deepEqual(alpha.talk.msgsOf(bravo.key).map((m) => m.sys), [true]);
assert.equal(alpha.talk.settingsOf(bravo.key).pins.length, 0);

// A unit channel: Alpha is admin; Bravo and Charlie can read but not post.
const ch = alpha.talk.createGroup('2nd Platoon', [bravo.key, charlie.key], true);
pump();
const gid = ch.slice(2);
assert.equal(bravo.talk.groups()[gid].name, '2nd Platoon');
assert.ok(charlie.talk.groups()[gid].channel);
assert.equal(bravo.talk.canPost(ch), false);
alpha.talk.sendText(ch, 'Move at 0400', { precedence: 'O' });
pump();
assert.equal(charlie.talk.msgsOf(ch).at(-1).body, 'Move at 0400');
assert.deepEqual(charlie.alerts, [[ch, 'O']]);
// A forged post from a non-admin is dropped by the others.
const forged = new core.Social('msg');
forged.id = 'forged-1'; forged.text = 'Retreat'; forged.groupId = gid;
const fb = core.encodeSocial(forged);
charlie.talk.receive(bravo.key, { kind: KIND.SOCIAL, sentAtMs: Date.now(), body: fb });
pump();
assert.ok(!charlie.talk.msgsOf(ch).some((m) => m.body === 'Retreat'));

// Promote Bravo, open the channel; Bravo posts, pins and the group update reaches everyone.
alpha.talk.updateGroup(ch, { name: '2nd Platoon', members: [bravo.key, charlie.key], admins: [bravo.key], channel: false });
pump();
assert.deepEqual(charlie.talk.groups()[gid].admins.sort(), [alpha.key, bravo.key].sort());
assert.equal(charlie.talk.canPost(ch), true);
bravo.talk.sendText(ch, 'On it');
pump();
const onIt = alpha.talk.msgsOf(ch).at(-1);
assert.equal(onIt.from, bravo.key);
charlie.talk.togglePin(ch, onIt.id); // Charlie is not an admin: ignored by the others
pump();
assert.equal(alpha.talk.settingsOf(ch).pins.length, 0);
bravo.talk.togglePin(ch, onIt.id);
pump();
assert.equal(charlie.talk.settingsOf(ch).pins.length, 1);

// Offline member: events are queued and delivered on reconnect.
disconnect(alpha, charlie);
alpha.talk.sendText(ch, 'Charlie, report when back');
pump();
assert.ok(!charlie.talk.msgsOf(ch).some((m) => m.body === 'Charlie, report when back'));
connect(alpha, charlie);
alpha.talk.flush(charlie.key);
pump();
assert.ok(charlie.talk.msgsOf(ch).some((m) => m.body === 'Charlie, report when back'));

// Remove Charlie; Charlie's later posts are ignored. Then wipe the group for everyone.
alpha.talk.updateGroup(ch, { name: 'Alpha + Bravo', members: [bravo.key], admins: [bravo.key], channel: false });
pump();
assert.ok(charlie.talk.groups()[gid].left);
assert.equal(bravo.talk.groups()[gid].name, 'Alpha + Bravo');
alpha.talk.wipeForEveryone(ch);
pump();
assert.equal(bravo.talk.msgsOf(ch).filter((m) => !m.sys).length, 0);

// Leave: the last admin leaving hands the group to the first remaining member.
const g2 = bravo.talk.createGroup('Recon', [alpha.key, charlie.key], false);
pump();
bravo.talk.leaveGroup(g2);
pump();
assert.deepEqual(alpha.talk.groups()[g2.slice(2)].admins, [alpha.key]);

console.log('talk.test.mjs: all checks passed');
