//! The hello: what each side sends inside the encrypted handshake payload (message 2 from the
//! responder, message 3 from the dialer). It carries the display name and, optionally, the
//! sender's device certificate, so a peer can be checked against the organization's CA before
//! any message is exchanged.
//!
//! Layout: `0x00 "AH1"` ‖ name_len (u8) ‖ name (UTF-8) ‖ cert_len (u16 BE) ‖ certificate.
//! A payload without the leading `0x00 "AH1"` is a bare display name from a client that predates
//! hellos; it decodes as a name with no certificate.

use sha2::{Digest, Sha256};

use crate::devicecert::{decode_device_certificate, verify_device_certificate, CertProfile};
use crate::AetherError;

const MAGIC: &[u8] = b"\x00AH1";
const MAX_NAME_BYTES: usize = 64;
const MAX_CERT_BYTES: usize = 1024;

/// A decoded hello.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Hello {
    pub name: String,
    pub certificate: Option<Vec<u8>>,
}

/// How a peer's certificate checked out.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PeerCertStatus {
    /// The peer sent no certificate.
    Absent,
    /// Issued by a trusted CA, for this peer's key, and in date.
    Valid,
    /// Well-formed, but from a CA this device doesn't trust.
    Untrusted,
    /// Bad signature, wrong device, expired, not yet valid, or malformed.
    Invalid,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeerCertificate {
    pub status: PeerCertStatus,
    pub label: Option<String>,
    pub profile: Option<CertProfile>,
    pub serial: Option<String>,
    /// Why the certificate isn't `Valid` (empty when it is).
    pub reason: String,
}

fn truncate_utf8(s: &str, max: usize) -> &str {
    let mut end = s.len().min(max);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

#[uniffi::export]
pub fn encode_hello(name: String, certificate: Option<Vec<u8>>) -> Result<Vec<u8>, AetherError> {
    let name = truncate_utf8(name.trim(), MAX_NAME_BYTES);
    let cert = certificate.unwrap_or_default();
    if cert.len() > MAX_CERT_BYTES {
        return Err(AetherError::malformed("certificate too large"));
    }
    let mut out = Vec::with_capacity(MAGIC.len() + 3 + name.len() + cert.len());
    out.extend_from_slice(MAGIC);
    out.push(name.len() as u8);
    out.extend_from_slice(name.as_bytes());
    out.extend_from_slice(&(cert.len() as u16).to_be_bytes());
    out.extend_from_slice(&cert);
    Ok(out)
}

#[uniffi::export]
pub fn decode_hello(payload: Vec<u8>) -> Result<Hello, AetherError> {
    let Some(body) = payload.strip_prefix(MAGIC) else {
        // A client from before hellos: the payload is the display name.
        let name = String::from_utf8_lossy(&payload);
        return Ok(Hello { name: truncate_utf8(name.trim(), MAX_NAME_BYTES).to_owned(), certificate: None });
    };
    let bad = || AetherError::malformed("malformed hello");
    let (&name_len, rest) = body.split_first().ok_or_else(bad)?;
    let name_len = name_len as usize;
    if name_len > MAX_NAME_BYTES || rest.len() < name_len + 2 {
        return Err(bad());
    }
    let name = std::str::from_utf8(&rest[..name_len]).map_err(|_| bad())?.to_owned();
    let rest = &rest[name_len..];
    let cert_len = u16::from_be_bytes([rest[0], rest[1]]) as usize;
    let rest = &rest[2..];
    if cert_len > MAX_CERT_BYTES || rest.len() != cert_len {
        return Err(bad());
    }
    Ok(Hello { name, certificate: (cert_len > 0).then(|| rest.to_vec()) })
}

/// Checks the certificate a peer presented against the CA keys this device trusts. The peer's
/// key must be the one the handshake authenticated, so a certificate can't be replayed by
/// another device.
#[uniffi::export]
pub fn check_peer_certificate(
    certificate: Option<Vec<u8>>,
    trusted_ca_keys: Vec<Vec<u8>>,
    peer_public_key: Vec<u8>,
    now_ms: u64,
) -> PeerCertificate {
    let result = |status, reason: &str| PeerCertificate { status, label: None, profile: None, serial: None, reason: reason.into() };
    let Some(cert) = certificate else { return result(PeerCertStatus::Absent, "no certificate") };
    let decoded = match decode_device_certificate(cert.clone()) {
        Ok(c) => c,
        Err(_) => return result(PeerCertStatus::Invalid, "malformed certificate"),
    };
    let issuer = trusted_ca_keys
        .into_iter()
        .find(|k| k.len() == 32 && hex(&Sha256::digest(k)[..8]) == decoded.ca_key_id);
    let Some(ca) = issuer else {
        return PeerCertificate {
            status: PeerCertStatus::Untrusted,
            label: Some(decoded.label),
            profile: Some(decoded.profile),
            serial: Some(decoded.serial),
            reason: "issued by an authority this device doesn't trust".into(),
        };
    };
    match verify_device_certificate(cert, ca, peer_public_key, now_ms) {
        Ok(c) => PeerCertificate {
            status: PeerCertStatus::Valid,
            label: Some(c.label),
            profile: Some(c.profile),
            serial: Some(c.serial),
            reason: String::new(),
        },
        Err(e) => PeerCertificate {
            status: PeerCertStatus::Invalid,
            label: Some(decoded.label),
            profile: Some(decoded.profile),
            serial: Some(decoded.serial),
            reason: match e {
                AetherError::State { reason } => reason,
                other => other.to_string(),
            },
        },
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{CertificateAuthority, Identity};

    const NOW: u64 = 10_000;

    fn cert(ca: &CertificateAuthority, dev: &Identity) -> Vec<u8> {
        ca.issue(dev.public_key(), "GRANITE-07".into(), CertProfile::Node, 1_000, 1_000_000).unwrap()
    }

    #[test]
    fn hello_round_trip_with_and_without_certificate() {
        let ca = CertificateAuthority::generate();
        let c = cert(&ca, &Identity::generate());
        let h = decode_hello(encode_hello("Field-Phone".into(), Some(c.clone())).unwrap()).unwrap();
        assert_eq!(h, Hello { name: "Field-Phone".into(), certificate: Some(c) });
        let h = decode_hello(encode_hello("Web".into(), None).unwrap()).unwrap();
        assert_eq!(h, Hello { name: "Web".into(), certificate: None });
    }

    #[test]
    fn legacy_bare_name_still_decodes() {
        let h = decode_hello(b"Old Client".to_vec()).unwrap();
        assert_eq!(h, Hello { name: "Old Client".into(), certificate: None });
        assert_eq!(decode_hello(vec![]).unwrap().name, "");
    }

    #[test]
    fn long_names_are_cut_on_a_character_boundary() {
        let h = decode_hello(encode_hello("é".repeat(40), None).unwrap()).unwrap();
        assert_eq!(h.name, "é".repeat(32));
    }

    #[test]
    fn malformed_hellos_are_rejected() {
        let good = encode_hello("x".into(), Some(vec![1, 2, 3])).unwrap();
        assert!(decode_hello(good[..good.len() - 1].to_vec()).is_err());
        let mut long = good.clone();
        long.push(0);
        assert!(decode_hello(long).is_err());
        assert!(decode_hello(MAGIC.to_vec()).is_err());
    }

    #[test]
    fn peer_certificate_statuses() {
        let (ca, other) = (CertificateAuthority::generate(), CertificateAuthority::generate());
        let dev = Identity::generate();
        let c = cert(&ca, &dev);
        let trusted = vec![other.public_key(), ca.public_key()];

        let ok = check_peer_certificate(Some(c.clone()), trusted.clone(), dev.public_key(), NOW);
        assert_eq!(ok.status, PeerCertStatus::Valid);
        assert_eq!(ok.label.as_deref(), Some("GRANITE-07"));

        assert_eq!(check_peer_certificate(None, trusted.clone(), dev.public_key(), NOW).status, PeerCertStatus::Absent);
        assert_eq!(
            check_peer_certificate(Some(c.clone()), vec![other.public_key()], dev.public_key(), NOW).status,
            PeerCertStatus::Untrusted
        );
        assert_eq!(check_peer_certificate(Some(c.clone()), vec![], dev.public_key(), NOW).status, PeerCertStatus::Untrusted);

        // Someone else's certificate, replayed by a different device.
        let thief = check_peer_certificate(Some(c.clone()), trusted.clone(), Identity::generate().public_key(), NOW);
        assert_eq!(thief.status, PeerCertStatus::Invalid);
        assert!(thief.reason.contains("different device"));

        assert_eq!(check_peer_certificate(Some(c.clone()), trusted.clone(), dev.public_key(), 2_000_000).status, PeerCertStatus::Invalid);
        let mut forged = c;
        let n = forged.len();
        forged[n - 1] ^= 1;
        assert_eq!(check_peer_certificate(Some(forged), trusted.clone(), dev.public_key(), NOW).status, PeerCertStatus::Invalid);
        assert_eq!(check_peer_certificate(Some(vec![9; 40]), trusted, dev.public_key(), NOW).status, PeerCertStatus::Invalid);
    }

    /// Two certified devices dial each other the way the apps do: the responder's hello rides in
    /// message 2, the dialer checks it before sending its own in message 3, and each side sees the
    /// other as certified over a post-quantum session. An uncertified responder is caught before
    /// the dialer's message 3 is written.
    #[test]
    fn certified_devices_handshake_and_see_each_other_as_certified() {
        use crate::Handshake;
        let ca = CertificateAuthority::generate();
        let trusted = vec![ca.public_key()];
        let (phone, desk) = (Identity::generate(), Identity::generate());
        let now = 5_000;
        let phone_cert = ca.issue(phone.public_key(), "FIELD-PHONE".into(), CertProfile::Node, 1_000, 1_000_000).unwrap();
        let desk_cert = ca.issue(desk.public_key(), "WEB-DESK".into(), CertProfile::Operator, 1_000, 1_000_000).unwrap();

        let dial = |responder_cert: Option<Vec<u8>>| {
            let i = Handshake::initiator(desk.clone()).unwrap();
            let r = Handshake::responder(phone.clone()).unwrap();
            r.read_message(i.write_message(vec![]).unwrap()).unwrap();
            let m2 = r.write_message(encode_hello("Field-Phone".into(), responder_cert).unwrap()).unwrap();
            let hello = decode_hello(i.read_message(m2).unwrap()).unwrap();
            let seen = check_peer_certificate(hello.certificate, trusted.clone(), i.remote_public_key().unwrap(), now);
            (i, r, hello.name, seen)
        };

        let (i, r, name, seen_by_desk) = dial(Some(phone_cert));
        assert_eq!(name, "Field-Phone");
        assert_eq!(seen_by_desk.status, PeerCertStatus::Valid);
        assert_eq!(seen_by_desk.label.as_deref(), Some("FIELD-PHONE"));
        let m3 = i.write_message(encode_hello("Web-Desk".into(), Some(desk_cert)).unwrap()).unwrap();
        let hello = decode_hello(r.read_message(m3).unwrap()).unwrap();
        let seen_by_phone = check_peer_certificate(hello.certificate, trusted.clone(), r.remote_public_key().unwrap(), now);
        assert_eq!(seen_by_phone.status, PeerCertStatus::Valid);
        assert_eq!(seen_by_phone.label.as_deref(), Some("WEB-DESK"));
        assert_eq!(seen_by_phone.profile, Some(CertProfile::Operator));
        let (a, b) = (i.finish().unwrap(), r.finish().unwrap());
        assert!(a.post_quantum() && b.post_quantum());
        assert_eq!(a.handshake_hash(), b.handshake_hash());

        // "Require" on the dialer: an uncertified phone is refused before message 3 exists.
        let (i, _r, _, seen) = dial(None);
        assert_eq!(seen.status, PeerCertStatus::Absent);
        assert!(!i.is_finished());
    }
}
