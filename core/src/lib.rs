//! ÆTHER security core.
//!
//! One Rust crate shared by every ÆTHER client (Android first, iOS later).
//! It owns all cryptography so the platform layers never touch raw key
//! material beyond opaque byte buffers:
//!
//! * [`Identity`]: a long-term X25519 static key and its human-readable fingerprint.
//! * [`Handshake`] / [`Session`]: mutually authenticated, forward-secret channels
//!   built on the Noise `XX` pattern (`Noise_XX_25519_ChaChaPoly_BLAKE2s`).
//! * [`ChatMessage`]: the versioned plaintext envelope carried inside a session.
//! * [`KeyDrop`]: an identity key card shared under a codename inside a session.
//! * Burner numbers: disposable mesh numbers bound to their own keys, plus remote-burn codes.
//! * [`Vault`]: an append-only, XChaCha20-Poly1305 encrypted record store.

uniffi::setup_scaffolding!();

mod burner;
mod envelope;
mod error;
mod fingerprint;
mod identity;
mod keydrop;
mod session;
mod vault;

pub use burner::{
    burn_code_hash, burner_number, burner_number_matches, format_burner_number, generate_burn_code,
    parse_burner_number, verify_burn_code,
};
pub use envelope::{ChatMessage, MessageKind, MAX_BODY_BYTES};
pub use error::AetherError;
pub use fingerprint::{fingerprint, safety_number};
pub use identity::Identity;
pub use keydrop::{decode_key_drop, encode_key_drop, suggest_codename, KeyDrop};
pub use session::{Handshake, Session, NOISE_PARAMS, PROLOGUE};
pub use vault::Vault;

/// Version of the ÆTHER wire protocol implemented by this core.
pub const PROTOCOL_VERSION: u8 = 1;

/// Returns the core library version, for diagnostics screens.
#[uniffi::export]
pub fn core_version() -> String {
    format!("{} (protocol {})", env!("CARGO_PKG_VERSION"), PROTOCOL_VERSION)
}
