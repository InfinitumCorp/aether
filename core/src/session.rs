use std::sync::{Arc, Mutex};

use snow::{Builder, HandshakeState, TransportState};
use zeroize::Zeroizing;

use crate::{fingerprint, safety_number, AetherError, ChatMessage, Identity};

/// Noise protocol name. `XX` gives mutual authentication with identity hiding
/// for both sides; every session derives fresh ephemeral keys (forward secrecy).
pub const NOISE_PARAMS: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
/// Bound into the handshake transcript so ÆTHER keys can't be replayed into
/// other Noise deployments, and so protocol versions never interoperate silently.
pub const PROLOGUE: &[u8] = b"AETHER/1";
const MAX_FRAME: usize = 65535;

fn builder(identity: &Identity) -> Result<Builder<'_>, AetherError> {
    let params = NOISE_PARAMS.parse().map_err(AetherError::handshake)?;
    Ok(Builder::new(params).local_private_key(identity.secret_bytes()).prologue(PROLOGUE))
}

/// One in-progress Noise XX handshake (3 messages: I→R, R→I, I→R).
///
/// Messages 2 and 3 can carry an encrypted payload; ÆTHER clients use it to
/// exchange display names once the peer's static key is already authenticated.
#[derive(uniffi::Object)]
pub struct Handshake {
    state: Mutex<Option<HandshakeState>>,
}

impl Handshake {
    fn with_state<T>(&self, f: impl FnOnce(&mut HandshakeState) -> Result<T, AetherError>) -> Result<T, AetherError> {
        let mut guard = self.state.lock().map_err(|_| AetherError::state("poisoned"))?;
        let hs = guard.as_mut().ok_or_else(|| AetherError::state("handshake already finished"))?;
        f(hs)
    }
}

#[uniffi::export]
impl Handshake {
    #[uniffi::constructor]
    pub fn initiator(identity: Arc<Identity>) -> Result<Arc<Self>, AetherError> {
        let hs = builder(&identity)?.build_initiator().map_err(AetherError::handshake)?;
        Ok(Arc::new(Self { state: Mutex::new(Some(hs)) }))
    }

    #[uniffi::constructor]
    pub fn responder(identity: Arc<Identity>) -> Result<Arc<Self>, AetherError> {
        let hs = builder(&identity)?.build_responder().map_err(AetherError::handshake)?;
        Ok(Arc::new(Self { state: Mutex::new(Some(hs)) }))
    }

    /// Produces the next handshake message, optionally carrying `payload`.
    pub fn write_message(&self, payload: Vec<u8>) -> Result<Vec<u8>, AetherError> {
        self.with_state(|hs| {
            let mut buf = vec![0u8; MAX_FRAME];
            let n = hs.write_message(&payload, &mut buf).map_err(AetherError::handshake)?;
            buf.truncate(n);
            Ok(buf)
        })
    }

    /// Consumes a handshake message from the peer and returns its payload.
    pub fn read_message(&self, message: Vec<u8>) -> Result<Vec<u8>, AetherError> {
        self.with_state(|hs| {
            let mut buf = vec![0u8; MAX_FRAME];
            let n = hs.read_message(&message, &mut buf).map_err(AetherError::handshake)?;
            buf.truncate(n);
            Ok(buf)
        })
    }

    /// The peer's static key once the handshake has authenticated it: after message 2 for the
    /// initiator, so a dialer can check who answered before revealing its own identity in
    /// message 3. `None` before then.
    pub fn remote_public_key(&self) -> Option<Vec<u8>> {
        self.with_state(|hs| Ok(hs.get_remote_static().map(<[u8]>::to_vec))).ok().flatten()
    }

    pub fn is_my_turn(&self) -> bool {
        self.with_state(|hs| Ok(hs.is_my_turn())).unwrap_or(false)
    }

    pub fn is_finished(&self) -> bool {
        self.with_state(|hs| Ok(hs.is_handshake_finished())).unwrap_or(false)
    }

    /// Converts a finished handshake into a transport [`Session`].
    pub fn finish(&self) -> Result<Arc<Session>, AetherError> {
        let mut guard = self.state.lock().map_err(|_| AetherError::state("poisoned"))?;
        let hs = guard.take().ok_or_else(|| AetherError::state("handshake already finished"))?;
        if !hs.is_handshake_finished() {
            *guard = Some(hs);
            return Err(AetherError::state("handshake not complete"));
        }
        let remote: [u8; 32] = hs
            .get_remote_static()
            .ok_or_else(|| AetherError::handshake("peer sent no static key"))?
            .try_into()
            .map_err(|_| AetherError::InvalidKey)?;
        let handshake_hash = hs.get_handshake_hash().to_vec();
        let transport = hs.into_transport_mode().map_err(AetherError::handshake)?;
        Ok(Arc::new(Session { transport: Mutex::new(transport), remote_static: remote, handshake_hash }))
    }
}

/// An established, mutually authenticated channel to one peer.
///
/// Frames must be delivered in order and exactly once (ÆTHER runs sessions
/// over TCP / reliable mesh links); a dropped, replayed or reordered frame
/// fails authentication.
#[derive(uniffi::Object)]
pub struct Session {
    transport: Mutex<TransportState>,
    remote_static: [u8; 32],
    handshake_hash: Vec<u8>,
}

#[uniffi::export]
impl Session {
    pub fn encrypt(&self, message: ChatMessage) -> Result<Vec<u8>, AetherError> {
        let plain = Zeroizing::new(message.encode()?);
        let mut t = self.transport.lock().map_err(|_| AetherError::state("poisoned"))?;
        let mut buf = vec![0u8; MAX_FRAME];
        let n = t.write_message(&plain, &mut buf).map_err(|_| AetherError::state("encrypt failed"))?;
        buf.truncate(n);
        Ok(buf)
    }

    pub fn decrypt(&self, frame: Vec<u8>) -> Result<ChatMessage, AetherError> {
        let mut t = self.transport.lock().map_err(|_| AetherError::state("poisoned"))?;
        let mut buf = Zeroizing::new(vec![0u8; MAX_FRAME]);
        let n = t.read_message(&frame, &mut buf).map_err(|_| AetherError::Decrypt)?;
        ChatMessage::decode(&buf[..n])
    }

    /// The peer's authenticated long-term public key.
    pub fn remote_public_key(&self) -> Vec<u8> {
        self.remote_static.to_vec()
    }

    pub fn remote_fingerprint(&self) -> String {
        fingerprint(self.remote_static.to_vec()).expect("32-byte key")
    }

    /// Number both users compare out of band to rule out a man in the middle.
    pub fn safety_number(&self, local: Arc<Identity>) -> String {
        safety_number(local.public_bytes().to_vec(), self.remote_static.to_vec()).expect("32-byte keys")
    }

    /// Transcript hash; identical on both ends of one session (channel binding).
    pub fn handshake_hash(&self) -> Vec<u8> {
        self.handshake_hash.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MessageKind;

    fn connect() -> (Arc<Identity>, Arc<Identity>, Arc<Session>, Arc<Session>) {
        let (a, b) = (Identity::generate(), Identity::generate());
        let i = Handshake::initiator(a.clone()).unwrap();
        let r = Handshake::responder(b.clone()).unwrap();
        let m1 = i.write_message(vec![]).unwrap();
        r.read_message(m1).unwrap();
        let m2 = r.write_message(b"bob".to_vec()).unwrap();
        assert_eq!(i.read_message(m2).unwrap(), b"bob");
        let m3 = i.write_message(b"alice".to_vec()).unwrap();
        assert_eq!(r.read_message(m3).unwrap(), b"alice");
        assert!(i.is_finished() && r.is_finished());
        (a, b, i.finish().unwrap(), r.finish().unwrap())
    }

    fn text(s: &str) -> ChatMessage {
        ChatMessage { kind: MessageKind::Text, sent_at_ms: 42, body: s.into() }
    }

    #[test]
    fn initiator_learns_responder_key_before_revealing_its_own() {
        let (a, b) = (Identity::generate(), Identity::generate());
        let i = Handshake::initiator(a).unwrap();
        let r = Handshake::responder(b.clone()).unwrap();
        assert_eq!(i.remote_public_key(), None);
        r.read_message(i.write_message(vec![]).unwrap()).unwrap();
        assert_eq!(r.remote_public_key(), None);
        i.read_message(r.write_message(vec![]).unwrap()).unwrap();
        assert_eq!(i.remote_public_key(), Some(b.public_key()));
        // The responder still knows nothing about the initiator at this point.
        assert_eq!(r.remote_public_key(), None);
    }

    #[test]
    fn mutual_authentication_and_round_trip() {
        let (a, b, sa, sb) = connect();
        assert_eq!(sa.remote_public_key(), b.public_key());
        assert_eq!(sb.remote_public_key(), a.public_key());
        assert_eq!(sa.safety_number(a.clone()), sb.safety_number(b.clone()));
        assert_eq!(sa.handshake_hash(), sb.handshake_hash());

        for i in 0..5 {
            let m = text(&format!("a→b {i}"));
            assert_eq!(sb.decrypt(sa.encrypt(m.clone()).unwrap()).unwrap(), m);
            let m = text(&format!("b→a {i}"));
            assert_eq!(sa.decrypt(sb.encrypt(m.clone()).unwrap()).unwrap(), m);
        }
    }

    #[test]
    fn tampered_frame_is_rejected() {
        let (_, _, sa, sb) = connect();
        let mut frame = sa.encrypt(text("secret")).unwrap();
        frame[3] ^= 0x01;
        assert!(matches!(sb.decrypt(frame), Err(AetherError::Decrypt)));
    }

    #[test]
    fn replayed_frame_is_rejected() {
        let (_, _, sa, sb) = connect();
        let frame = sa.encrypt(text("once")).unwrap();
        sb.decrypt(frame.clone()).unwrap();
        assert!(sb.decrypt(frame).is_err());
    }

    #[test]
    fn ciphertext_hides_plaintext() {
        let (_, _, sa, _) = connect();
        let frame = sa.encrypt(text("attack at dawn")).unwrap();
        assert!(!frame.windows(6).any(|w| w == b"attack"));
    }

    #[test]
    fn finish_before_complete_fails_and_keeps_state() {
        let a = Identity::generate();
        let i = Handshake::initiator(a).unwrap();
        assert!(i.finish().is_err());
        assert!(i.write_message(vec![]).is_ok());
    }

    #[test]
    fn handshake_with_garbage_fails() {
        let r = Handshake::responder(Identity::generate()).unwrap();
        assert!(r.read_message(vec![1, 2, 3]).is_err());
    }
}
