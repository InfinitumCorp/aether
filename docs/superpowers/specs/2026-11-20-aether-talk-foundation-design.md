# Cycle A — ÆTHER Talk foundation and security

> **Status:** Plan-ready only after the owner reviews the written spec set dated 2026-11-20.
>
> **Parent:** [2026-11-20-aether-secure-messaging-tak-design.md](2026-11-20-aether-secure-messaging-tak-design.md)
>
> **This cycle is one implementation plan.** It does not include TAK, groups, attachments, calls, multi-device, or a backup.

## Goal

Make every existing message kind buildable on the browser binding, stop the browser from storing map and field bodies, and add an offline revocation snapshot the CA can sign and the core can verify. No new cryptographic algorithm. No new visible control. No network.

## Current defects this cycle closes

1. `web/wasm/src/lib.rs` `kind_from` and `kind_to` list kinds 1–5. `MessageKind` also has Position (6) and Field (7). An exhaustive match means `aether-web` does not build against current `aether-core`. Android already encodes those kinds.
2. If the binding is fixed and `web/app/app.js` grows a default branch, a position body (coordinates) or a field body could be written to `localStorage` under `aether.msgs.*`. Today the page only stores text (kind 1) and key drops (kind 4). The receive decision must be a pure function so a later edit cannot store any other kind by accident.
3. Devices do not check certificate revocation. `aether-ca` can mark a serial in Supabase, and that path needs the network and the service key. This cycle adds a signed file a device can verify later, and does not wire the device.

## Non-goals

- No change to Noise, ML-KEM, the envelope version byte, or `MAX_BODY_BYTES`.
- No new message kind.
- No Android UI, theme, layout, font, color, or domain change. `Screens.kt` and `style.css` are untouched.
- No new Android permission.
- No Supabase call, no phone-home, and no change to registry SQL.
- The Diagnostics "require certificate" switch keeps its current meaning. A "require revocation snapshot" switch is a later spec because it is a new control.
- Browser identity and text history stay in `localStorage`. Moving them into a stronger store is a different spec. This cycle does not copy additional sensitive bodies into that store.
- No-backup policy is unchanged.

## Revocation snapshot

The snapshot answers one question: is this 16-byte certificate serial listed. Certificate signature, device binding, and certificate lifetime stay in the existing `verify_device_certificate`, which already uses `verify_strict`. This cycle does not change that function. A later Diagnostics switch, not built here, will call `verify_device_certificate` first and call the snapshot function only after that returns success. Any snapshot result other than `NotRevoked` will be a refusal. That wiring is out of scope.

Canonical bytes before the signature, big-endian:

```text
"AETHER-CRL/1"                               12 bytes
ca_key_id                                    8 raw bytes, the same octets as the certificate wire field: the first 8 bytes of SHA-256(CA public key), not the hex text
issued_at_ms                                 u64
expires_at_ms                                u64
count                                        u32
serials                                      count times 16 bytes, the device-certificate serial
```

The Ed25519 signature (64 bytes) covers those bytes and is appended. Signing and verifying use the existing `ed25519-dalek` key types and `verify_strict`, the same verification mode as `verify_device_certificate`. ÆTHER does not add a signature algorithm. `count` is 0 through 4096. Serials are unique and strictly ascending. Anything else is `Malformed`.

The file `aether-ca` writes is those raw bytes. It is not hex and not JSON.

`expires_at_ms` is greater than `issued_at_ms`. A snapshot is stale when `now_ms > expires_at_ms`. It is not yet valid when `issued_at_ms > now_ms + 300_000`. The 300 seconds is clock skew, not a security margin to stretch a snapshot's life. Both conditions return `Stale`.

The function is `revocation_status(serial: [u8; 16], snapshot: bytes, ca_public_keys: list of 32-byte keys, now_ms) -> RevocationStatus`. It does not take a certificate and does not call `verify_device_certificate`.

Verifier results, first match wins:

| Order | Input | Result |
|---|---|---|
| 1 | Bad magic, bad length, unsorted or duplicate serials, `count` not matching the bytes | `Malformed` |
| 2 | No provided CA key has this `ca_key_id` | `UntrustedSigner` |
| 3 | A provided CA key has this `ca_key_id` and `verify_strict` fails | `BadSignature` |
| 4 | `now` is outside the validity window, including the skew rule | `Stale` |
| 5 | `serial` is in the list | `Revoked` |
| 6 | Otherwise | `NotRevoked` |

The verifier considers only CA keys whose id equals `ca_key_id`. It does not try the others. It does not fetch. It does not log serials. Error `Display` text is a fixed phrase.

`aether-ca snapshot` reads `--key`, `--serials` (a file), `--issued-at`, `--expires-at`, and `--out`. Each non-empty line of the serial file is one 32-hex-character serial. An empty file is count 0. A blank line, a duplicate, an unsorted list, a non-hex line, `count` above 4096, or `expires_at_ms <= issued_at_ms` fails before signing, with a fixed phrase and a non-zero status. The command does not contact Supabase. Missing `AETHER_SUPABASE_SERVICE_KEY` is irrelevant. The CA key stays offline, as the README already requires. The command does not print the CA secret or the serial file.

Android and the browser do not load the file in this cycle. Shipping the verifier without a switch means behavior on the wire is unchanged.

## Browser kind map

`kind_from` and `kind_to` gain Position = 6 and Field = 7. No other mapping changes.

New file `web/app/inbound-kind.mjs` exports `classifyInboundKind(kind)`. It returns `"store"` for kinds 1 and 4 and `"discard"` for every other number, including 2, 3, 5, 6, 7, and values outside 1–7. `web/app/app.js` is already a module. After `session.decrypt` returns, the receive path calls this function and calls `addMsg` only for `"store"`. A decrypt throw still closes the link, which is the current `catch` in `adopt`. `"discard"` applies only to a kind that decrypt returned. Send of text and key drop is unchanged. No element is added to `index.html`. No CSS change.

The page loads the committed bundle `web/app/pkg`, and the Pages deploy publishes `web/app` without rebuilding wasm. The implementation PR regenerates that bundle with the repository's existing `wasm-bindgen` command (`--target web --out-dir ../app/pkg`) and commits `web/app/pkg`. Host tests alone do not change the bytes the page runs.

The wasm round-trip uses kind bytes 6 and 7 with the body `lab`. It does not call the position or field encoders and does not use coordinates.

## Error handling

- Wasm returns the kind and body to the page. After that return, the page keeps kinds 1 and 4 and discards every other kind. It does not parse position or field bodies, and it does not log them. A decrypt failure still closes the link. Android's existing ignore-on-malformed-position behavior is unchanged.
- Snapshot results follow the order in the table. The CLI prints a fixed phrase and a non-zero status. It does not print the CA secret or the serial file.

## Privacy and supply chain

- No new crate.
- No telemetry.
- Tests use synthetic serials. The wasm round-trip body is the three characters `lab`. Tests do not print message bodies.
- `data_extraction_rules.xml` and `allowBackup` stay as they are.

## Acceptance tests

All of these are automated. None need a device, a browser window, or a server.

1. `cargo test -p aether-web` builds `kind_to` and round-trips kind bytes 6 and 7, body `lab`, through the wasm wrapper's encrypt and decrypt on the host.
2. A text message still round-trips as kind 1.
3. Kind byte 99 still fails on encrypt.
4. `node --test web/app/inbound-kind.test.mjs` asserts `"store"` for 1 and 4 and `"discard"` for 0, 2, 3, 5, 6, 7, and 99. The helper module does not import `app.js` and does not mention `localStorage`.
5. Snapshot tests call `revocation_status` with a 16-byte serial, not a certificate: round-trip sign and verify `NotRevoked`; a listed serial is `Revoked`; a flipped signature bit is `BadSignature`; a snapshot whose `ca_key_id` matches none of the provided keys is `UntrustedSigner`; `now` past `expires_at_ms` is `Stale`; `issued_at_ms` more than 300 seconds in the future is `Stale`; unsorted serials are `Malformed` and the CLI refuses them before signing; 4097 serials are refused; an empty serial list verifies as `NotRevoked`. A bad magic wins over a bad signature (order 1 before order 3).
6. `cargo test` in `core/` still passes, including the existing handshake, replay, and certificate tests.
7. The committed `web/app/pkg/aether_web.js` maps kinds 6 and 7. The implementation PR states the `wasm-bindgen` command it ran.
8. Diff review: `Screens.kt`, theme files, `style.css`, `vercel.json`, `wrangler.jsonc`, and domain strings are untouched. Android manifest permissions are untouched.

## Implementation boundaries

Files a later plan may edit:

- `web/wasm/src/lib.rs`
- `web/app/inbound-kind.mjs` and `web/app/inbound-kind.test.mjs`
- `web/app/app.js` (the import and the receive branch only)
- `core/src/devicecert.rs` or a new `core/src/revocation.rs` with `revocation_status` public to the crate and not exported through UniFFI
- `core/src/bin/aether-ca.rs`
- `docs/ARCHITECTURE.md` one paragraph pointing at the snapshot, in the same PR as the code, not in this documentation PR

Files a later plan leaves alone: Android UI, `web/app/style.css`, TAK, Supabase migrations, the bridge.

## Done when

The acceptance tests pass in the implementation PR, wire behavior of chat is unchanged, and no device consults a snapshot until a follow-on spec adds the Diagnostics switch.
