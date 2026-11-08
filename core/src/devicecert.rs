//! Device certificates: an organization's ÆTHER certificate authority signs each enrolled
//! device's X25519 identity key, so peers (and the backend) can tell an issued node from an
//! unknown one without a live lookup.
//!
//! The model follows cfssl's: a CA key, signing *profiles* that fix a certificate's usage and
//! default lifetime, and serial numbers for revocation lists. The format is ÆTHER's own,
//! compact binary (no X.509), signed with Ed25519:
//!
//! ```text
//! "AETHER-CERT/1" ‖ serial(16) ‖ device X25519 key(32) ‖ profile(1) ‖ not_before_ms(u64 BE)
//! ‖ not_after_ms(u64 BE) ‖ ca_key_id(8) ‖ label_len(1) ‖ label(UTF-8) ‖ Ed25519 signature(64)
//! ```
//!
//! The signature covers every byte before it. `ca_key_id` is the first 8 bytes of
//! SHA-256(CA public key) and lets a device holding several CA keys pick the right one.

use std::sync::Arc;

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::fingerprint::check_key;
use crate::AetherError;

const MAGIC: &[u8] = b"AETHER-CERT/1";
const SERIAL_LEN: usize = 16;
const KEY_ID_LEN: usize = 8;
const SIG_LEN: usize = 64;
pub const MAX_CERT_LABEL_BYTES: usize = 64;
const FIXED_LEN: usize = MAGIC.len() + SERIAL_LEN + 32 + 1 + 8 + 8 + KEY_ID_LEN + 1;

/// What a certificate allows its device to do, like a cfssl signing profile.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CertProfile {
    /// A field node: talks to peers and holds burner numbers.
    Node,
    /// A relay: forwards encrypted frames for other nodes.
    Relay,
    /// An operator console that can issue remote burns for its team.
    Operator,
}

impl CertProfile {
    fn code(self) -> u8 {
        match self {
            Self::Node => 1,
            Self::Relay => 2,
            Self::Operator => 3,
        }
    }

    fn from_code(c: u8) -> Result<Self, AetherError> {
        Ok(match c {
            1 => Self::Node,
            2 => Self::Relay,
            3 => Self::Operator,
            _ => return Err(AetherError::malformed("unknown certificate profile")),
        })
    }

    /// Default lifetime in hours when the issuer gives none (cfssl's `expiry`).
    pub fn default_expiry_hours(self) -> u64 {
        match self {
            Self::Node | Self::Relay => 8760, // one year
            Self::Operator => 2160,           // 90 days
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Node => "node",
            Self::Relay => "relay",
            Self::Operator => "operator",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "node" => Some(Self::Node),
            "relay" => Some(Self::Relay),
            "operator" => Some(Self::Operator),
            _ => None,
        }
    }
}

/// The decoded fields of a device certificate.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceCertificate {
    /// 128-bit serial, lowercase hex.
    pub serial: String,
    pub device_public_key: Vec<u8>,
    pub label: String,
    pub profile: CertProfile,
    pub not_before_ms: u64,
    pub not_after_ms: u64,
    /// First 8 bytes of SHA-256 of the issuing CA's public key, lowercase hex.
    pub ca_key_id: String,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn key_id(ca_public: &[u8; 32]) -> [u8; KEY_ID_LEN] {
    let d = Sha256::digest(ca_public);
    d[..KEY_ID_LEN].try_into().expect("8 bytes")
}

/// An ÆTHER certificate authority (Ed25519 signing key).
#[derive(uniffi::Object)]
pub struct CertificateAuthority {
    key: SigningKey,
}

#[uniffi::export]
impl CertificateAuthority {
    #[uniffi::constructor]
    pub fn generate() -> Arc<Self> {
        Arc::new(Self { key: SigningKey::generate(&mut OsRng) })
    }

    /// Restores a CA from its 32-byte secret seed.
    #[uniffi::constructor]
    pub fn from_secret(secret: Vec<u8>) -> Result<Arc<Self>, AetherError> {
        let seed = Zeroizing::new(<[u8; 32]>::try_from(secret.as_slice()).map_err(|_| AetherError::InvalidKey)?);
        Ok(Arc::new(Self { key: SigningKey::from_bytes(&seed) }))
    }

    pub fn export_secret(&self) -> Vec<u8> {
        self.key.to_bytes().to_vec()
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.key.verifying_key().to_bytes().to_vec()
    }

    pub fn key_id(&self) -> String {
        hex(&key_id(&self.key.verifying_key().to_bytes()))
    }

    /// Signs `device_public_key` (a 32-byte X25519 identity key) for `[not_before_ms, not_after_ms]`.
    pub fn issue(
        &self,
        device_public_key: Vec<u8>,
        label: String,
        profile: CertProfile,
        not_before_ms: u64,
        not_after_ms: u64,
    ) -> Result<Vec<u8>, AetherError> {
        check_key(&device_public_key)?;
        let label = label.trim();
        if label.is_empty() || label.len() > MAX_CERT_LABEL_BYTES || label.chars().any(char::is_control) {
            return Err(AetherError::malformed("label must be 1-64 bytes with no control characters"));
        }
        if not_after_ms <= not_before_ms {
            return Err(AetherError::malformed("certificate must expire after it becomes valid"));
        }
        let mut serial = [0u8; SERIAL_LEN];
        OsRng.fill_bytes(&mut serial);
        let mut out = Vec::with_capacity(FIXED_LEN + label.len() + SIG_LEN);
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&serial);
        out.extend_from_slice(&device_public_key);
        out.push(profile.code());
        out.extend_from_slice(&not_before_ms.to_be_bytes());
        out.extend_from_slice(&not_after_ms.to_be_bytes());
        out.extend_from_slice(&key_id(&self.key.verifying_key().to_bytes()));
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
        let sig = self.key.sign(&out);
        out.extend_from_slice(&sig.to_bytes());
        Ok(out)
    }
}

/// Parses a certificate without checking its signature (for display only).
#[uniffi::export]
pub fn decode_device_certificate(certificate: Vec<u8>) -> Result<DeviceCertificate, AetherError> {
    parse(&certificate).map(|(c, _, _)| c)
}

/// Checks that `certificate` was issued by `ca_public_key`, names `device_public_key`, and is
/// valid at `now_ms`. Returns its fields on success.
#[uniffi::export]
pub fn verify_device_certificate(
    certificate: Vec<u8>,
    ca_public_key: Vec<u8>,
    device_public_key: Vec<u8>,
    now_ms: u64,
) -> Result<DeviceCertificate, AetherError> {
    let ca: [u8; 32] = ca_public_key.as_slice().try_into().map_err(|_| AetherError::InvalidKey)?;
    let vk = VerifyingKey::from_bytes(&ca).map_err(|_| AetherError::InvalidKey)?;
    let (cert, signed, sig) = parse(&certificate)?;
    if cert.ca_key_id != hex(&key_id(&ca)) {
        return Err(AetherError::state("certificate was issued by a different authority"));
    }
    vk.verify_strict(signed, &sig).map_err(|_| AetherError::state("certificate signature is invalid"))?;
    if cert.device_public_key != device_public_key {
        return Err(AetherError::state("certificate is for a different device"));
    }
    if now_ms < cert.not_before_ms {
        return Err(AetherError::state("certificate is not yet valid"));
    }
    if now_ms > cert.not_after_ms {
        return Err(AetherError::state("certificate has expired"));
    }
    Ok(cert)
}

fn parse(b: &[u8]) -> Result<(DeviceCertificate, &[u8], Signature), AetherError> {
    let bad = || AetherError::malformed("not an ÆTHER device certificate");
    if b.len() < FIXED_LEN + 1 + SIG_LEN || !b.starts_with(MAGIC) {
        return Err(bad());
    }
    let label_len = b[FIXED_LEN - 1] as usize;
    if label_len == 0 || label_len > MAX_CERT_LABEL_BYTES || b.len() != FIXED_LEN + label_len + SIG_LEN {
        return Err(bad());
    }
    let mut p = MAGIC.len();
    let mut take = |n: usize| {
        let s = &b[p..p + n];
        p += n;
        s
    };
    let serial = hex(take(SERIAL_LEN));
    let device_public_key = take(32).to_vec();
    let profile = CertProfile::from_code(take(1)[0])?;
    let not_before_ms = u64::from_be_bytes(take(8).try_into().expect("8"));
    let not_after_ms = u64::from_be_bytes(take(8).try_into().expect("8"));
    let ca_key_id = hex(take(KEY_ID_LEN));
    take(1);
    let label = std::str::from_utf8(take(label_len)).map_err(|_| bad())?.to_owned();
    let signed_len = p;
    let sig = Signature::from_bytes(b[signed_len..].try_into().map_err(|_| bad())?);
    let cert = DeviceCertificate { serial, device_public_key, label, profile, not_before_ms, not_after_ms, ca_key_id };
    Ok((cert, &b[..signed_len], sig))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Identity;

    const HOUR: u64 = 3_600_000;

    fn issue(ca: &CertificateAuthority, dev: &Identity) -> Vec<u8> {
        ca.issue(dev.public_key(), "GRANITE-07".into(), CertProfile::Node, 1_000, 1_000 + 24 * HOUR).unwrap()
    }

    #[test]
    fn issue_and_verify() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let cert = issue(&ca, &dev);
        let c = verify_device_certificate(cert, ca.public_key(), dev.public_key(), 2_000).unwrap();
        assert_eq!(c.label, "GRANITE-07");
        assert_eq!(c.profile, CertProfile::Node);
        assert_eq!(c.device_public_key, dev.public_key());
        assert_eq!(c.ca_key_id, ca.key_id());
        assert_eq!(c.serial.len(), 32);
    }

    #[test]
    fn serials_are_unique() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let a = decode_device_certificate(issue(&ca, &dev)).unwrap();
        let b = decode_device_certificate(issue(&ca, &dev)).unwrap();
        assert_ne!(a.serial, b.serial);
    }

    #[test]
    fn rejects_other_ca() {
        let (ca, other) = (CertificateAuthority::generate(), CertificateAuthority::generate());
        let dev = Identity::generate();
        assert!(verify_device_certificate(issue(&ca, &dev), other.public_key(), dev.public_key(), 2_000).is_err());
    }

    #[test]
    fn rejects_other_device() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let cert = issue(&ca, &dev);
        assert!(verify_device_certificate(cert, ca.public_key(), Identity::generate().public_key(), 2_000).is_err());
    }

    #[test]
    fn rejects_outside_validity_window() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let cert = issue(&ca, &dev);
        assert!(verify_device_certificate(cert.clone(), ca.public_key(), dev.public_key(), 999).is_err());
        assert!(verify_device_certificate(cert, ca.public_key(), dev.public_key(), 1_000 + 25 * HOUR).is_err());
    }

    #[test]
    fn rejects_any_tampered_byte() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let cert = issue(&ca, &dev);
        for i in MAGIC.len()..cert.len() {
            let mut t = cert.clone();
            t[i] ^= 0x01;
            assert!(verify_device_certificate(t, ca.public_key(), dev.public_key(), 2_000).is_err(), "byte {i}");
        }
    }

    #[test]
    fn rejects_truncated_and_garbage() {
        let ca = CertificateAuthority::generate();
        let dev = Identity::generate();
        let cert = issue(&ca, &dev);
        assert!(decode_device_certificate(cert[..cert.len() - 1].to_vec()).is_err());
        assert!(decode_device_certificate(b"AETHER-CERT/1".to_vec()).is_err());
        assert!(decode_device_certificate(vec![0; 200]).is_err());
    }

    #[test]
    fn issue_validates_inputs() {
        let ca = CertificateAuthority::generate();
        let key = Identity::generate().public_key();
        assert!(ca.issue(vec![0; 31], "x".into(), CertProfile::Node, 0, 1).is_err());
        assert!(ca.issue(key.clone(), "".into(), CertProfile::Node, 0, 1).is_err());
        assert!(ca.issue(key.clone(), "a\nb".into(), CertProfile::Node, 0, 1).is_err());
        assert!(ca.issue(key.clone(), "x".repeat(65), CertProfile::Node, 0, 1).is_err());
        assert!(ca.issue(key, "x".into(), CertProfile::Node, 5, 5).is_err());
    }

    #[test]
    fn ca_survives_export_and_restore() {
        let ca = CertificateAuthority::generate();
        let back = CertificateAuthority::from_secret(ca.export_secret()).unwrap();
        assert_eq!(ca.public_key(), back.public_key());
        let dev = Identity::generate();
        verify_device_certificate(issue(&back, &dev), ca.public_key(), dev.public_key(), 2_000).unwrap();
    }
}
