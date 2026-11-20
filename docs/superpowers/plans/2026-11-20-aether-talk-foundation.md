# ÆTHER Talk foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make browser bindings accept existing message kinds 6 and 7, store only text and key drops from inbound frames, and add an offline Ed25519 revocation snapshot the CA can sign and the core can verify.

**Architecture:** Kind mapping stays a match in `web/wasm/src/lib.rs`. The page decides persistence in a pure function and still closes the link when decrypt throws. The snapshot is a new byte layout signed by the existing `ed25519-dalek` CA key; `verify_device_certificate` is not changed and no device loads the file.

**Tech Stack:** Rust `aether-core` 0.1.0 (edition 2021), `ed25519-dalek` 2.x already in `core/Cargo.toml`, wasm-bindgen 0.2.100, Node 22 for `node --test`. No new crate.

## Global Constraints

- No change to Noise, ML-KEM, the envelope version byte, or `MAX_BODY_BYTES`.
- No new message kind.
- No Android UI, theme, layout, font, color, or domain change. `Screens.kt` and `style.css` are untouched.
- No new Android permission.
- No Supabase call, no phone-home, and no change to registry SQL.
- The Diagnostics "require certificate" switch keeps its current meaning. A "require revocation snapshot" switch is a later spec because it is a new control.
- Browser identity and text history stay in `localStorage`. This cycle does not copy additional sensitive bodies into that store.
- No-backup policy is unchanged.
- No new crate.
- No telemetry.
- Tests use synthetic serials. The wasm round-trip body is the three characters `lab`. Tests do not print message bodies.
- `data_extraction_rules.xml` and `allowBackup` stay as they are.
- Do not claim FIPS, classified handling, or accreditation. The snapshot uses the same `verify_strict` path as device certificates. It is not a validated module.
- Signing and verifying use the existing `ed25519-dalek` key types and `verify_strict`. ÆTHER does not add a signature algorithm.

---

## Current interfaces this plan uses

- `core/src/envelope.rs`: `MessageKind` bytes Text 1, Receipt 2, Ping 3, KeyDrop 4, Burn 5, Position 6, Field 7. Envelope version byte is `PROTOCOL_VERSION` = 1.
- `core/src/devicecert.rs`: `CertificateAuthority` holds a private `ed25519_dalek::SigningKey`. `issue` already calls `self.key.sign`. `key_id` is a private `fn key_id(ca_public: &[u8; 32]) -> [u8; 8]` equal to the first 8 bytes of SHA-256 of the CA public key. `verify_device_certificate` calls `vk.verify_strict`. Wire `ca_key_id` is those 8 raw bytes, not the hex `CertificateAuthority::key_id()` string.
- `core/src/error.rs`: `AetherError::malformed` is `pub(crate)` and formats as `malformed message: {reason}`. Snapshot results are a separate enum, not new `AetherError` variants, so UniFFI does not grow.
- `core/src/bin/aether-ca.rs`: `run(args: &[String]) -> Result<String, String>` parses `--flag value`. `load_ca` reads a hex secret file via `CertificateAuthority::from_secret`. Success prints the `Ok` string. Failure prints `aether-ca: {e}` on stderr and exits non-zero. Today the usage string is `init|sign|verify|info|register-ca|revoke|status`.
- `core/src/lib.rs` module list ends `mod nav; mod session; mod vault;`. There is no `mod revocation`.
- `web/wasm/src/lib.rs` `kind_from` / `kind_to` list kinds 1–5 only. `Session::encrypt(kind: u8, sent_at_ms: f64, body: String)` and `decrypt` return `Message { kind, sent_at_ms, body }`. `web/wasm/Cargo.toml` lib crate-type is `["cdylib"]` only. Package name is `aether-web`.
- `web/app/app.js` line 3 imports the wasm glue. `adopt` (about line 161) decrypts in a loop; the `catch` closes the link. It calls `addMsg` only for `KIND.TEXT` (1) and `KIND.DROP` (4). `addMsg` writes `localStorage` key `aether.msgs.` + contact. `index.html` loads `app.js` as `type="module"`.
- `web/app/pkg/aether_web.js` is the committed bundle the page and Pages deploy actually load. Host tests do not replace it.
- Android already encodes kinds 6 and 7 in `AetherNode.kt`. This plan does not edit Android.

Enforcement policy, documented on the function and not wired: a later Diagnostics switch calls `verify_device_certificate` first. Only after that returns success does it call `revocation_status`. Every status other than `NotRevoked` is a refusal. This plan does not add that switch.

The CA binary is a separate crate from the library, so `revocation_status` is `pub` and has no `#[uniffi::export]`. `pub(crate)` would be invisible to `aether-ca`.

## File map

| File | Action |
|---|---|
| `core/src/revocation.rs` | Create. Canonical bytes, sign body, `revocation_status` |
| `core/src/devicecert.rs` | Add `sign_revocation_snapshot` on `CertificateAuthority` |
| `core/src/lib.rs` | `mod revocation;` and `pub use` the status API. No UniFFI |
| `core/src/bin/aether-ca.rs` | `snapshot` subcommand |
| `web/wasm/src/lib.rs` | Kinds 6 and 7, host tests |
| `web/wasm/Cargo.toml` | Add `rlib` so `cargo test` links |
| `web/app/inbound-kind.mjs` | Create. `classifyInboundKind` |
| `web/app/inbound-kind.test.mjs` | Create |
| `web/app/app.js` | Import and receive branch only |
| `web/app/pkg/*` | Regenerated wasm-bindgen output |
| `docs/ARCHITECTURE.md` | One paragraph |
| `.github/workflows/test-foundation.yml` | Create. Test only, no deploy |

Leave untouched: `Screens.kt`, `style.css`, `AndroidManifest.xml`, `data_extraction_rules.xml`, `vercel.json`, `wrangler.jsonc`, `web/bridge`, Supabase migrations, `core/src/session.rs`, `core/src/envelope.rs`.

---

### Task 1: Revocation snapshot in the core

**Files:**
- Create: `core/src/revocation.rs`
- Modify: `core/src/devicecert.rs` (method on `CertificateAuthority` after `issue`)
- Modify: `core/src/lib.rs` (module list and exports)
- Test: `core/src/revocation.rs` (`#[cfg(test)]` at the bottom)

**Interfaces:**
- Consumes: `CertificateAuthority`'s private `SigningKey` via a method in `devicecert.rs`; private `key_id` in that file; `ed25519_dalek::VerifyingKey::verify_strict`; `AetherError::malformed`.
- Produces: `aether_core::revocation_status(serial: [u8; 16], snapshot: &[u8], ca_public_keys: &[[u8; 32]], now_ms: u64) -> RevocationStatus` and `aether_core::RevocationStatus`. `CertificateAuthority::sign_revocation_snapshot(&self, issued_at_ms: u64, expires_at_ms: u64, serials: &[[u8; 16]]) -> Result<Vec<u8>, AetherError>`.

- [ ] **Step 1: Write the failing test**

Create `core/src/revocation.rs` with only the test module and a stub that does not exist yet. Put this test at the bottom of the file:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::CertificateAuthority;

    fn ca() -> std::sync::Arc<CertificateAuthority> {
        CertificateAuthority::generate()
    }

    fn keys(ca: &CertificateAuthority) -> Vec<[u8; 32]> {
        vec![ca.public_key().as_slice().try_into().unwrap()]
    }

    #[test]
    fn empty_snapshot_is_not_revoked() {
        let ca = ca();
        let snap = ca.sign_revocation_snapshot(1_000, 9_000, &[]).unwrap();
        let serial = [0u8; 16];
        assert_eq!(revocation_status(serial, &snap, &keys(&ca), 1_500), RevocationStatus::NotRevoked);
    }
}
```

Add to `core/src/lib.rs` immediately after `mod nav;`:

```rust
mod revocation;
```

And with the other `pub use` lines:

```rust
pub use revocation::{revocation_status, RevocationStatus};
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml revocation::tests::empty_snapshot_is_not_revoked -- --nocapture`

Expected: FAIL to compile. `sign_revocation_snapshot` and `revocation_status` are unresolved.

- [ ] **Step 3: Write minimal implementation**

Replace `core/src/revocation.rs` with the test from step 1 plus this library code above the tests:

```rust
//! Offline revocation snapshot. Not a new signature algorithm: Ed25519 via ed25519-dalek,
//! `verify_strict`, same as `verify_device_certificate`.
//!
//! A later Diagnostics switch, not built in this cycle, calls `verify_device_certificate`
//! first and calls `revocation_status` only after that succeeds. Every variant other than
//! `NotRevoked` is a refusal. Devices do not load a snapshot in this cycle.

use ed25519_dalek::{Signature, VerifyingKey};
use sha2::{Digest, Sha256};

use crate::error::AetherError;

const MAGIC: &[u8] = b"AETHER-CRL/1";
const MAX_SERIALS: usize = 4096;
const SKEW_MS: u64 = 300_000;
const SIG_LEN: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RevocationStatus {
    Malformed,
    UntrustedSigner,
    BadSignature,
    Stale,
    Revoked,
    NotRevoked,
}

impl std::fmt::Display for RevocationStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Malformed => "revocation snapshot is malformed",
            Self::UntrustedSigner => "revocation snapshot signer is not trusted",
            Self::BadSignature => "revocation snapshot signature is invalid",
            Self::Stale => "revocation snapshot is not current",
            Self::Revoked => "certificate serial is revoked",
            Self::NotRevoked => "certificate serial is not revoked",
        })
    }
}

pub(crate) fn canonical_body(
    ca_public: &[u8; 32],
    issued_at_ms: u64,
    expires_at_ms: u64,
    serials: &[[u8; 16]],
) -> Result<Vec<u8>, AetherError> {
    if expires_at_ms <= issued_at_ms || serials.len() > MAX_SERIALS {
        return Err(AetherError::malformed("revocation snapshot is malformed"));
    }
    if serials.windows(2).any(|w| w[0] >= w[1]) {
        return Err(AetherError::malformed("revocation snapshot is malformed"));
    }
    let mut id = [0u8; 8];
    id.copy_from_slice(&Sha256::digest(ca_public)[..8]);
    let mut out = Vec::with_capacity(12 + 8 + 8 + 8 + 4 + serials.len() * 16);
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&id);
    out.extend_from_slice(&issued_at_ms.to_be_bytes());
    out.extend_from_slice(&expires_at_ms.to_be_bytes());
    out.extend_from_slice(&(serials.len() as u32).to_be_bytes());
    for serial in serials {
        out.extend_from_slice(serial);
    }
    Ok(out)
}

pub fn revocation_status(
    serial: [u8; 16],
    snapshot: &[u8],
    ca_public_keys: &[[u8; 32]],
    now_ms: u64,
) -> RevocationStatus {
    let Some((body, sig_bytes, issued_at_ms, expires_at_ms, serials)) = parse(snapshot) else {
        return RevocationStatus::Malformed;
    };
    let id = &body[12..20];
    let mut matched = false;
    let mut verified = false;
    for key in ca_public_keys {
        let mut kid = [0u8; 8];
        kid.copy_from_slice(&Sha256::digest(key)[..8]);
        if kid.as_slice() != id {
            continue;
        }
        matched = true;
        let Ok(vk) = VerifyingKey::from_bytes(key) else { continue };
        let Ok(sig) = Signature::from_slice(sig_bytes) else { continue };
        if vk.verify_strict(body, &sig).is_ok() {
            verified = true;
            break;
        }
    }
    if !matched {
        return RevocationStatus::UntrustedSigner;
    }
    if !verified {
        return RevocationStatus::BadSignature;
    }
    if now_ms > expires_at_ms || issued_at_ms > now_ms.saturating_add(SKEW_MS) {
        return RevocationStatus::Stale;
    }
    if serials.iter().any(|s| *s == serial) {
        return RevocationStatus::Revoked;
    }
    RevocationStatus::NotRevoked
}

fn parse(snapshot: &[u8]) -> Option<(&[u8], &[u8], u64, u64, Vec<[u8; 16]>)> {
    if snapshot.len() < 12 + 8 + 8 + 8 + 4 + SIG_LEN || !snapshot.starts_with(MAGIC) {
        return None;
    }
    let count = u32::from_be_bytes(snapshot[36..40].try_into().ok()?) as usize;
    if count > MAX_SERIALS {
        return None;
    }
    let expect = 40 + count * 16 + SIG_LEN;
    if snapshot.len() != expect {
        return None;
    }
    let issued_at_ms = u64::from_be_bytes(snapshot[20..28].try_into().ok()?);
    let expires_at_ms = u64::from_be_bytes(snapshot[28..36].try_into().ok()?);
    if expires_at_ms <= issued_at_ms {
        return None;
    }
    let mut serials = Vec::with_capacity(count);
    for i in 0..count {
        let start = 40 + i * 16;
        let mut serial = [0u8; 16];
        serial.copy_from_slice(&snapshot[start..start + 16]);
        if let Some(prev) = serials.last() {
            if *prev >= serial {
                return None;
            }
        }
        serials.push(serial);
    }
    let split = snapshot.len() - SIG_LEN;
    Some((&snapshot[..split], &snapshot[split..], issued_at_ms, expires_at_ms, serials))
}
```

In `core/src/devicecert.rs`, inside the `impl CertificateAuthority` block after `issue`, add:

```rust
    /// Signs an `AETHER-CRL/1` snapshot. The file bytes are the return value. No network.
    pub fn sign_revocation_snapshot(
        &self,
        issued_at_ms: u64,
        expires_at_ms: u64,
        serials: &[[u8; 16]],
    ) -> Result<Vec<u8>, AetherError> {
        let mut body = crate::revocation::canonical_body(
            &self.key.verifying_key().to_bytes(),
            issued_at_ms,
            expires_at_ms,
            serials,
        )?;
        let sig = self.key.sign(&body);
        body.extend_from_slice(&sig.to_bytes());
        Ok(body)
    }
```

Add these tests in the same `tests` module. Do not print snapshot bytes or serials.

```rust
    #[test]
    fn listed_serial_is_revoked_and_bad_signature_is_not_a_bad_magic() {
        let ca = ca();
        let listed = [2u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        let snap = ca.sign_revocation_snapshot(1_000, 9_000, &[listed]).unwrap();
        assert_eq!(revocation_status(listed, &snap, &keys(&ca), 1_500), RevocationStatus::Revoked);
        let mut bad_sig = snap.clone();
        let last = bad_sig.len() - 1;
        bad_sig[last] ^= 0x01;
        assert_eq!(revocation_status(listed, &bad_sig, &keys(&ca), 1_500), RevocationStatus::BadSignature);
        let mut bad_magic = snap.clone();
        bad_magic[0] = 0;
        bad_magic[last] ^= 0x01;
        assert_eq!(revocation_status(listed, &bad_magic, &keys(&ca), 1_500), RevocationStatus::Malformed);
    }

    #[test]
    fn other_ca_future_and_expired_snapshots_are_refused() {
        let ca = ca();
        let other = ca();
        let snap = ca.sign_revocation_snapshot(1_000, 9_000, &[]).unwrap();
        assert_eq!(revocation_status([0u8; 16], &snap, &keys(&other), 1_500), RevocationStatus::UntrustedSigner);
        assert_eq!(revocation_status([0u8; 16], &snap, &keys(&ca), 9_001), RevocationStatus::Stale);
        let future = ca.sign_revocation_snapshot(1_500 + 300_001, 1_500 + 300_001 + 10, &[]).unwrap();
        assert_eq!(revocation_status([0u8; 16], &future, &keys(&ca), 1_500), RevocationStatus::Stale);
    }

    #[test]
    fn unsorted_and_too_many_serials_are_malformed() {
        let ca = ca();
        let high = [2u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        let low = [1u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(ca.sign_revocation_snapshot(1_000, 9_000, &[high, low]).is_err());
        let mut many = Vec::new();
        for i in 0..4097u16 {
            let mut serial = [0u8; 16];
            serial[14] = (i >> 8) as u8;
            serial[15] = i as u8;
            many.push(serial);
        }
        assert!(ca.sign_revocation_snapshot(1_000, 9_000, &many).is_err());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml revocation:: -- --nocapture`

Expected: PASS, including `empty_snapshot_is_not_revoked` and the cases added in step 3. `cargo test --manifest-path core/Cargo.toml` still passes the existing handshake, replay, and certificate tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/revocation.rs core/src/devicecert.rs core/src/lib.rs
git commit -m "core: add offline revocation snapshot verify and sign"
```

---

### Task 2: `aether-ca snapshot` writes the raw file

**Files:**
- Modify: `core/src/bin/aether-ca.rs` (`run` usage string and a new match arm)
- Test: `core/src/bin/aether-ca.rs` `#[cfg(test)]` module

**Interfaces:**
- Consumes: `CertificateAuthority::from_secret`, `sign_revocation_snapshot`, `aether_core::revocation_status`, `aether_core::RevocationStatus`.
- Produces: `aether-ca snapshot --key <file> --serials <file> --issued-at <u64> --expires-at <u64> --out <file>` writes raw bytes and returns the string `wrote snapshot`. Failures return `revocation snapshot is malformed` and do not echo the serial file or the CA secret.

- [ ] **Step 1: Write the failing test**

Inside `mod tests` in `core/src/bin/aether-ca.rs`:

```rust
#[test]
fn snapshot_writes_raw_file_and_refuses_unsorted_serials() {
    use aether_core::{revocation_status, CertificateAuthority, RevocationStatus};
    let dir = tempfile::tempdir().unwrap();
    let ca = CertificateAuthority::generate();
    let key_path = dir.path().join("ca.key");
    std::fs::write(&key_path, hex(&ca.export_secret())).unwrap();
    let serials = dir.path().join("serials.txt");
    std::fs::write(&serials, "").unwrap();
    let out = dir.path().join("snap.bin");
    let args = vec![
        "snapshot".into(),
        "--key".into(), key_path.display().to_string(),
        "--serials".into(), serials.display().to_string(),
        "--issued-at".into(), "1000".into(),
        "--expires-at".into(), "9000".into(),
        "--out".into(), out.display().to_string(),
    ];
    assert_eq!(run(&args).unwrap(), "wrote snapshot");
    let bytes = std::fs::read(&out).unwrap();
    assert!(bytes.starts_with(b"AETHER-CRL/1"));
    assert_eq!(bytes.len(), 104);
    let pk: [u8; 32] = ca.public_key().try_into().unwrap();
    assert_eq!(revocation_status([0u8; 16], &bytes, &[pk], 1500), RevocationStatus::NotRevoked);

    std::fs::write(&serials, "02000000000000000000000000000000\n01000000000000000000000000000000\n").unwrap();
    let err = run(&args).unwrap_err();
    assert_eq!(err, "revocation snapshot is malformed");
    assert!(!err.contains("0200"));
}
```

`tempfile` is already a dev-dependency of this package. `hex` and `run` are in the parent module.

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --bin aether-ca snapshot_writes_raw_file_and_refuses_unsorted_serials -- --nocapture`

Expected: FAIL. `run` returns an error that the command is unknown (`unexpected` / usage), not `wrote snapshot`.

- [ ] **Step 3: Write minimal implementation**

Change the usage string to:

```rust
let cmd = args.first().ok_or("usage: aether-ca init|sign|verify|info|register-ca|revoke|status|snapshot [flags]")?;
```

Add this arm before the final `_ =>` of the `match cmd.as_str()`:

```rust
"snapshot" => {
    let ca = load_ca(&need("key")?)?;
    let issued_at: u64 = need("issued-at")?.parse().map_err(|_| "revocation snapshot is malformed".to_string())?;
    let expires_at: u64 = need("expires-at")?.parse().map_err(|_| "revocation snapshot is malformed".to_string())?;
    let mut text = std::fs::read_to_string(need("serials")?).map_err(|_| "revocation snapshot is malformed".to_string())?;
    if text.ends_with('\n') {
        text.pop();
    }
    let mut serials = Vec::new();
    if !text.is_empty() {
        for line in text.split('\n') {
            if line.is_empty() {
                return Err("revocation snapshot is malformed".into());
            }
            let raw = unhex(line).map_err(|_| "revocation snapshot is malformed".to_string())?;
            let serial: [u8; 16] = raw.as_slice().try_into().map_err(|_| "revocation snapshot is malformed".to_string())?;
            serials.push(serial);
        }
    }
    let bytes = ca.sign_revocation_snapshot(issued_at, expires_at, &serials).map_err(|_| "revocation snapshot is malformed".to_string())?;
    std::fs::write(need("out")?, &bytes).map_err(|_| "revocation snapshot is malformed".to_string())?;
    Ok("wrote snapshot".into())
}
```

A file that is empty or only `"\n"` is count 0. A file `"aa\n\n"` still has an empty line after the single trailing-newline strip and fails. Do not print `text` or `bytes`.

This arm must not call `registry_status`, `ureq`, or read `AETHER_SUPABASE_SERVICE_KEY`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --bin aether-ca -- --nocapture`

Expected: PASS, including the three existing JSON tests and `snapshot_writes_raw_file_and_refuses_unsorted_serials`.

- [ ] **Step 5: Commit**

```bash
git add core/src/bin/aether-ca.rs
git commit -m "aether-ca: write an offline revocation snapshot"
```

---

### Task 3: Browser core accepts kinds 6 and 7

**Files:**
- Modify: `web/wasm/src/lib.rs` (`kind_from`, `kind_to`, the doc comment on `Message.kind`, new tests)
- Modify: `web/wasm/Cargo.toml` crate-type
- Test: `web/wasm/src/lib.rs` `#[cfg(test)]`

**Interfaces:**
- Consumes: `core::MessageKind::{Position, Field}` and the existing wasm `Handshake` / `Session` wrappers (`initiator`, `responder`, `write_message`, `read_message`, `finish`, `encrypt`, `decrypt`).
- Produces: `encrypt(6, _, _)` and `encrypt(7, _, _)` succeed. `decrypt` returns `kind` 6 or 7. `encrypt(99, _, _)` still returns `JsError`.

- [ ] **Step 1: Write the failing test**

In `web/wasm/Cargo.toml` change the lib section to:

```toml
[lib]
crate-type = ["cdylib", "rlib"]
```

Append to `web/wasm/src/lib.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (Session, Session) {
        let a = Identity::generate();
        let b = Identity::generate();
        let i = Handshake::initiator(&a).unwrap();
        let r = Handshake::responder(&b).unwrap();
        let m1 = i.write_message(vec![]).unwrap();
        r.read_message(m1).unwrap();
        let m2 = r.write_message(b"bob".to_vec()).unwrap();
        i.read_message(m2).unwrap();
        let m3 = i.write_message(b"alice".to_vec()).unwrap();
        r.read_message(m3).unwrap();
        (i.finish().unwrap(), r.finish().unwrap())
    }

    #[test]
    fn kinds_six_and_seven_round_trip_and_unknown_kind_fails() {
        let (a, b) = pair();
        for kind in [6u8, 7] {
            let frame = a.encrypt(kind, 1.0, "lab".into()).unwrap();
            let m = b.decrypt(frame).unwrap();
            assert_eq!(m.kind, kind);
            assert_eq!(m.body, "lab");
            assert_eq!(m.sent_at_ms, 1.0);
        }
        let (a, b) = pair();
        let frame = a.encrypt(1, 2.0, "hi".into()).unwrap();
        let m = b.decrypt(frame).unwrap();
        assert_eq!(m.kind, 1);
        assert_eq!(m.body, "hi");
        assert!(a.encrypt(99, 0.0, "x".into()).is_err());
    }
}
```

This is the finished test. Do not weaken it in later steps.

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path web/wasm/Cargo.toml kinds_six_and_seven_round_trip_and_unknown_kind_fails -- --nocapture`

Expected: FAIL. `encrypt(6, ...)` returns `Err` (`unknown message kind`) and `unwrap` panics. Do not change the test.

If the host build fails only because `getrandom`'s `js` feature is active off wasm, add this to `web/wasm/Cargo.toml` and rerun the same command:

```toml
[target.'cfg(not(target_arch = "wasm32"))'.dependencies]
getrandom = { version = "0.2" }
```

Keep `getrandom` with `features = ["js"]` for the wasm target. Do not add another crypto crate.

- [ ] **Step 3: Write minimal implementation**

Change the doc comment and the two matches. Leave the test from step 1 as it is.

```rust
    /// 1 text, 2 receipt, 3 ping, 4 key drop, 5 burn, 6 position, 7 field.
    pub kind: u8,
```

```rust
fn kind_from(k: u8) -> Result<core::MessageKind, JsError> {
    use core::MessageKind::*;
    Ok(match k {
        1 => Text,
        2 => Receipt,
        3 => Ping,
        4 => KeyDrop,
        5 => Burn,
        6 => Position,
        7 => Field,
        _ => return Err(JsError::new("unknown message kind")),
    })
}

fn kind_to(k: core::MessageKind) -> u8 {
    use core::MessageKind::*;
    match k {
        Text => 1,
        Receipt => 2,
        Ping => 3,
        KeyDrop => 4,
        Burn => 5,
        Position => 6,
        Field => 7,
    }
}
```

Do not call `encode_position` or put coordinates in the body.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path web/wasm/Cargo.toml -- --nocapture`

Expected: PASS. Output includes `kinds_six_and_seven_round_trip_and_unknown_kind_fails`.

- [ ] **Step 5: Commit**

```bash
git add web/wasm/src/lib.rs web/wasm/Cargo.toml web/wasm/Cargo.lock
git commit -m "wasm: map position and field message kinds"
```

`Cargo.lock` only if cargo changed it. Do not commit a rebuilt `web/app/pkg` in this task.

---

### Task 4: Inbound persistence allowlist

**Files:**
- Create: `web/app/inbound-kind.mjs`
- Create: `web/app/inbound-kind.test.mjs`
- Modify: `web/app/app.js` (one import, and the receive branch inside `adopt`)
- Test: `web/app/inbound-kind.test.mjs`

**Interfaces:**
- Consumes: numeric kind from `session.decrypt`, which returns `{ kind, sentAtMs, body }` in JS.
- Produces: `classifyInboundKind(kind: number) -> "store" | "discard"`. `"store"` only for 1 and 4. The receive path calls `addMsg` only after `"store"`. A decrypt throw still executes `fs.close(); break;`.

- [ ] **Step 1: Write the failing test**

`web/app/inbound-kind.test.mjs`:

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyInboundKind } from './inbound-kind.mjs';

test('stores only text and key drop', () => {
  assert.equal(classifyInboundKind(1), 'store');
  assert.equal(classifyInboundKind(4), 'store');
  for (const kind of [0, 2, 3, 5, 6, 7, 99]) {
    assert.equal(classifyInboundKind(kind), 'discard');
  }
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/app/inbound-kind.test.mjs`

Expected: FAIL. Cannot find module `./inbound-kind.mjs`.

- [ ] **Step 3: Write minimal implementation**

`web/app/inbound-kind.mjs`:

```javascript
/** Inbound persistence. "store" writes localStorage. Every other kind is discarded. */
export function classifyInboundKind(kind) {
  if (kind === 1 || kind === 4) return 'store';
  return 'discard';
}
```

In `web/app/app.js`, add under the existing wasm import:

```javascript
import { classifyInboundKind } from './inbound-kind.mjs';
```

Replace the two `if` lines inside `adopt` that call `addMsg` with:

```javascript
      try { m = session.decrypt(f); } catch { fs.close(); break; } // authentication failure: drop the link
      if (classifyInboundKind(m.kind) === 'store') {
        if (m.kind === KIND.TEXT) addMsg(k, { o: false, ts: m.sentAtMs, body: m.body });
        else if (m.kind === KIND.DROP) addMsg(k, { o: false, ts: m.sentAtMs, body: m.body, drop: true });
      }
```

Do not change `function send`. Do not add a DOM node. Do not edit `index.html` or `style.css`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/app/inbound-kind.test.mjs`

Expected: PASS, `# tests 1` `# pass 1` `# fail 0`.

Run: `grep -n localStorage web/app/inbound-kind.mjs web/app/inbound-kind.test.mjs`

Expected: no matches. `grep` exit code 1.

- [ ] **Step 5: Commit**

```bash
git add web/app/inbound-kind.mjs web/app/inbound-kind.test.mjs web/app/app.js
git commit -m "web: store only inbound text and key drops"
```

---

### Task 5: Rebuild the committed wasm bundle

**Files:**
- Modify: `web/app/pkg/aether_web.js` and `web/app/pkg/aether_web_bg.wasm` (wasm-bindgen output)
- Test: grep of the generated JS comment

**Interfaces:**
- Consumes: the `Message.kind` doc comment from Task 3, which wasm-bindgen copies into the glue.
- Produces: a bundle whose JS contains the text `6 position, 7 field`. The page already loads `./pkg/aether_web.js`.

- [ ] **Step 1: Write the failing test**

Run: `grep -F "6 position, 7 field" web/app/pkg/aether_web.js`

Expected: exit code 1. The committed glue still says `1 text, 2 receipt, 3 ping, 4 key drop, 5 burn.`

- [ ] **Step 2: Run test to verify it fails**

The grep in step 1 is the failing check. Do not edit the glue by hand.

- [ ] **Step 3: Write minimal implementation**

From `web/wasm`, with `wasm32-unknown-unknown` installed and `wasm-bindgen-cli` 0.2.100:

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.100 --locked
cargo build --release --target wasm32-unknown-unknown
wasm-bindgen --target web --out-dir ../app/pkg --no-typescript target/wasm32-unknown-unknown/release/aether_web.wasm
```

`--no-typescript` matches the repository command in the README except the README omits that flag; this repo has no `aether_web.d.ts` in `web/app/pkg` (only `.js` and `.wasm`). If a `.d.ts` appears, delete it before commit so the package shape stays the same. Do not change `vercel.json` or `wrangler.jsonc`.

- [ ] **Step 4: Run test to verify it passes**

Run: `grep -F "6 position, 7 field" web/app/pkg/aether_web.js`

Expected: one matching line, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add web/app/pkg/aether_web.js web/app/pkg/aether_web_bg.wasm
git commit -m "wasm: rebuild the browser bundle for kinds 6 and 7"
```

The commit message body must include the exact `wasm-bindgen` command from step 3.

---

### Task 6: Architecture note, CI, and the review gate

**Files:**
- Modify: `docs/ARCHITECTURE.md` (one paragraph after the sentence that devices do not check revocation during the handshake)
- Create: `.github/workflows/test-foundation.yml`
- Test: the commands below

**Interfaces:**
- Consumes: the snapshot API from Task 1 and the bundle from Task 5.
- Produces: a CI workflow that runs the foundation checks and does not deploy.

- [ ] **Step 1: Write the failing test**

Run: `test -f .github/workflows/test-foundation.yml`

Expected: exit code 1. The file is absent. The only workflow today is `.github/workflows/auto-deploy.yml`. Do not edit `auto-deploy.yml`.

- [ ] **Step 2: Run test to verify it fails**

The `test -f` in step 1 is the failing check.

- [ ] **Step 3: Write minimal implementation**

Insert this paragraph in `docs/ARCHITECTURE.md` immediately after the paragraph that ends `Revocation is checked by aether-ca verify --online, not yet by devices during the handshake.`

```markdown
**Offline revocation snapshots.** `aether-ca snapshot` writes a raw `AETHER-CRL/1` file signed by the CA Ed25519 key with `verify_strict`. `revocation_status` answers whether a 16-byte serial is in that file. It does not replace `verify_device_certificate`. Devices do not load the file. A later switch would verify the certificate first and refuse every snapshot status other than not-revoked. The snapshot is not a FIPS validation and not an accreditation.
```

Create `.github/workflows/test-foundation.yml`:

```yaml
name: test-foundation
on:
  pull_request:
  push:
    branches: [main]
jobs:
  foundation:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - run: cargo test --manifest-path core/Cargo.toml
      - run: cargo test --manifest-path web/wasm/Cargo.toml
      - run: node --test web/app/inbound-kind.test.mjs
      - run: grep -F "6 position, 7 field" web/app/pkg/aether_web.js
```

No secrets, no wrangler, no vercel, no network call from the tests.

- [ ] **Step 4: Run test to verify it passes**

Run, from the repo root:

```bash
cargo test --manifest-path core/Cargo.toml
cargo test --manifest-path web/wasm/Cargo.toml
node --test web/app/inbound-kind.test.mjs
grep -F "6 position, 7 field" web/app/pkg/aether_web.js
git diff --name-only origin/main...HEAD
```

Expected: both cargo suites PASS, node PASS, grep exit 0. The name-only diff must not list `Screens.kt`, `style.css`, `AndroidManifest.xml`, `data_extraction_rules.xml`, `vercel.json`, `wrangler.jsonc`, or anything under `supabase/`.

- [ ] **Step 5: Commit**

```bash
git add docs/ARCHITECTURE.md .github/workflows/test-foundation.yml
git commit -m "ci: test the talk foundation without deploying"
```

---

## Plan review gate

Stop if any of these are true: a new crate was added; `session.rs` or `envelope.rs` changed; Android UI or permissions changed; the snapshot command reads Supabase; tests print a message body; the plan's CI workflow deploys. Kinds 6 and 7 on the wire were already Android's behavior. This plan does not make the browser render positions or fields.
