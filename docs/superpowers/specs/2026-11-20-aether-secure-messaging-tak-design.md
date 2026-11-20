# ÆTHER secure messaging and TAK connector

> **Status:** Architecture approved by the owner on 2026-11-20. This written spec awaits owner review before any implementation plan or feature code.
>
> **Decision:** Separate encrypted ÆTHER messaging, and an explicitly chosen, server-visible TAK connector.
>
> **This document is the architecture record.** It is not an implementation plan. Two cycle specs are plan-ready after written-spec approval, and only those two:
>
> - [Cycle A — foundation and security](2026-11-20-aether-talk-foundation-design.md)
> - [Cycle B — TAK parsing lab fixtures](2026-11-20-aether-tak-fixtures-design.md)
>
> Cycles C through H need their own written specs before planning. Nothing here authorizes a merge or a deploy.

Evidence below is from the tree at `35c14a1f1846af24968ae0859ff8daad224078ad` (2026-11-14) and from public documents read on 2026-11-20. Commands were not run against a TAK server. No client version is claimed compatible.

## What is being built

ÆTHER Talk stays the end-to-end path. Peers who both run ÆTHER keep the confidentiality the core already gives a live session, and later cycles add asynchronous delivery, groups, attachments, extra devices, and calls on that path.

ATAK and iTAK are reached only when an operator selects a TAK Server connector. That connector sends Cursor on Target (CoT) to a server the organization runs. The server accepts the plaintext of what the operator chose to send. Transport security on that hop is TLS with mutual certificates. The server operator can read the CoT. Store text that says iTAK data "is secured" is not evidence of end-to-end encryption.

Telegram and WhatsApp parity means comparable in-app controls on ÆTHER. It does not mean access to those networks, their APIs, or their protocol weaknesses.

Out of scope on every cycle: weapons, targeting, fire control, casualty-evacuation workflow, geofence triggers, sensor or video tasking, tracking tools, embedding the ATAK or iTAK applications, and vendoring the ATAK source tree.

## Evidence the design rests on

Current product, from [docs/ARCHITECTURE.md](../../ARCHITECTURE.md), [README.md](../../../README.md), and the code:

- Cryptography lives in `core/`. Android is a Kotlin shell. The browser runs the same core through WebAssembly and a loopback bridge that forwards frames and does not see plaintext (`web/bridge/bridge.mjs`).
- A live session is Noise XX. Version 2 mixes an ML-KEM-768 secret into the key schedule. A contact that has completed version 2 is refused a later version 1 session. Frames must arrive in order. A replayed frame fails authentication (`core/src/session.rs`). That protection ends when the session ends. There is no ratchet, so a later compromise of the long-term identity key is not healed by the current protocol.
- The plaintext envelope is `version:u8 ‖ kind:u8 ‖ sent_at_ms:u64be ‖ utf8 body`, envelope version byte 1 (`core/src/envelope.rs`). Kinds are Text (1), Receipt (2), Ping (3), Key Drop (4), Burn (5), Position (6), Field (7). Unknown kinds fail closed. The largest body is `MAX_BODY_BYTES`.
- Android stores text, key drop, position, and field as chat records. An incoming burn is acted on and is not written to the message log. Receipt and Ping are dropped (`android/.../net/AetherNode.kt`). `send` returns false when the peer has no live link. There is no queue.
- `web/wasm/src/lib.rs` maps only kinds 1–5 in `kind_from` / `kind_to`. Rust matches are exhaustive, so that crate does not build against the current core. The checked-in `web/app/pkg/aether_web.js` still documents kinds 1–5. `web/app/app.js` stores chat text in `localStorage`. The Android vault is XChaCha20-Poly1305 under a hardware-wrapped key, with `allowBackup="false"` and backup and device-transfer excluded (`android/app/src/main/res/xml/data_extraction_rules.xml`). The browser does not have that at-rest protection.
- Device certificates are Ed25519 over the custom layout in `core/src/devicecert.rs`, not X.509. `aether-ca` can revoke a serial in the Supabase registry. Devices do not check revocation during the handshake.
- Position reports (`AETHER-POS/1`) and field messages are explicit sends to one online ÆTHER peer. They are not a TAK track.
- Supabase schema `aether` stores device public keys, bundle entitlements, and certificate serials. The migration states that keys and messages stay on the device. There is no message relay.
- The core has unit tests for the handshake, replay, certificates, vault, navigation, and field messages. Android unit tests cover Cumulus ID. There is no TAK test and no web test suite. The only workflow in `.github/workflows/` is `auto-deploy.yml`.
- Open pull requests on 2026-11-20: [#6](https://github.com/InfinitumCorp/aether/pull/6) (draft AdMob) and [#4](https://github.com/InfinitumCorp/aether/pull/4) (opt-in geo labels). Neither is part of this work. AdMob is an advertising SDK and stays out of this product.

Public TAK material used, and the limits of that material:

- The Cursor on Target Message Standard, 31 Jan 2025, Revision A, is Distribution Statement C on ASSIST (ident 284928). It is not copied here and is not a test oracle for this public repository.
- Public ATAK-CIV notes describe two framings. XML events on a stream are split on `</event>`. Binary messages start with the byte `0xbf`. A mesh header then carries a version varint. A streaming header then carries a length varint. Payload version 1 is one protobuf `TakMessage`. Sources: the `takproto` README and `commoncommo/core/impl/protobuf/protocol.txt` in `deptofdefense/AndroidTacticalAssaultKit-CIV`.
- That GitHub repository was archived on 2 May 2025. Its third-party notice at tag 4.5.1.13 lists GPL and LGPL components. ÆTHER will not vendor that tree.
- Tags on `TAK-Product-Center/atak-civ` ran through 5.5.1.10 on 1 Dec 2025. The Play listing for `com.atakmap.app.civ` and the iTAK App Store listing (version note 2.12.3 on 8 Aug 2025, iOS 17.6, publisher United States Department of the Army, TAK Product Center) describe chat, files, photos, and location sharing. Those listings are not a support matrix. A community note that mentions ATAK 5.7.0.5 is not one either.
- Community servers often use TLS on port 8089 and zip enrollment packages. Port, package layout, and iTAK versus ATAK zip shape stay operator-supplied. This spec does not fix a port or a package schema.

Cryptographic-library facts used for later cycles, not as a dependency approval:

- RFC 9420 (MLS) provides asynchronous group key agreement with epoch forward secrecy and post-compromise security.
- OpenMLS is a Rust MLS library. SRLabs reviewed it from 16 Oct 2025 and issued a final report on 3 Mar 2026. The public summary says the security issues were remediated in the versions they rechecked. That is a candidate, not a pin. The pin, the license, and the transitive crates are rechecked in the cycle that would add the dependency.
- The Double Ratchet specification gives finer per-message forward secrecy for a pair. The default below prefers one new protocol rather than two. That default is reversible until the dependency is added.

The architecture doc already says this build is not a FIPS 140-3 validated module and is not accredited. User-facing copy does not say "military-grade", "classified", or "FIPS validated".

## Two planes

```text
Plane A — ÆTHER Talk (end-to-end between ÆTHER peers)
  Android or browser
    → aether-core session (existing Noise, later an audited async protocol)
    → peer aether-core
  A relay, when one exists, carries ciphertext. It does not receive plaintext.

Plane B — TAK connector (server-visible)
  Operator confirms server and presses send
    → aether-core produces an allowlisted CoT payload from that send
    → platform TLS stack with the TAK client certificate and trust store
    → TAK Server (sees the CoT)
    → ATAK, iTAK, or another TAK client
```

Plane A never forwards a session onto plane B by itself. A burner-number session is not bridged. Bridging it would attach a TAK callsign to a number that exists to hide the operator identity. That bridge does not exist unless a later owner decision says so.

Plane B does not accept `PositionKind::OwnFix`, does not start GPS, and does not call `encode_position`. Marker publishing is cycle H and is not approved.

## Parity matrix

Priority is the order of later specs. P0 is the plan-ready work. "Existing" means in the tree today. "Platform-dependent" means ATAK, iTAK, or a future ÆTHER Node, not ÆTHER Talk.

| Priority | In-app capability | ÆTHER Talk target | TAK connector | Cycle |
|---|---|---|---|---|
| P0 | Buildable core, kinds 6 and 7 on every binding. Browser receive stores only text and key drop, which is what the page does today | Existing on Android. The wasm kind map is broken | No TAK behavior | A |
| P0 | Revocation snapshot the device can verify offline | Missing on device. CA revoke exists server-side | TAK uses its own X.509 revocation. Not this snapshot | A (library and CLI only) |
| P0 | Structural TAK parser and hostile fixtures | None | Classify frames. Production allowlist empty. No socket | B |
| P1 | Offline 1:1 delivery and receipts that are actually sent | Missing. Receipt kind is unused | Server retention is the server's policy, and it is server-visible | C |
| P1 | Replies, edits, deletes, reactions, local search | Missing | Not promised | C |
| P1 | Disappearing messages on ÆTHER devices | Missing. Local timer only. A TAK copy is unaffected | Cannot be promised after the server has the CoT | C |
| P1 | Groups, topics, member add and remove | Missing | TAK rooms are server rooms, not MLS groups | C |
| P2 | Admin of ÆTHER groups | CA profiles exist. Group admin does not | TAK Server admin is the server operator | C |
| P2 | Channels and broadcasts | Missing. ÆTHER Node is not built | Server-visible if sent | After ÆTHER Node has a spec |
| P2 | Files, photos, voice notes | Missing. One frame cannot hold them | Their clients advertise files. The server can read them | D |
| P3 | A second device receives new ÆTHER messages | Missing | A second TAK certificate is a different enrollment | E |
| P3 | History sync and backups | Forbidden by the no-backup policy | A data package is enrollment, not a message backup | Blocked on a separate backup decision |
| P4 | 1:1 voice and video | Missing | Their video links are a different system | F |
| P4 | Group calls and screen sharing | Missing | Out of the TAK allowlist | F, own spec after 1:1 calls |
| P5 | Bots | Missing | An MLS member with a service credential, later | After C |
| — | Invitations and contacts | Existing: trust on first use, safety numbers, Key Drop, pinned keys, burner numbers | A TAK callsign is a different name. Linking it takes an explicit confirm | No change in A or B |
| — | TAK text gateway | None | Operator-selected, disclosed, server-visible | G, after B and after the owner names a server |
| — | TAK marker publish | Explicit ÆTHER position share exists | Default off. Not approved | H, needs a new decision |

## Security and threat model

Assets: identity secret keys, session keys, message bodies, attachment bytes, position coordinates, TAK client private keys, CA signing keys, burner secrets, and the fact that two call signs talked.

Trust boundaries:

| Boundary | Trusted to | Not trusted with |
|---|---|---|
| ÆTHER device with the app unlocked | Plaintext of ÆTHER Talk, keys in use | — |
| Android Keystore | Wrapping the vault key | Message contents |
| Browser `localStorage` | Whatever the page writes | Hardware-backed secrecy. Today it holds the identity and chat text |
| ÆTHER peer after a finished handshake | Its own messages | The local identity secret |
| Future ciphertext relay | Opaque frames | Plaintext, if plane A is intact |
| TAK Server and any federation hop | CoT the operator sent, and the client certificate identity | ÆTHER session keys. It never receives them |
| Supabase service role | Device public keys, labels, serials, bundles | Message bodies. Clients have no policies on these tables |
| Organization CA key | Signing certificates and revocation snapshots | Online use. It stays offline, as the README already requires |

Adversaries considered: a network observer, a peer who fails the handshake, a revoked device, a replay of a frame or a certificate, a malicious CoT or zip byte string, a TAK Server operator, a person who later copies a device image, and a compromised dependency.

Current controls that stay in force:

- Noise transcript binding (`AETHER/1`, `AETHER/2`) and version downgrade refusal.
- In-session replay rejection.
- Certificate bound to the handshake key, so a certificate replayed by another key is invalid.
- Vault records bound to the vault id and index.
- No analytics SDK in the tree. Pull request #6 is not merged by this work.
- No message body or coordinate in log strings. New code follows that. `Log` and `println` of a send failure must not include the body.
- Rate limit already present for remote burn: the link drops after 3 wrong codes.

Controls this program adds, on the cycle that can enforce them:

- Offline revocation check, fail closed when the operator has required it (producer and verifier in A; device wiring is a later visible control).
- Audited async protocol for offline and group messages (C), with no new primitive invented in ÆTHER.
- Parser and archive limits (B).
- Explicit disclosure on every TAK send (G).
- Encrypted attachment blobs (D) using an AEAD already in the dependency set (`chacha20poly1305`), with keys from the session protocol, not a new cipher.

Accepted gaps, stated so they are not implied away:

- Live sessions have no post-compromise security.
- The browser stores plaintext messages.
- `FLAG_SECURE` is release-only, per the README.
- An attacker who can truncate the vault file can drop the newest records. The architecture doc already says a Keystore counter would close that. It is not this program.
- Disappearing messages cannot delete a TAK Server copy.
- MLS post-compromise security applies only after the compromised member's update is processed by the others.

## Cryptographic library rules

These rules apply when a cycle adds cryptography. Cycle A and cycle B add none.

1. Use a maintained implementation of a public standard. Preferred licenses are Apache-2.0 or MIT, including dual license "OR MIT". GPL and LGPL crates are rejected unless the owner accepts that license in writing.
2. The pinned version has a public independent review, the issues called security issues are fixed in that pin, and the review is re-read at the time of the dependency PR.
3. ÆTHER code calls the library. It does not reimplement a KDF, ratchet, AEAD, or signature scheme. A new byte layout signed by the existing Ed25519 implementation, as device certificates already are, is a message format, not a new algorithm.
4. Transitive dependencies are reviewed in the same PR: license, crates.io checksum, no telemetry, no surprise native code.
5. The lockfile is updated only in that PR.

Default for cycle C, reversible until that dependency lands: one MLS stack for 1:1 and for groups, with OpenMLS as the candidate to re-verify. A Double Ratchet remains acceptable if the owner chooses it in the cycle C spec. Cycle C does not start from this document.

Identity keys stay X25519. TAK client certificates stay X.509 PKCS#12 issued by the TAK PKI. ÆTHER certificates are not presented to a TAK Server. TAK certificates are not presented on an ÆTHER handshake.

## Component boundaries

| Unit | Responsibility | Must not |
|---|---|---|
| `core` session, envelope, vault, identity, device cert | Existing plane A cryptography and storage | Open sockets. Log bodies. Grow TAK policy |
| `core` revocation snapshot (cycle A) | Sign and verify a serial list with the existing Ed25519 CA key | Fetch the network. Decide UI policy |
| `web/wasm` | Map every `MessageKind` and move bytes | Store history. Render maps |
| `web/app/app.js` | Ignore kinds 6 and 7 without writing them to `localStorage` (cycle A) | Change layout, colors, fonts, or domains |
| Android UI | Existing screens | New controls in cycles A and B |
| `core` TAK classifier (cycle B) | Return a class and a fault. No coordinates and no remarks text | Connect. Emit CoT. Call position encoders |
| TAK gateway (cycle G, not specified here) | TLS using `rustls` or the platform stack, disclosure, operator confirm | Silent send. Own-position publish. Burner bridging |
| `aether-ca` | Offline CA, including a local snapshot command in cycle A | Embed the CA key in an app |

Data flow for a cycle A revocation snapshot:

```text
Offline CA key → aether-ca writes a signed serial list → operator moves the file
  → a future device control loads it → core verifier returns Revoked or NotRevoked
```

Data flow for a cycle B fixture:

```text
Byte string in a unit test → classifier → TakClass
```

There is no third box.

## Error handling

| Condition | Result |
|---|---|
| Unknown envelope kind or bad UTF-8 | `AetherError::malformed`. Session stays up only if the transport authenticated. The body is not logged |
| Peer offline | `send` stays false. No queue until cycle C |
| Version 1 offered to a version 2 contact | Hang up before revealing the local identity, as today |
| Certificate missing when require is on | Hang up, as today |
| Revocation snapshot missing or expired when require-revocation is on | Fail closed. Cycle A only implements the function. The app does not turn the mode on |
| Revoked serial | `Revoked`. A later wiring hangs up before message 3 on the dialer side |
| TAK bytes over the cap, bad varint, length mismatch, DOCTYPE, unresolved entity | `Malformed`. No partial application output |
| XML event with a `point` element or a `lat` or `lon` attribute, any ASCII case | `RejectedPosition`. Coordinates are not in the return value |
| XML event otherwise well formed | `StructuralOk`. There is no allowlist API, so this is not sendable |
| Zip path with `..`, absolute path, link, encryption flag, too many entries, or an expansion over the cap, when the archive inspector is included | Reject the whole archive |
| TAK TLS or certificate failure | Cycle G. The connector surfaces a generic failure and does not include the server certificate or the CoT in a log |

## Privacy

- No analytics, session replay, Intercom, or cloud memory is added.
- No payload or location logs.
- Cycles A and B do not add a network call.
- Browser handling of kinds 6 and 7 does not copy those bodies into `localStorage`.
- The no-backup policy stays: `allowBackup="false"`, extraction rules, `noBackupFilesDir`. A backup or history-sync feature requires a separate owner decision. This spec does not approve one.
- Pull request #4's geo labels are a separate privacy choice and are not enabled here.

## Supply chain and licensing

- Cycles A and B add no cryptographic crate.
- Cycle B may add one zip reader only if that PR's license check passes (Apache-2.0 or MIT, no telemetry). If it fails, the archive inspector waits and the frame classifier still ships.
- ATAK-CIV is not a dependency.
- The Distribution Statement C standard is not committed.
- Fixtures are synthetic. No real PKCS#12, no customer certificate, no production hostname.

## Visible controls and permissions

Cycles A and B add no control and no permission. Existing styling, layout, colors, fonts, and domains stay as they are.

Later cycles, if their specs are approved, add controls. Disclosure text is one of those controls. It is not removed to keep a screen looking unchanged.

| Later control | Permission | Cycle |
|---|---|---|
| Diagnostics switch "require a current revocation snapshot" | None | After A, own small spec |
| Attachment pick via the platform picker | Avoid a broad storage permission. The picker is the default | D |
| Voice and video call buttons | `RECORD_AUDIO`, `CAMERA`, foreground-service microphone and camera types | F |
| Screen share | Media projection, named on its own spec | After F |
| TAK enrollment import, server confirm, and a send line that states the server can read the message | None beyond existing `INTERNET` | G |
| "Publish this marker to the TAK Server" | Location is already on the manifest for Maps. This use is a separate control and stays off | H |

`ACCESS_FINE_LOCATION` is not used by the TAK connector in any approved cycle.

## Test gates

| Gate | Where it runs | What it proves |
|---|---|---|
| `cargo test` for the core, including new snapshot and classifier tests | Implementation PR of A or B | Library behavior |
| `cargo test -p aether-web` | Cycle A | Kinds 6 and 7 round-trip and the wasm crate builds on the host |
| Hostile XML and zip fixtures | Cycle B | Parser and archive limits |
| Real ATAK and real iTAK | Not CI. A device lab named by the owner | Nothing, until cycle G exists and the owner names versions |

Automated fixtures do not prove that a shipping ATAK or iTAK build will accept a frame.

## Cycles

| Cycle | Plan from | Delivers | Does not deliver |
|---|---|---|---|
| A | Foundation spec, after this review | Wasm kind map. Browser receive stores only kinds 1 and 4. Offline revocation snapshot sign and verify. `aether-ca` writes a snapshot with the local CA key | UI, network, TAK, new crypto, device enforcement |
| B | Fixtures spec, after this review. Independent of A | Classifier and archive inspector, synthetic fixtures, empty allowlist | Socket, enrollment UI, protobuf `TakMessage`, client compatibility |
| C | A new spec | Async 1:1, groups, receipts, edits, reactions, local search, local disappearing timers | TAK, backups |
| D | A new spec | Encrypted attachments and voice notes | Calls |
| E | A new spec | Extra device receives new messages | History sync |
| F | A new spec | 1:1 calls whose media keys are not visible to a forwarder. If that cannot be done, the call is labeled relay-visible and the operator opts in | Group calls, screen sharing |
| G | A new spec after B, and after the owner names the server and the client builds | Enrollment import and disclosed text send | Position publish |
| H | A new owner decision | One marker the operator just chose | Own-position streaming |

## Owner choices still open

Defaults are safe and reversible until the cycle that would make them code.

| Choice | Default while it stays open |
|---|---|
| MLS only, or a Double Ratchet for 1:1 plus MLS for groups | One MLS stack, candidate OpenMLS, re-verified in cycle C. No dependency now |
| Encrypted backups or history sync | Stay forbidden |
| Which ATAK-CIV and iTAK versions to support | No version is supported. Cycle B claims none |
| Use of the 31 Jan 2025 CoT standard as an oracle | Not in this repository |
| Protobuf `TakMessage` | Not in cycle B. License review first |
| Cumulus ID as the only enrollment | Unchanged. Self-generated keys still work |
| An ÆTHER iOS app | Out of scope. iTAK is a future TAK peer, not an ÆTHER port |
| Air-gap store-and-forward onto TAK | Not built. Mesh delivery stays on plane A |
| TAK federation | One configured server, when cycle G exists |
| Bridging burner numbers | Not built |
| Marker publish | Off. Cycle H is not approved |
| AdMob pull request #6 | Not part of this work |

## Claims this spec does not make

- No ATAK or iTAK version works with ÆTHER today.
- No TAK path is end-to-end encrypted.
- No FIPS, classified, or accredited status.
- Cycle A and cycle B do not deliver messaging parity.
- Community TAK ports and zip layouts are not a standard.
- The OpenMLS audit is not a dependency approval.
- Browser storage is not the Android vault.

## Written-spec review

Please review this file and the two cycle specs. Implementation planning starts only after that review, and only for cycle A and cycle B. Architecture approval is not merge or deploy authorization.
