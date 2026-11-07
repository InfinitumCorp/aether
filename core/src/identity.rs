use std::sync::Arc;

use rand_core::OsRng;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::{fingerprint::fingerprint, AetherError};

/// A device's long-term identity: an X25519 static key pair.
///
/// The secret is held in zeroizing memory and only leaves the core through
/// [`Identity::export_secret`], which the platform layer stores inside an
/// encrypted [`crate::Vault`] whose key is wrapped by the hardware keystore.
#[derive(uniffi::Object)]
pub struct Identity {
    secret: Zeroizing<[u8; 32]>,
    public: [u8; 32],
}

impl Identity {
    pub(crate) fn secret_bytes(&self) -> &[u8; 32] {
        &self.secret
    }
    pub(crate) fn public_bytes(&self) -> &[u8; 32] {
        &self.public
    }
}

#[uniffi::export]
impl Identity {
    /// Creates a fresh identity from the OS CSPRNG.
    #[uniffi::constructor]
    pub fn generate() -> Arc<Self> {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        Arc::new(Self { secret: Zeroizing::new(secret.to_bytes()), public: public.to_bytes() })
    }

    /// Restores an identity from its 32-byte secret.
    #[uniffi::constructor]
    pub fn from_secret(secret: Vec<u8>) -> Result<Arc<Self>, AetherError> {
        let secret = Zeroizing::new(secret);
        let bytes: [u8; 32] = secret.as_slice().try_into().map_err(|_| AetherError::InvalidKey)?;
        let sk = StaticSecret::from(bytes);
        let public = PublicKey::from(&sk);
        Ok(Arc::new(Self { secret: Zeroizing::new(sk.to_bytes()), public: public.to_bytes() }))
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.public.to_vec()
    }

    /// The raw secret, for persisting into an encrypted vault only.
    pub fn export_secret(&self) -> Vec<u8> {
        self.secret.to_vec()
    }

    pub fn fingerprint(&self) -> String {
        fingerprint(self.public.to_vec()).expect("public key is always 32 bytes")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restore_round_trip() {
        let id = Identity::generate();
        let restored = Identity::from_secret(id.export_secret()).unwrap();
        assert_eq!(id.public_key(), restored.public_key());
        assert_eq!(id.fingerprint(), restored.fingerprint());
    }

    #[test]
    fn distinct_identities() {
        assert_ne!(Identity::generate().public_key(), Identity::generate().public_key());
    }

    #[test]
    fn rejects_short_secret() {
        assert!(matches!(Identity::from_secret(vec![1, 2, 3]), Err(AetherError::InvalidKey)));
    }
}
