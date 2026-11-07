//! Key Drop: an operator's identity key card, dropped to a teammate under a
//! codename and carried end-to-end encrypted inside an ÆTHER Talk™ session
//! (`MessageKind::KeyDrop`). The recipient pins the key; a later connection is
//! only trusted as that codename if the peer proves possession of the pinned key.
//!
//! Body layout (UTF-8, newline separated, no trailing newline):
//! `AETHER-DROP/1 \n codename \n display name \n public key (64 hex) \n addr,addr`

use crate::fingerprint::check_key;
use crate::AetherError;
use rand_core::{OsRng, RngCore};

const MAGIC: &str = "AETHER-DROP/1";
pub const MAX_CODENAME_CHARS: usize = 32;
pub const MAX_NAME_CHARS: usize = 64;
const MAX_ADDRESSES: usize = 4;
const MAX_ADDRESS_CHARS: usize = 64;

/// An identity key card shared under a codename.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct KeyDrop {
    /// Short, memorable label chosen by the sender, e.g. "NIGHTJAR-27".
    pub codename: String,
    /// Display name of the operator whose key this is.
    pub name: String,
    /// The operator's 32-byte X25519 static public key.
    pub public_key: Vec<u8>,
    /// Where the operator was last reachable (`host:port`), at most 4.
    pub addresses: Vec<String>,
}

fn clean_label(s: &str, max: usize, what: &str) -> Result<String, AetherError> {
    let t = s.trim();
    let n = t.chars().count();
    if n == 0 || n > max {
        return Err(AetherError::malformed(&format!("{what} must be 1-{max} characters")));
    }
    if t.chars().any(|c| c.is_control()) {
        return Err(AetherError::malformed(&format!("{what} contains control characters")));
    }
    Ok(t.to_owned())
}

fn clean_address(a: &str) -> Result<String, AetherError> {
    let t = a.trim();
    let ok = !t.is_empty()
        && t.len() <= MAX_ADDRESS_CHARS
        && t.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ':' | '-' | '[' | ']' | '%'));
    if !ok {
        return Err(AetherError::malformed("invalid address"));
    }
    Ok(t.to_owned())
}

fn validate(d: &KeyDrop) -> Result<KeyDrop, AetherError> {
    check_key(&d.public_key)?;
    if d.addresses.len() > MAX_ADDRESSES {
        return Err(AetherError::malformed("too many addresses"));
    }
    Ok(KeyDrop {
        codename: clean_label(&d.codename, MAX_CODENAME_CHARS, "codename")?,
        name: clean_label(&d.name, MAX_NAME_CHARS, "name")?,
        public_key: d.public_key.clone(),
        addresses: d.addresses.iter().map(|a| clean_address(a)).collect::<Result<_, _>>()?,
    })
}

/// Validates a drop and encodes it as a `KeyDrop` message body.
#[uniffi::export]
pub fn encode_key_drop(drop: KeyDrop) -> Result<String, AetherError> {
    let d = validate(&drop)?;
    let hex: String = d.public_key.iter().map(|b| format!("{b:02x}")).collect();
    Ok([MAGIC, &d.codename, &d.name, &hex, &d.addresses.join(",")].join("\n"))
}

/// Parses and validates a `KeyDrop` message body received from a peer.
#[uniffi::export]
pub fn decode_key_drop(body: String) -> Result<KeyDrop, AetherError> {
    let parts: Vec<&str> = body.split('\n').collect();
    if parts.len() != 5 || parts[0] != MAGIC {
        return Err(AetherError::malformed("not a key drop"));
    }
    let hex = parts[3];
    if hex.len() != 64 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(AetherError::InvalidKey);
    }
    let public_key = (0..32)
        .map(|i| u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).map_err(|_| AetherError::InvalidKey))
        .collect::<Result<Vec<u8>, _>>()?;
    let addresses = if parts[4].is_empty() { vec![] } else { parts[4].split(',').map(str::to_owned).collect() };
    validate(&KeyDrop { codename: parts[1].to_owned(), name: parts[2].to_owned(), public_key, addresses })
}

const WORDS: [&str; 32] = [
    "NIGHTJAR", "OSPREY", "KESTREL", "MERLIN", "RAVEN", "HARRIER", "FALCON", "SHRIKE",
    "WRAITH", "SPECTRE", "PHANTOM", "VORTEX", "TEMPEST", "CYCLONE", "MISTRAL", "SIROCCO",
    "OBSIDIAN", "COBALT", "TUNGSTEN", "ONYX", "GRANITE", "BASALT", "QUARTZ", "FLINT",
    "LANTERN", "BEACON", "COMPASS", "SEXTANT", "ANCHOR", "TALON", "ARROW", "CIPHER",
];

/// A random, catchy default codename such as `KESTREL-42`.
#[uniffi::export]
pub fn suggest_codename() -> String {
    let r = OsRng.next_u32();
    format!("{}-{:02}", WORDS[(r % 32) as usize], (r >> 8) % 100)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> KeyDrop {
        KeyDrop {
            codename: "NIGHTJAR-27".into(),
            name: "Bravo-2".into(),
            public_key: (0u8..32).collect(),
            addresses: vec!["192.168.1.20:7337".into(), "10.0.2.15:7337".into()],
        }
    }

    #[test]
    fn round_trip() {
        let body = encode_key_drop(sample()).unwrap();
        assert_eq!(decode_key_drop(body).unwrap(), sample());
        let none = KeyDrop { addresses: vec![], ..sample() };
        assert_eq!(decode_key_drop(encode_key_drop(none.clone()).unwrap()).unwrap(), none);
    }

    #[test]
    fn trims_labels() {
        let d = KeyDrop { codename: "  Rally Point ".into(), ..sample() };
        assert_eq!(decode_key_drop(encode_key_drop(d).unwrap()).unwrap().codename, "Rally Point");
    }

    #[test]
    fn rejects_bad_drops() {
        assert!(encode_key_drop(KeyDrop { public_key: vec![1; 31], ..sample() }).is_err());
        assert!(encode_key_drop(KeyDrop { codename: " ".into(), ..sample() }).is_err());
        assert!(encode_key_drop(KeyDrop { codename: "x".repeat(33), ..sample() }).is_err());
        assert!(encode_key_drop(KeyDrop { name: "a\nb".into(), ..sample() }).is_err());
        assert!(encode_key_drop(KeyDrop { addresses: vec!["a,b".into()], ..sample() }).is_err());
        assert!(encode_key_drop(KeyDrop { addresses: vec!["h:1".into(); 5], ..sample() }).is_err());
        assert!(decode_key_drop("hello".into()).is_err());
        let body = encode_key_drop(sample()).unwrap();
        assert!(decode_key_drop(body.replace("AETHER-DROP/1", "AETHER-DROP/9")).is_err());
        let bad_hex = body.replacen("000102", "zz0102", 1);
        assert!(decode_key_drop(bad_hex).is_err());
        assert!(decode_key_drop(format!("{body}\nextra")).is_err());
    }

    #[test]
    fn codenames_are_well_formed() {
        for _ in 0..50 {
            let c = suggest_codename();
            let (w, n) = c.split_once('-').unwrap();
            assert!(WORDS.contains(&w) && n.len() == 2 && n.parse::<u8>().is_ok());
        }
    }
}
