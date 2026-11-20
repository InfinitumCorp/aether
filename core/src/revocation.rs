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

/// Whether `serial` is listed in a signed snapshot. Does not check a device certificate.
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
        let issuer = ca();
        let other = CertificateAuthority::generate();
        let snap = issuer.sign_revocation_snapshot(1_000, 9_000, &[]).unwrap();
        assert_eq!(revocation_status([0u8; 16], &snap, &keys(&other), 1_500), RevocationStatus::UntrustedSigner);
        assert_eq!(revocation_status([0u8; 16], &snap, &keys(&issuer), 9_001), RevocationStatus::Stale);
        let future = issuer.sign_revocation_snapshot(1_500 + 300_001, 1_500 + 300_001 + 10, &[]).unwrap();
        assert_eq!(revocation_status([0u8; 16], &future, &keys(&issuer), 1_500), RevocationStatus::Stale);
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
}
