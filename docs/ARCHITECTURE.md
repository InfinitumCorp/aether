# ÆTHER MIL™ architecture

ÆTHER MIL™ keeps units talking, navigating and sharing data when networks are jammed, damaged
or absent. Every device is managed by the customer, every message is end-to-end encrypted, and
every server and gateway belongs to the military organization that runs it. It is the
communications layer of ODIN°™ and has no commercial edition.

## One security core, accredited for the field

All cryptography lives in `core/` (Rust). ÆTHER Mobile MIL™ (Android now, iOS on managed devices
later) is a thin shell that moves opaque byte buffers between transports, the UI and the core.

```
 ┌──────────────── ÆTHER Mobile MIL™ (Kotlin / Compose) ───────────┐
 │  UI ── AetherNode ── PeerLink ── Wi-Fi/LAN · (planned) mesh,    │
 │              │            │        approved radio, gateway      │
 │            Store       framing (u16 length prefix)              │
 │              │            │                                     │
 │  KeyWrapper (Android Keystore AES-GCM, StrongBox / TEE)         │
 └──────────────┼────────────┼─────────────────────────────────────┘
                │  UniFFI    │
 ┌──────────────▼────────────▼──── aether-core (Rust) ─────────────┐
 │  Identity   Handshake/Session (Noise XX)   ChatMessage   Vault  │
 └─────────────────────────────────────────────────────────────────┘
```

## The ten systems: status in this repository

| System | Function (product page) | Status |
|---|---|---|
| ÆTHER Mobile MIL™ | Hardened, organization-controlled Android application | **Built**: app shell, hardening, diagnostics. Organization enrollment and managed-device policy not yet. A browser client (ÆTHER MIL™ Web, `web/`) runs the same core as WebAssembly and reaches nodes through a local frame bridge |
| ÆTHER Talk™ | Encrypted messaging, voice, video, files, push-to-talk, on-device AI | **Partial**: 1:1 encrypted text, Key Drop, burner numbers (text). Groups, PTT, voice/video, files, on-device AI not yet |
| ÆTHER Maps™ | Offline GPS, topographic maps, waypoints, routes | Not started |
| ÆTHER Radio™ | Interfaces for approved military digital radios | Not started |
| ÆTHER Mesh™ | Authenticated peer-to-peer and multi-hop field networking | **Partial**: authenticated single-hop over Wi-Fi/LAN with mDNS discovery. Multi-hop relay not yet |
| ÆTHER Node™ | Portable relays and ruggedized network appliances | Not started (the Rust core is designed to run on nodes) |
| ÆTHER Mail™ | Internal encrypted email on organization domains | Not started |
| ÆTHER Vault™ | Encrypted local storage and digital identity | **Built**: XChaCha20-Poly1305 vault, hardware-wrapped key, device identity |
| ÆTHER Command™ | Central device administration and communications management | Not started |
| ÆTHER Cloud™ | Sync via Cumulus://Cloud MIL™ in government regions or air-gapped | Not started |

## Protocol (v2, hybrid post-quantum; v1 fallback)

| Layer | Choice | Why |
|---|---|---|
| Identity | X25519 static key per device | Same key authenticates Noise sessions |
| Session (v2, default) | `Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s`, prologue `AETHER/2`, plus ML-KEM-768 (FIPS 203) | Everything v1 gives, and transport keys also depend on an ML-KEM secret: recorded traffic stays safe unless X25519 *and* ML-KEM-768 are both broken |
| Session (v1, fallback) | `Noise_XX_25519_ChaChaPoly_BLAKE2s`, prologue `AETHER/1` | For peers that predate v2 |
| Device certificates | Ed25519 CA signs `serial ‖ device key ‖ profile ‖ validity ‖ label` (`core/src/devicecert.rs`, `aether-ca` CLI) | Organization-issued trust in device keys; cfssl-style profiles (node 1 y, relay 1 y, operator 90 d) and serials for revocation |
| Framing | `len:u16be ‖ bytes` | Matches Noise's 65 535-byte message limit |
| Envelope | `version:u8 ‖ kind:u8 ‖ sent_at_ms:u64be ‖ utf8 body` | Versioned; kinds Text / Receipt / Ping / KeyDrop / Burn |
| Burner numbers | One X25519 identity per number; number = 10 digits from `SHA-256("AETHER-NUMBER-v1" ‖ key)` | Self-authenticating; the dialer checks the responder's key after message 2 and hangs up before revealing itself. See [BURNER_NUMBERS.md](BURNER_NUMBERS.md) |
| Key Drop | body `AETHER-DROP/1 ⏎ codename ⏎ name ⏎ key hex ⏎ addr,addr` | Validated in Rust (`core/src/keydrop.rs`); sent only inside a session, so it inherits the session's confidentiality and authenticity |
| Verification | 60-digit safety number from both keys (iterated SHA-256) | Out-of-band MITM detection |

Handshake v2: `→ e (+ML-KEM-768 encapsulation key, fresh per session)`,
`← e, ee, s, es (+ML-KEM ciphertext, display name)`, `→ s, se, psk (+display name)`, where the
`psk` is the ML-KEM shared secret. In a psk handshake Noise keys the cipher from `e`, so the
encapsulation key in message 1 is already AEAD-protected and bound to the transcript.
Handshake v1 is the same without the ML-KEM payloads and the psk.
Display names are sent only after the peer's static key is authenticated and encrypted.

**Version negotiation and downgrade protection.** A responder reads the version from the length of
message 1 (32 bytes = v1, 1232 = v2) and answers in kind. A dialer tries v2 first and retries with
v1 only if the v2 handshake fails. Once a contact has completed v2, clients remember it
(`pq` on the contact) and refuse v1 with that key in either direction: a dialer hangs up after
message 2, before revealing itself; a responder drops the finished session. The distinct protocol
name and prologue stop a v1 transcript from being accepted as v2.

**Device certificates.** `aether-ca init --key ca.key` creates an organization CA (keep the key
offline); `aether-ca sign --key ca.key --device <key hex> --label NAME [--profile node|relay|operator]
[--expiry 8760h]` issues a certificate as JSON; `aether-ca verify` checks one. The core exports
`verify_device_certificate` to Android and the browser. The registry of CAs and issued serials
lives in `aether.certificate_authorities` / `aether.device_certificates`
(`supabase/migrations/20261108120000_aether_device_certificates.sql`, applied 2026-11-08 with the base `aether` schema).
Built with `--features registry`, `aether-ca` keeps that registry current: `init --register` and
`register-ca` record a CA, `sign --register` records the certificate and enrols the device in
`aether.devices`, `revoke` and `status` manage serials, and `verify --online` also fails on a
revoked serial. It calls service-role-only RPC functions in `public`
(`supabase/migrations/20261108130000_aether_registry_functions.sql`; the `aether` schema itself is
not exposed to the API) and reads the key from `AETHER_SUPABASE_SERVICE_KEY`.
Exchanging and enforcing certificates during the handshake is the next step.

## Storage (ÆTHER Vault™)

`Vault` is an append-only file: `MAGIC ‖ vault_id ‖ key-check ‖ records`. Each record is
XChaCha20-Poly1305 sealed with AAD `vault_id ‖ index`, so records can't be moved between
vaults or reordered. A torn final write is truncated on open; any other corruption is an error.

The vault key is 32 random bytes, wrapped by a non-exportable Android Keystore AES-256-GCM key
and stored in `noBackupFilesDir`. Two vaults exist: `identity.vault` and `messages.vault`.

## Gaps against the product page (deliberate, for this first build)

- **Organization-controlled identity.** Users create their own key today. The CA and device
  certificates now exist (`aether-ca`), but devices don't yet present certificates in the
  handshake or require them from peers; that, plus an enrollment flow tied to Cumulus://ID MIL™,
  is next.
- **Trust on first use.** A new peer is accepted and shown unverified until safety numbers are
  compared, or until its key is pinned from a Key Drop (a drop is as trustworthy as the teammate
  who sent it; dialing a pinned contact rejects any other key). With organization-signed keys this becomes automatic; key-change warnings come first.
- **Interactive sessions only.** Offline delivery when connectivity returns needs asynchronous
  sessions (prekeys + Double Ratchet, or MLS for groups and PTT channels).
- **Single hop.** Multi-hop relay over ÆTHER Mesh™ and approved radios are not built yet.
- **Truncation.** An attacker with file access can drop the newest vault records (not forge or
  reorder them). A monotonic counter in the Keystore would close this.
- **Mission records.** Tamper-evident chain of custody on Firmament Network MIL™ is not integrated.
- **Accreditation.** Not yet independently evaluated; the cryptography does not run in a
  FIPS 140-3 validated module. Each deployment requires independent evaluation, accreditation
  and operational testing with the customer before operational use.

ÆTHER MIL™ has no connection to commercial data monetization, advertising or public AI inference.
