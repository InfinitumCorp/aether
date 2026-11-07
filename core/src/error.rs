/// Every failure the core can report across the FFI boundary.
///
/// Messages are deliberately coarse: they never include key material,
/// plaintext or ciphertext.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AetherError {
    #[error("invalid key material")]
    InvalidKey,
    #[error("handshake failed: {reason}")]
    Handshake { reason: String },
    #[error("message could not be authenticated")]
    Decrypt,
    #[error("malformed message: {reason}")]
    Malformed { reason: String },
    #[error("vault error: {reason}")]
    Vault { reason: String },
    #[error("invalid state: {reason}")]
    State { reason: String },
}

impl AetherError {
    pub(crate) fn handshake(e: impl std::fmt::Display) -> Self {
        Self::Handshake { reason: e.to_string() }
    }
    pub(crate) fn malformed(reason: &str) -> Self {
        Self::Malformed { reason: reason.to_owned() }
    }
    pub(crate) fn vault(e: impl std::fmt::Display) -> Self {
        Self::Vault { reason: e.to_string() }
    }
    pub(crate) fn state(reason: &str) -> Self {
        Self::State { reason: reason.to_owned() }
    }
}
