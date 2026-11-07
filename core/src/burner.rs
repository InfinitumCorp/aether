//! Burner numbers: disposable ÆTHER mesh numbers that let an operator call and
//! text without exposing their real identity key or name.
//!
//! A burner number is its own X25519 identity. Its 10-digit number is derived
//! from that key, so a number is self-authenticating: a peer reached at a number
//! must prove the key the number was derived from. Burning a number destroys the
//! key (on Android, a Keystore-wrapped per-number vault), after which the number
//! can never be answered again.
//!
//! A remote burn is authorised by a per-device burn code: 128 random bits shown
//! once to the operator in Crockford base32, stored on the device only as a hash.

use crate::fingerprint::check_key;
use crate::AetherError;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

const NUMBER_DOMAIN: &[u8] = b"AETHER-NUMBER-v1";
const BURN_DOMAIN: &[u8] = b"AETHER-BURN-v1";
const NUMBER_DIGITS: u64 = 10_000_000_000;
const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const BURN_CODE_BYTES: usize = 16;

/// The 10-digit mesh number for a burner's public key.
#[uniffi::export]
pub fn burner_number(public_key: Vec<u8>) -> Result<String, AetherError> {
    check_key(&public_key)?;
    let h = Sha256::new().chain_update(NUMBER_DOMAIN).chain_update(&public_key).finalize();
    let n = u64::from_be_bytes(h[..8].try_into().expect("8 bytes")) % NUMBER_DIGITS;
    Ok(format!("{n:010}"))
}

/// Display form of a 10-digit mesh number: `ÆN 733 482 9173`.
#[uniffi::export]
pub fn format_burner_number(digits: String) -> String {
    if digits.len() == 10 && digits.bytes().all(|b| b.is_ascii_digit()) {
        format!("ÆN {} {} {}", &digits[..3], &digits[3..6], &digits[6..])
    } else {
        digits
    }
}

/// Normalises user input (`ÆN 733-482-9173`, `7334829173`, …) to 10 digits.
#[uniffi::export]
pub fn parse_burner_number(input: String) -> Result<String, AetherError> {
    let s = input.trim();
    let s = s.strip_prefix("ÆN").or_else(|| s.strip_prefix("AEN")).unwrap_or(s);
    let digits: String = s.chars().filter(|c| !matches!(c, ' ' | '-' | '.' | '(' | ')')).collect();
    if digits.len() == 10 && digits.bytes().all(|b| b.is_ascii_digit()) {
        Ok(digits)
    } else {
        Err(AetherError::malformed("a burner number has 10 digits"))
    }
}

/// True if `public_key` is the key behind `number` (10 digits).
#[uniffi::export]
pub fn burner_number_matches(number: String, public_key: Vec<u8>) -> bool {
    burner_number(public_key).map(|n| n == number).unwrap_or(false)
}

fn encode_crockford(bytes: &[u8]) -> String {
    let mut out = String::new();
    let (mut acc, mut bits) = (0u32, 0u32);
    for &b in bytes {
        acc = (acc << 8) | b as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(CROCKFORD[((acc >> bits) & 31) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(CROCKFORD[((acc << (5 - bits)) & 31) as usize] as char);
    }
    out
}

fn decode_crockford(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in s.chars() {
        let c = match c.to_ascii_uppercase() {
            '-' | ' ' => continue,
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        };
        let v = CROCKFORD.iter().position(|&x| x as char == c)? as u32;
        acc = (acc << 5) | v;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// A new remote-burn code: 128 random bits as `XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X`.
#[uniffi::export]
pub fn generate_burn_code() -> String {
    let mut raw = [0u8; BURN_CODE_BYTES];
    OsRng.fill_bytes(&mut raw);
    let s = encode_crockford(&raw);
    s.as_bytes().chunks(5).map(|c| std::str::from_utf8(c).expect("ascii")).collect::<Vec<_>>().join("-")
}

/// Hash of a burn code for storage. Accepts any spacing, case and O/0, I/L/1 confusion.
#[uniffi::export]
pub fn burn_code_hash(code: String) -> Result<Vec<u8>, AetherError> {
    let raw = decode_crockford(&code)
        .filter(|r| r.len() == BURN_CODE_BYTES)
        .ok_or_else(|| AetherError::malformed("not a burn code"))?;
    Ok(Sha256::new().chain_update(BURN_DOMAIN).chain_update(&raw).finalize().to_vec())
}

/// Constant-time check of a presented burn code against a stored hash.
#[uniffi::export]
pub fn verify_burn_code(code: String, stored_hash: Vec<u8>) -> bool {
    let Ok(h) = burn_code_hash(code) else { return false };
    h.len() == stored_hash.len() && h.iter().zip(&stored_hash).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_are_stable_ten_digits_and_bound_to_the_key() {
        let k1: Vec<u8> = (0u8..32).collect();
        let k2: Vec<u8> = (1u8..33).collect();
        let n1 = burner_number(k1.clone()).unwrap();
        assert_eq!(n1.len(), 10);
        assert!(n1.bytes().all(|b| b.is_ascii_digit()));
        assert_eq!(n1, burner_number(k1.clone()).unwrap());
        assert_ne!(n1, burner_number(k2.clone()).unwrap());
        assert!(burner_number_matches(n1.clone(), k1));
        assert!(!burner_number_matches(n1, k2));
        assert!(burner_number(vec![0; 31]).is_err());
    }

    #[test]
    fn format_and_parse_round_trip() {
        let f = format_burner_number("7334829173".into());
        assert_eq!(f, "ÆN 733 482 9173");
        assert_eq!(parse_burner_number(f).unwrap(), "7334829173");
        assert_eq!(parse_burner_number(" 733-482-9173 ".into()).unwrap(), "7334829173");
        assert!(parse_burner_number("733 482 917".into()).is_err());
        assert!(parse_burner_number("ÆN 733 482 917x".into()).is_err());
    }

    #[test]
    fn burn_codes_verify_and_tolerate_typos_in_lookalikes() {
        let code = generate_burn_code();
        assert_eq!(code.replace('-', "").len(), 26);
        let h = burn_code_hash(code.clone()).unwrap();
        assert!(verify_burn_code(code.clone(), h.clone()));
        assert!(verify_burn_code(code.to_lowercase().replace('-', " "), h.clone()));
        let other = generate_burn_code();
        assert!(!verify_burn_code(other, h.clone()));
        assert!(!verify_burn_code("hello".into(), h));
    }

    #[test]
    fn crockford_round_trips() {
        for n in [1usize, 5, 16, 31] {
            let raw: Vec<u8> = (0..n as u8).map(|i| i.wrapping_mul(37)).collect();
            let enc = encode_crockford(&raw);
            let mut dec = decode_crockford(&enc).unwrap();
            dec.truncate(n);
            assert_eq!(dec, raw);
        }
    }
}
