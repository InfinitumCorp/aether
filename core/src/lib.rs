//! ÆTHER security core.
//!
//! One Rust crate shared by every ÆTHER client (Android first, iOS later).
//! It owns all cryptography so the platform layers never touch raw key
//! material beyond opaque byte buffers:
//!
//! * [`Identity`]: a long-term X25519 static key and its human-readable fingerprint.
//! * [`Handshake`] / [`Session`]: mutually authenticated, forward-secret channels built on
//!   the Noise `XX` pattern. Protocol v2 (default) is hybrid post-quantum: X25519 plus an
//!   ML-KEM-768 secret mixed in as `psk3` (`Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s`); v1
//!   (`Noise_XX_25519_ChaChaPoly_BLAKE2s`) remains for fallback.
//! * [`CertificateAuthority`]: Ed25519 device certificates over identity keys (cfssl-style profiles).
//! * [`ChatMessage`]: the versioned plaintext envelope carried inside a session.
//! * [`KeyDrop`]: an identity key card shared under a codename inside a session.
//! * Burner numbers: disposable mesh numbers bound to their own keys, plus remote-burn codes.
//! * [`Vault`]: an append-only, XChaCha20-Poly1305 encrypted record store.
//! * Geo routing is off unless `INFINITUM_GEO_ENABLED` or the settings toggle turns it on.
//!   While off, peer labels stay exactly as they were.

uniffi::setup_scaffolding!();

mod burner;
mod devicecert;
mod envelope;
mod error;
mod fingerprint;
pub mod geo;
mod hello;
mod identity;
mod keydrop;
mod session;
mod vault;

pub use burner::{
    burn_code_hash, burner_number, burner_number_matches, format_burner_number, generate_burn_code,
    parse_burner_number, verify_burn_code,
};
pub use devicecert::{
    decode_device_certificate, verify_device_certificate, CertProfile, CertificateAuthority, DeviceCertificate,
    MAX_CERT_LABEL_BYTES,
};
pub use envelope::{ChatMessage, MessageKind, MAX_BODY_BYTES};
pub use error::AetherError;
pub use fingerprint::{fingerprint, safety_number};
pub use geo::{describe_endpoint, geo_routing_enabled, set_geo_routing};
pub use hello::{check_peer_certificate, decode_hello, encode_hello, Hello, PeerCertStatus, PeerCertificate};
pub use identity::Identity;
pub use keydrop::{decode_key_drop, encode_key_drop, suggest_codename, KeyDrop};
pub use session::{Handshake, Session, LATEST_PROTOCOL, NOISE_PARAMS, NOISE_PARAMS_V2, PROLOGUE, PROLOGUE_V2};
pub use vault::Vault;

/// Version byte of the plaintext message envelope (independent of the handshake version).
pub const PROTOCOL_VERSION: u8 = 1;

/// Returns the core library version, for diagnostics screens.
#[uniffi::export]
pub fn core_version() -> String {
    format!("{} (handshake v{}, envelope v{})", env!("CARGO_PKG_VERSION"), LATEST_PROTOCOL, PROTOCOL_VERSION)
}
