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
| ÆTHER Mobile MIL™ | Hardened, organization-controlled Android application | **Built**: app shell, hardening, diagnostics. Organization enrollment and managed-device policy not yet |
| ÆTHER Talk™ | Encrypted messaging, voice, video, files, push-to-talk, on-device AI | **Partial**: 1:1 encrypted text, Key Drop, burner numbers (text). Groups, PTT, voice/video, files, on-device AI not yet |
| ÆTHER Maps™ | Offline GPS, topographic maps, waypoints, routes | Not started |
| ÆTHER Radio™ | Interfaces for approved military digital radios | Not started |
| ÆTHER Mesh™ | Authenticated peer-to-peer and multi-hop field networking | **Partial**: authenticated single-hop over Wi-Fi/LAN with mDNS discovery. Multi-hop relay not yet |
| ÆTHER Node™ | Portable relays and ruggedized network appliances | Not started (the Rust core is designed to run on nodes) |
| ÆTHER Mail™ | Internal encrypted email on organization domains | Not started |
| ÆTHER Vault™ | Encrypted local storage and digital identity | **Built**: XChaCha20-Poly1305 vault, hardware-wrapped key, device identity |
| ÆTHER Command™ | Central device administration and communications management | Not started |
| ÆTHER Cloud™ | Sync via Cumulus://Cloud MIL™ in government regions or air-gapped | Not started |

## Protocol (v1)

| Layer | Choice | Why |
|---|---|---|
| Identity | X25519 static key per device | Same key authenticates Noise sessions |
| Session | `Noise_XX_25519_ChaChaPoly_BLAKE2s`, prologue `AETHER/1` | Mutual auth, identity hiding, forward secrecy, widely analysed |
| Framing | `len:u16be ‖ bytes` | Matches Noise's 65 535-byte message limit |
| Envelope | `version:u8 ‖ kind:u8 ‖ sent_at_ms:u64be ‖ utf8 body` | Versioned; kinds Text / Receipt / Ping / KeyDrop / Burn |
| Burner numbers | One X25519 identity per number; number = 10 digits from `SHA-256("AETHER-NUMBER-v1" ‖ key)` | Self-authenticating; the dialer checks the responder's key after message 2 and hangs up before revealing itself. See [BURNER_NUMBERS.md](BURNER_NUMBERS.md) |
| Key Drop | body `AETHER-DROP/1 ⏎ codename ⏎ name ⏎ key hex ⏎ addr,addr` | Validated in Rust (`core/src/keydrop.rs`); sent only inside a session, so it inherits the session's confidentiality and authenticity |
| Verification | 60-digit safety number from both keys (iterated SHA-256) | Out-of-band MITM detection |

Handshake: `→ e`, `← e, ee, s, es (+display name)`, `→ s, se (+display name)`.
Display names are sent only after the peer's static key is authenticated and encrypted.

## Storage (ÆTHER Vault™)

`Vault` is an append-only file: `MAGIC ‖ vault_id ‖ key-check ‖ records`. Each record is
XChaCha20-Poly1305 sealed with AAD `vault_id ‖ index`, so records can't be moved between
vaults or reordered. A torn final write is truncated on open; any other corruption is an error.

The vault key is 32 random bytes, wrapped by a non-exportable Android Keystore AES-256-GCM key
and stored in `noBackupFilesDir`. Two vaults exist: `identity.vault` and `messages.vault`.

## Gaps against the product page (deliberate, for this first build)

- **Organization-controlled identity.** Users create their own key today. The product calls for
  organization-issued Cumulus://ID MIL™ credentials and controlled provisioning; this needs an
  enrollment flow and an organization CA that signs device keys.
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
