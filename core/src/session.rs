use std::sync::{Arc, Mutex, MutexGuard};

use kem::{Decapsulate, Encapsulate};
use ml_kem::{EncodedSizeUser, KemCore, MlKem768};
use rand_core::OsRng;
use snow::{Builder, HandshakeState, TransportState};
use zeroize::Zeroizing;

use crate::{fingerprint, safety_number, AetherError, ChatMessage, Identity};

/// Protocol v1 (classical). `XX` gives mutual authentication with identity hiding for both
/// sides; every session derives fresh ephemeral keys (forward secrecy).
pub const NOISE_PARAMS: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
/// Bound into the v1 transcript so ÆTHER keys can't be replayed into other Noise deployments.
pub const PROLOGUE: &[u8] = b"AETHER/1";

/// Protocol v2 (hybrid post-quantum). The same XX pattern plus an ML-KEM-768 exchange whose
/// shared secret is mixed into the Noise key schedule as the `psk3` pre-shared key:
///
/// ```text
/// -> e                   payload: ML-KEM-768 encapsulation key (fresh per session, 1184 bytes)
/// <- e, ee, s, es        payload: ML-KEM-768 ciphertext (1088 bytes) ‖ app payload
/// -> s, se, psk          psk = ML-KEM shared secret; app payload
/// ```
///
/// Transport keys depend on both the X25519 exchanges and the ML-KEM secret, so recorded
/// traffic stays confidential unless X25519 *and* ML-KEM-768 are both broken. The distinct
/// prologue and protocol name stop a v2 transcript being accepted as v1 or vice versa; a
/// responder recognises the version from the length of message 1 and answers in kind.
pub const NOISE_PARAMS_V2: &str = "Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s";
pub const PROLOGUE_V2: &[u8] = b"AETHER/2";
/// Newest protocol version this core speaks.
pub const LATEST_PROTOCOL: u8 = 2;

const MAX_FRAME: usize = 65535;
const DH_LEN: usize = 32;
/// ML-KEM-768 encapsulation key and ciphertext sizes (FIPS 203).
const EK_LEN: usize = 1184;
const CT_LEN: usize = 1088;
/// In psk handshakes Noise keys the cipher from `e`, so message 1's payload carries an AEAD tag.
const TAG_LEN: usize = 16;
/// Length of a v2 message 1 with an empty application payload.
const V2_MSG1_LEN: usize = DH_LEN + EK_LEN + TAG_LEN;

type Dk = <MlKem768 as KemCore>::DecapsulationKey;
type Ek = <MlKem768 as KemCore>::EncapsulationKey;

fn build(identity: &Identity, version: u8, initiator: bool) -> Result<HandshakeState, AetherError> {
    let (params, prologue) = match version {
        1 => (NOISE_PARAMS, PROLOGUE),
        2 => (NOISE_PARAMS_V2, PROLOGUE_V2),
        _ => return Err(AetherError::handshake("unsupported protocol version")),
    };
    let b = Builder::new(params.parse().map_err(AetherError::handshake)?)
        .local_private_key(identity.secret_bytes())
        .prologue(prologue);
    if initiator { b.build_initiator() } else { b.build_responder() }.map_err(AetherError::handshake)
}

struct Inner {
    identity: Arc<Identity>,
    initiator: bool,
    /// 0 for a responder that hasn't seen message 1 yet (it learns the dialer's version from it).
    version: u8,
    hs: Option<HandshakeState>,
    /// Handshake messages processed so far (0..=3).
    count: u8,
    /// Initiator: ephemeral ML-KEM decapsulation key, between messages 1 and 2 (v2).
    dk: Option<Dk>,
    /// Responder: the dialer's ML-KEM encapsulation key from message 1 (v2).
    peer_ek: Option<Vec<u8>>,
    finished: bool,
}

impl Inner {
    fn hs(&mut self) -> Result<&mut HandshakeState, AetherError> {
        if self.finished {
            return Err(AetherError::state("handshake already finished"));
        }
        self.hs.as_mut().ok_or_else(|| AetherError::state("waiting for the first handshake message"))
    }

    fn write(&mut self, payload: &[u8]) -> Result<Vec<u8>, AetherError> {
        self.hs()?;
        let pq = self.version == 2;
        let mut body = Vec::with_capacity(payload.len() + EK_LEN);
        let mut psk: Option<Zeroizing<Vec<u8>>> = None;
        if pq && self.initiator && self.count == 0 {
            let (dk, ek) = MlKem768::generate(&mut OsRng);
            body.extend_from_slice(&ek.as_bytes());
            self.dk = Some(dk);
        } else if pq && !self.initiator && self.count == 1 {
            let raw = self.peer_ek.take().ok_or_else(|| AetherError::state("no ML-KEM key from peer"))?;
            let enc = ml_kem::Encoded::<Ek>::try_from(&raw[..]).map_err(|_| AetherError::handshake("bad ML-KEM key"))?;
            let (ct, ss) = Ek::from_bytes(&enc)
                .encapsulate(&mut OsRng)
                .map_err(|_| AetherError::handshake("ML-KEM encapsulation failed"))?;
            body.extend_from_slice(&ct);
            psk = Some(Zeroizing::new(ss.to_vec()));
        }
        body.extend_from_slice(payload);
        let hs = self.hs()?;
        let mut buf = vec![0u8; MAX_FRAME];
        let n = hs.write_message(&body, &mut buf).map_err(AetherError::handshake)?;
        buf.truncate(n);
        if let Some(k) = psk {
            hs.set_psk(3, &k).map_err(AetherError::handshake)?;
        }
        self.count += 1;
        Ok(buf)
    }

    fn read(&mut self, message: &[u8]) -> Result<Vec<u8>, AetherError> {
        if self.hs.is_none() && !self.initiator && !self.finished {
            // Message 1 is unencrypted: a bare ephemeral key (v1), or one followed by an
            // ML-KEM encapsulation key (v2). Its length picks the version.
            let version = match message.len() {
                DH_LEN => 1,
                n if n == V2_MSG1_LEN => 2,
                _ => return Err(AetherError::handshake("unrecognised first handshake message")),
            };
            self.hs = Some(build(&self.identity, version, false)?);
            self.version = version;
        }
        let pq = self.version == 2;
        let index = self.count;
        let hs = self.hs()?;
        let mut buf = vec![0u8; MAX_FRAME];
        let n = hs.read_message(message, &mut buf).map_err(AetherError::handshake)?;
        buf.truncate(n);
        self.count += 1;
        if pq && !self.initiator && index == 0 {
            if buf.len() < EK_LEN {
                return Err(AetherError::handshake("missing ML-KEM key"));
            }
            let rest = buf.split_off(EK_LEN);
            self.peer_ek = Some(buf);
            return Ok(rest);
        }
        if pq && self.initiator && index == 1 {
            if buf.len() < CT_LEN {
                return Err(AetherError::handshake("missing ML-KEM ciphertext"));
            }
            let rest = buf.split_off(CT_LEN);
            let ct = ml_kem::Ciphertext::<MlKem768>::try_from(&buf[..])
                .map_err(|_| AetherError::handshake("bad ML-KEM ciphertext"))?;
            let dk = self.dk.take().ok_or_else(|| AetherError::state("no ML-KEM key"))?;
            let ss = Zeroizing::new(
                dk.decapsulate(&ct).map_err(|_| AetherError::handshake("ML-KEM decapsulation failed"))?.to_vec(),
            );
            self.hs()?.set_psk(3, &ss).map_err(AetherError::handshake)?;
            return Ok(rest);
        }
        Ok(buf)
    }
}

/// One in-progress ÆTHER handshake (3 messages: I→R, R→I, I→R).
///
/// [`Handshake::initiator`] dials with protocol v2 (hybrid post-quantum);
/// [`Handshake::initiator_classic`] dials v1 for peers that predate v2. A
/// [`Handshake::responder`] accepts either and answers in the dialer's version.
///
/// Messages 2 and 3 carry an encrypted payload; ÆTHER clients use it to exchange
/// display names once the peer's static key is already authenticated.
#[derive(uniffi::Object)]
pub struct Handshake {
    inner: Mutex<Inner>,
}

impl Handshake {
    fn new(identity: Arc<Identity>, initiator: bool, version: u8) -> Result<Arc<Self>, AetherError> {
        let hs = if version == 0 { None } else { Some(build(&identity, version, initiator)?) };
        Ok(Arc::new(Self {
            inner: Mutex::new(Inner { identity, initiator, version, hs, count: 0, dk: None, peer_ek: None, finished: false }),
        }))
    }

    fn lock(&self) -> Result<MutexGuard<'_, Inner>, AetherError> {
        self.inner.lock().map_err(|_| AetherError::state("poisoned"))
    }
}

#[uniffi::export]
impl Handshake {
    /// Dials with the latest protocol (v2, hybrid X25519 + ML-KEM-768).
    #[uniffi::constructor]
    pub fn initiator(identity: Arc<Identity>) -> Result<Arc<Self>, AetherError> {
        Self::new(identity, true, LATEST_PROTOCOL)
    }

    /// Dials with protocol v1 (classical X25519 only), for peers that don't speak v2.
    #[uniffi::constructor]
    pub fn initiator_classic(identity: Arc<Identity>) -> Result<Arc<Self>, AetherError> {
        Self::new(identity, true, 1)
    }

    /// Accepts v1 and v2 dialers.
    #[uniffi::constructor]
    pub fn responder(identity: Arc<Identity>) -> Result<Arc<Self>, AetherError> {
        Self::new(identity, false, 0)
    }

    /// Produces the next handshake message, optionally carrying `payload`.
    pub fn write_message(&self, payload: Vec<u8>) -> Result<Vec<u8>, AetherError> {
        self.lock()?.write(&payload)
    }

    /// Consumes a handshake message from the peer and returns its payload.
    pub fn read_message(&self, message: Vec<u8>) -> Result<Vec<u8>, AetherError> {
        self.lock()?.read(&message)
    }

    /// The peer's static key once the handshake has authenticated it: after message 2 for the
    /// initiator, so a dialer can check who answered before revealing its own identity in
    /// message 3. `None` before then.
    pub fn remote_public_key(&self) -> Option<Vec<u8>> {
        let mut g = self.lock().ok()?;
        g.hs().ok()?.get_remote_static().map(<[u8]>::to_vec)
    }

    /// Protocol version in use: 1 or 2, or 0 for a responder before message 1.
    pub fn protocol_version(&self) -> u8 {
        self.lock().map(|g| g.version).unwrap_or(0)
    }

    pub fn is_my_turn(&self) -> bool {
        let Ok(mut g) = self.lock() else { return false };
        g.hs().map(|hs| hs.is_my_turn()).unwrap_or(false)
    }

    pub fn is_finished(&self) -> bool {
        let Ok(mut g) = self.lock() else { return false };
        g.hs().map(|hs| hs.is_handshake_finished()).unwrap_or(false)
    }

    /// Converts a finished handshake into a transport [`Session`].
    pub fn finish(&self) -> Result<Arc<Session>, AetherError> {
        let mut g = self.lock()?;
        if !g.hs()?.is_handshake_finished() {
            return Err(AetherError::state("handshake not complete"));
        }
        let version = g.version;
        let hs = g.hs.take().expect("checked above");
        g.finished = true;
        let remote: [u8; 32] = hs
            .get_remote_static()
            .ok_or_else(|| AetherError::handshake("peer sent no static key"))?
            .try_into()
            .map_err(|_| AetherError::InvalidKey)?;
        let handshake_hash = hs.get_handshake_hash().to_vec();
        let transport = hs.into_transport_mode().map_err(AetherError::handshake)?;
        Ok(Arc::new(Session { transport: Mutex::new(transport), remote_static: remote, handshake_hash, version }))
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
    version: u8,
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

    /// 2 for the hybrid post-quantum handshake, 1 for classical X25519 only.
    pub fn protocol_version(&self) -> u8 {
        self.version
    }

    /// True when the session keys also depend on an ML-KEM-768 shared secret.
    pub fn post_quantum(&self) -> bool {
        self.version >= 2
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

    fn run(i: &Handshake, r: &Handshake) -> (Arc<Session>, Arc<Session>) {
        r.read_message(i.write_message(vec![]).unwrap()).unwrap();
        assert_eq!(i.read_message(r.write_message(b"bob".to_vec()).unwrap()).unwrap(), b"bob");
        assert_eq!(r.read_message(i.write_message(b"alice".to_vec()).unwrap()).unwrap(), b"alice");
        (i.finish().unwrap(), r.finish().unwrap())
    }

    #[test]
    fn default_handshake_is_post_quantum() {
        let (a, b) = (Identity::generate(), Identity::generate());
        let i = Handshake::initiator(a.clone()).unwrap();
        let r = Handshake::responder(b.clone()).unwrap();
        assert_eq!(r.protocol_version(), 0);
        let (sa, sb) = run(&i, &r);
        assert!(sa.post_quantum() && sb.post_quantum());
        assert_eq!((sa.protocol_version(), sb.protocol_version()), (2, 2));
        assert_eq!(sa.handshake_hash(), sb.handshake_hash());
        assert_eq!(sa.remote_public_key(), b.public_key());
        assert_eq!(sb.remote_public_key(), a.public_key());
        let m = text("pq");
        assert_eq!(sb.decrypt(sa.encrypt(m.clone()).unwrap()).unwrap(), m);
        assert_eq!(sa.decrypt(sb.encrypt(m.clone()).unwrap()).unwrap(), m);
    }

    #[test]
    fn v2_first_message_carries_ml_kem_key() {
        let i = Handshake::initiator(Identity::generate()).unwrap();
        assert_eq!(i.write_message(vec![]).unwrap().len(), V2_MSG1_LEN);
        let c = Handshake::initiator_classic(Identity::generate()).unwrap();
        assert_eq!(c.write_message(vec![]).unwrap().len(), DH_LEN);
    }

    #[test]
    fn classic_dialer_falls_back_to_v1() {
        let i = Handshake::initiator_classic(Identity::generate()).unwrap();
        let r = Handshake::responder(Identity::generate()).unwrap();
        let (sa, sb) = run(&i, &r);
        assert!(!sa.post_quantum() && !sb.post_quantum());
        assert_eq!(r.protocol_version(), 1);
        let m = text("classic");
        assert_eq!(sb.decrypt(sa.encrypt(m.clone()).unwrap()).unwrap(), m);
    }

    #[test]
    fn tampered_ml_kem_ciphertext_breaks_the_handshake() {
        let i = Handshake::initiator(Identity::generate()).unwrap();
        let r = Handshake::responder(Identity::generate()).unwrap();
        r.read_message(i.write_message(vec![]).unwrap()).unwrap();
        let mut m2 = r.write_message(vec![]).unwrap();
        let last = m2.len() - 20; // inside the encrypted payload (ML-KEM ciphertext)
        m2[last] ^= 0x01;
        assert!(i.read_message(m2).is_err());
    }

    #[test]
    fn tampered_ml_kem_key_breaks_the_handshake() {
        // Message 1's payload (the encapsulation key) is AEAD-protected under a key mixed from
        // `e`, so a flipped bit is caught by the responder before it encapsulates anything.
        let i = Handshake::initiator(Identity::generate()).unwrap();
        let r = Handshake::responder(Identity::generate()).unwrap();
        let mut m1 = i.write_message(vec![]).unwrap();
        m1[DH_LEN + 100] ^= 0x01;
        assert!(r.read_message(m1).is_err());
    }

    #[test]
    fn v1_and_v2_transcripts_differ() {
        // A v2 session can't be confused with a v1 one: different prologue and protocol name.
        let (a, b) = (Identity::generate(), Identity::generate());
        let (s2, _) = run(&Handshake::initiator(a.clone()).unwrap(), &Handshake::responder(b.clone()).unwrap());
        let (s1, _) = run(&Handshake::initiator_classic(a).unwrap(), &Handshake::responder(b).unwrap());
        assert_ne!(s1.handshake_hash(), s2.handshake_hash());
    }

    #[test]
    fn malformed_first_message_lengths_are_rejected() {
        for len in [0, 31, 33, V2_MSG1_LEN - 1, V2_MSG1_LEN + 1] {
            let r = Handshake::responder(Identity::generate()).unwrap();
            assert!(r.read_message(vec![7; len]).is_err(), "len {len}");
        }
    }

    #[test]
    fn responder_cannot_write_before_reading() {
        let r = Handshake::responder(Identity::generate()).unwrap();
        assert!(r.write_message(vec![]).is_err());
        assert!(!r.is_my_turn());
    }
}
