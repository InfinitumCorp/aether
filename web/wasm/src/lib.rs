//! ÆTHER security core for the browser.
//!
//! A thin wasm-bindgen wrapper over `aether-core`: the browser app uses exactly the same
//! Noise XX handshake, envelopes, Key Drop and burner-number code as ÆTHER Mobile MIL™.
//! JavaScript only moves opaque byte buffers.

use std::sync::Arc;

use aether_core as core;
use wasm_bindgen::prelude::*;

fn err(e: core::AetherError) -> JsError {
    JsError::new(&e.to_string())
}

#[wasm_bindgen(js_name = coreVersion)]
pub fn core_version() -> String {
    core::core_version()
}

#[wasm_bindgen]
pub struct Identity(Arc<core::Identity>);

#[wasm_bindgen]
impl Identity {
    pub fn generate() -> Identity {
        Identity(core::Identity::generate())
    }
    #[wasm_bindgen(js_name = fromSecret)]
    pub fn from_secret(secret: Vec<u8>) -> Result<Identity, JsError> {
        core::Identity::from_secret(secret).map(Identity).map_err(err)
    }
    #[wasm_bindgen(js_name = publicKey)]
    pub fn public_key(&self) -> Vec<u8> {
        self.0.public_key()
    }
    #[wasm_bindgen(js_name = exportSecret)]
    pub fn export_secret(&self) -> Vec<u8> {
        self.0.export_secret()
    }
    pub fn fingerprint(&self) -> String {
        self.0.fingerprint()
    }
}

#[wasm_bindgen]
pub struct Handshake(Arc<core::Handshake>);

#[wasm_bindgen]
impl Handshake {
    pub fn initiator(identity: &Identity) -> Result<Handshake, JsError> {
        core::Handshake::initiator(identity.0.clone()).map(Handshake).map_err(err)
    }
    /// Dials with protocol v1 (classical only), for peers that don't speak v2.
    #[wasm_bindgen(js_name = initiatorClassic)]
    pub fn initiator_classic(identity: &Identity) -> Result<Handshake, JsError> {
        core::Handshake::initiator_classic(identity.0.clone()).map(Handshake).map_err(err)
    }
    pub fn responder(identity: &Identity) -> Result<Handshake, JsError> {
        core::Handshake::responder(identity.0.clone()).map(Handshake).map_err(err)
    }
    #[wasm_bindgen(js_name = protocolVersion)]
    pub fn protocol_version(&self) -> u8 {
        self.0.protocol_version()
    }
    #[wasm_bindgen(js_name = writeMessage)]
    pub fn write_message(&self, payload: Vec<u8>) -> Result<Vec<u8>, JsError> {
        self.0.write_message(payload).map_err(err)
    }
    #[wasm_bindgen(js_name = readMessage)]
    pub fn read_message(&self, message: Vec<u8>) -> Result<Vec<u8>, JsError> {
        self.0.read_message(message).map_err(err)
    }
    #[wasm_bindgen(js_name = remotePublicKey)]
    pub fn remote_public_key(&self) -> Option<Vec<u8>> {
        self.0.remote_public_key()
    }
    #[wasm_bindgen(js_name = isFinished)]
    pub fn is_finished(&self) -> bool {
        self.0.is_finished()
    }
    pub fn finish(&self) -> Result<Session, JsError> {
        self.0.finish().map(Session).map_err(err)
    }
}

/// A decrypted envelope.
#[wasm_bindgen(getter_with_clone)]
pub struct Message {
    /// 1 text, 2 receipt, 3 ping, 4 key drop, 5 burn.
    pub kind: u8,
    #[wasm_bindgen(js_name = sentAtMs)]
    pub sent_at_ms: f64,
    pub body: String,
}

fn kind_from(k: u8) -> Result<core::MessageKind, JsError> {
    use core::MessageKind::*;
    Ok(match k {
        1 => Text,
        2 => Receipt,
        3 => Ping,
        4 => KeyDrop,
        5 => Burn,
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
    }
}

#[wasm_bindgen]
pub struct Session(Arc<core::Session>);

#[wasm_bindgen]
impl Session {
    pub fn encrypt(&self, kind: u8, sent_at_ms: f64, body: String) -> Result<Vec<u8>, JsError> {
        let m = core::ChatMessage { kind: kind_from(kind)?, sent_at_ms: sent_at_ms as u64, body };
        self.0.encrypt(m).map_err(err)
    }
    pub fn decrypt(&self, frame: Vec<u8>) -> Result<Message, JsError> {
        let m = self.0.decrypt(frame).map_err(err)?;
        Ok(Message { kind: kind_to(m.kind), sent_at_ms: m.sent_at_ms as f64, body: m.body })
    }
    #[wasm_bindgen(js_name = remotePublicKey)]
    pub fn remote_public_key(&self) -> Vec<u8> {
        self.0.remote_public_key()
    }
    #[wasm_bindgen(js_name = protocolVersion)]
    pub fn protocol_version(&self) -> u8 {
        self.0.protocol_version()
    }
    /// True when the session keys also depend on an ML-KEM-768 secret.
    #[wasm_bindgen(js_name = postQuantum)]
    pub fn post_quantum(&self) -> bool {
        self.0.post_quantum()
    }
    #[wasm_bindgen(js_name = remoteFingerprint)]
    pub fn remote_fingerprint(&self) -> String {
        self.0.remote_fingerprint()
    }
    #[wasm_bindgen(js_name = safetyNumber)]
    pub fn safety_number(&self, local: &Identity) -> String {
        self.0.safety_number(local.0.clone())
    }
}

#[wasm_bindgen(js_name = burnerNumber)]
pub fn burner_number(public_key: Vec<u8>) -> Result<String, JsError> {
    core::burner_number(public_key).map_err(err)
}

#[wasm_bindgen(js_name = formatBurnerNumber)]
pub fn format_burner_number(number: String) -> String {
    core::format_burner_number(number)
}

#[wasm_bindgen(js_name = parseBurnerNumber)]
pub fn parse_burner_number(input: String) -> Option<String> {
    core::parse_burner_number(input).ok()
}

#[wasm_bindgen(js_name = suggestCodename)]
pub fn suggest_codename() -> String {
    core::suggest_codename()
}

/// Encodes a Key Drop body; `addresses` is a comma-separated list.
#[wasm_bindgen(js_name = encodeKeyDrop)]
pub fn encode_key_drop(codename: String, name: String, public_key: Vec<u8>, addresses: String) -> Result<String, JsError> {
    let addresses = addresses.split(',').map(str::trim).filter(|s| !s.is_empty()).map(String::from).collect();
    core::encode_key_drop(core::KeyDrop { codename, name, public_key, addresses }).map_err(err)
}

#[wasm_bindgen(getter_with_clone)]
pub struct Drop {
    pub codename: String,
    pub name: String,
    #[wasm_bindgen(js_name = publicKey)]
    pub public_key: Vec<u8>,
    pub addresses: Vec<String>,
}

#[wasm_bindgen(js_name = decodeKeyDrop)]
pub fn decode_key_drop(body: String) -> Result<Drop, JsError> {
    let d = core::decode_key_drop(body).map_err(err)?;
    Ok(Drop { codename: d.codename, name: d.name, public_key: d.public_key, addresses: d.addresses })
}

/// Verifies an ÆTHER device certificate; returns its label, or throws.
#[wasm_bindgen(js_name = verifyDeviceCertificate)]
pub fn verify_device_certificate(certificate: Vec<u8>, ca_public_key: Vec<u8>, device_public_key: Vec<u8>, now_ms: f64) -> Result<String, JsError> {
    core::verify_device_certificate(certificate, ca_public_key, device_public_key, now_ms as u64).map(|c| c.label).map_err(err)
}

/// Encodes the handshake hello: display name plus this device's certificate (empty = none).
#[wasm_bindgen(js_name = encodeHello)]
pub fn encode_hello(name: String, certificate: Vec<u8>) -> Result<Vec<u8>, JsError> {
    core::encode_hello(name, (!certificate.is_empty()).then_some(certificate)).map_err(err)
}

#[wasm_bindgen(getter_with_clone)]
pub struct Hello {
    pub name: String,
    /// Empty when the peer sent no certificate.
    pub certificate: Vec<u8>,
}

#[wasm_bindgen(js_name = decodeHello)]
pub fn decode_hello(payload: Vec<u8>) -> Result<Hello, JsError> {
    let h = core::decode_hello(payload).map_err(err)?;
    Ok(Hello { name: h.name, certificate: h.certificate.unwrap_or_default() })
}

#[wasm_bindgen(getter_with_clone)]
pub struct PeerCert {
    /// "absent", "valid", "untrusted" or "invalid".
    pub status: String,
    pub label: String,
    pub profile: String,
    pub serial: String,
    pub reason: String,
}

/// Checks a peer's certificate (empty = none) against trusted CA keys (concatenated 32-byte keys).
#[wasm_bindgen(js_name = checkPeerCertificate)]
pub fn check_peer_certificate(certificate: Vec<u8>, trusted_ca_keys: Vec<u8>, peer_public_key: Vec<u8>, now_ms: f64) -> PeerCert {
    let cas = trusted_ca_keys.chunks_exact(32).map(<[u8]>::to_vec).collect();
    let c = core::check_peer_certificate((!certificate.is_empty()).then_some(certificate), cas, peer_public_key, now_ms as u64);
    let status = match c.status {
        core::PeerCertStatus::Absent => "absent",
        core::PeerCertStatus::Valid => "valid",
        core::PeerCertStatus::Untrusted => "untrusted",
        core::PeerCertStatus::Invalid => "invalid",
    };
    PeerCert {
        status: status.into(),
        label: c.label.unwrap_or_default(),
        profile: c.profile.map(|p| p.name().to_owned()).unwrap_or_default(),
        serial: c.serial.unwrap_or_default(),
        reason: c.reason,
    }
}

#[wasm_bindgen(getter_with_clone)]
pub struct CertInfo {
    pub label: String,
    pub profile: String,
    pub serial: String,
    #[wasm_bindgen(js_name = devicePublicKey)]
    pub device_public_key: Vec<u8>,
    #[wasm_bindgen(js_name = notAfterMs)]
    pub not_after_ms: f64,
    #[wasm_bindgen(js_name = caKeyId)]
    pub ca_key_id: String,
}

/// Decodes a certificate without verifying it (to show what was imported).
#[wasm_bindgen(js_name = decodeDeviceCertificate)]
pub fn decode_device_certificate(certificate: Vec<u8>) -> Result<CertInfo, JsError> {
    let c = core::decode_device_certificate(certificate).map_err(err)?;
    Ok(CertInfo {
        label: c.label,
        profile: c.profile.name().into(),
        serial: c.serial,
        device_public_key: c.device_public_key,
        not_after_ms: c.not_after_ms as f64,
        ca_key_id: c.ca_key_id,
    })
}
