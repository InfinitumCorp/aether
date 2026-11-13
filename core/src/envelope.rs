use crate::{AetherError, PROTOCOL_VERSION};

/// Noise transport messages are capped at 65535 bytes including a 16-byte tag.
const NOISE_MAX_PLAINTEXT: usize = 65535 - 16;
const HEADER_LEN: usize = 1 + 1 + 8;
/// Largest UTF-8 body a single [`ChatMessage`] can carry.
pub const MAX_BODY_BYTES: usize = NOISE_MAX_PLAINTEXT - HEADER_LEN;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageKind {
    /// A user-authored text message.
    Text,
    /// Delivery receipt; `body` carries the acknowledged message's timestamp.
    Receipt,
    /// Keep-alive with an empty body.
    Ping,
    /// An identity key card shared under a codename; `body` is an encoded [`crate::KeyDrop`].
    KeyDrop,
    /// Remote burn: `body` is the device's burn code; a match burns every burner number.
    Burn,
    /// A shared position or map marker; `body` is an encoded [`crate::PositionReport`].
    Position,
    /// A field report or tasking update; `body` is an encoded [`crate::FieldMessage`].
    Field,
}

impl MessageKind {
    fn to_byte(self) -> u8 {
        match self {
            Self::Text => 1,
            Self::Receipt => 2,
            Self::Ping => 3,
            Self::KeyDrop => 4,
            Self::Burn => 5,
            Self::Position => 6,
            Self::Field => 7,
        }
    }
    fn from_byte(b: u8) -> Option<Self> {
        Some(match b {
            1 => Self::Text,
            2 => Self::Receipt,
            3 => Self::Ping,
            4 => Self::KeyDrop,
            5 => Self::Burn,
            6 => Self::Position,
            7 => Self::Field,
            _ => return None,
        })
    }
}

/// Plaintext carried inside an encrypted session frame.
///
/// Wire layout (before encryption): `version:u8 | kind:u8 | sent_at_ms:u64be | body:utf8`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatMessage {
    pub kind: MessageKind,
    pub sent_at_ms: u64,
    pub body: String,
}

impl ChatMessage {
    pub(crate) fn encode(&self) -> Result<Vec<u8>, AetherError> {
        if self.body.len() > MAX_BODY_BYTES {
            return Err(AetherError::malformed("body too large"));
        }
        let mut out = Vec::with_capacity(HEADER_LEN + self.body.len());
        out.push(PROTOCOL_VERSION);
        out.push(self.kind.to_byte());
        out.extend_from_slice(&self.sent_at_ms.to_be_bytes());
        out.extend_from_slice(self.body.as_bytes());
        Ok(out)
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, AetherError> {
        if bytes.len() < HEADER_LEN {
            return Err(AetherError::malformed("truncated envelope"));
        }
        if bytes[0] != PROTOCOL_VERSION {
            return Err(AetherError::malformed("unsupported protocol version"));
        }
        let kind = MessageKind::from_byte(bytes[1]).ok_or_else(|| AetherError::malformed("unknown kind"))?;
        let sent_at_ms = u64::from_be_bytes(bytes[2..10].try_into().expect("8 bytes"));
        let body = std::str::from_utf8(&bytes[HEADER_LEN..])
            .map_err(|_| AetherError::malformed("body is not UTF-8"))?
            .to_owned();
        Ok(Self { kind, sent_at_ms, body })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let m = ChatMessage { kind: MessageKind::Text, sent_at_ms: 1_762_000_000_000, body: "hello ÆTHER".into() };
        assert_eq!(ChatMessage::decode(&m.encode().unwrap()).unwrap(), m);
    }

    #[test]
    fn rejects_bad_input() {
        assert!(ChatMessage::decode(&[1, 1]).is_err());
        assert!(ChatMessage::decode(&[9, 1, 0, 0, 0, 0, 0, 0, 0, 0]).is_err());
        assert!(ChatMessage::decode(&[1, 99, 0, 0, 0, 0, 0, 0, 0, 0]).is_err());
        assert!(ChatMessage::decode(&[1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0xff]).is_err());
        let big = ChatMessage { kind: MessageKind::Text, sent_at_ms: 0, body: "x".repeat(MAX_BODY_BYTES + 1) };
        assert!(big.encode().is_err());
    }
}
