use sha2::{Digest, Sha256};

use crate::AetherError;

const FP_DOMAIN: &[u8] = b"AETHER-FINGERPRINT-v1";
const SN_DOMAIN: &[u8] = b"AETHER-SAFETY-NUMBER-v1";
const GROUPS: usize = 12;

/// Renders 60 decimal digits (12 groups of 5) from a digest, Signal-style:
/// each group is 5 digest bytes taken as a big-endian integer mod 100000.
fn digits(digest: &[u8]) -> String {
    debug_assert!(digest.len() >= GROUPS * 5);
    (0..GROUPS)
        .map(|g| {
            let chunk = &digest[g * 5..g * 5 + 5];
            let n = chunk.iter().fold(0u64, |acc, b| (acc << 8) | u64::from(*b));
            format!("{:05}", n % 100_000)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Iterated hash so that brute-forcing a colliding fingerprint is costly.
fn stretched(domain: &[u8], input: &[u8]) -> Vec<u8> {
    let mut h = Sha256::new().chain_update(domain).chain_update(input).finalize();
    for _ in 0..1024 {
        h = Sha256::new().chain_update(h).chain_update(input).finalize();
    }
    // 60 bytes needed; extend with a second, domain-separated block.
    let tail = Sha256::new().chain_update(h).chain_update(b"tail").finalize();
    [h.as_slice(), tail.as_slice()].concat()
}

pub(crate) fn check_key(key: &[u8]) -> Result<(), AetherError> {
    if key.len() == 32 {
        Ok(())
    } else {
        Err(AetherError::InvalidKey)
    }
}

/// Fingerprint of a single 32-byte public key.
#[uniffi::export]
pub fn fingerprint(public_key: Vec<u8>) -> Result<String, AetherError> {
    check_key(&public_key)?;
    Ok(digits(&stretched(FP_DOMAIN, &public_key)))
}

/// Safety number for a pair of public keys. Symmetric: both peers compute the
/// same value regardless of who is "local", so users can compare it out loud.
#[uniffi::export]
pub fn safety_number(key_a: Vec<u8>, key_b: Vec<u8>) -> Result<String, AetherError> {
    check_key(&key_a)?;
    check_key(&key_b)?;
    let (lo, hi) = if key_a <= key_b { (key_a, key_b) } else { (key_b, key_a) };
    Ok(digits(&stretched(SN_DOMAIN, &[lo, hi].concat())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprint_shape() {
        let fp = fingerprint(vec![7u8; 32]).unwrap();
        assert_eq!(fp.len(), 12 * 5 + 11);
        assert!(fp.split(' ').all(|g| g.len() == 5 && g.chars().all(|c| c.is_ascii_digit())));
    }

    #[test]
    fn safety_number_is_symmetric_and_key_dependent() {
        let a = vec![1u8; 32];
        let b = vec![2u8; 32];
        let c = vec![3u8; 32];
        assert_eq!(safety_number(a.clone(), b.clone()).unwrap(), safety_number(b.clone(), a.clone()).unwrap());
        assert_ne!(safety_number(a.clone(), b).unwrap(), safety_number(a, c).unwrap());
    }

    #[test]
    fn rejects_bad_lengths() {
        assert!(fingerprint(vec![0u8; 31]).is_err());
        assert!(safety_number(vec![0u8; 32], vec![0u8; 33]).is_err());
    }
}
