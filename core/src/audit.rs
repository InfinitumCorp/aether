//! Hash-chained audit log shared by ÆTHER eidetic MIL™ and ÆTHER Field™.
//!
//! Every entry commits to the previous entry's hash, so removing, reordering or editing any
//! entry breaks [`audit_verify`]. Entries are stored in an ÆTHER Vault™ (encrypted and
//! authenticated at rest). An entry carries identifiers, purposes, outcomes and scores only:
//! [`audit_append`] refuses details that look like biometric templates or raw media.

use crate::AetherError;
use sha2::{Digest, Sha256};

const DOMAIN: &[u8] = b"AETHER-AUDIT/1";
const MAX_FIELD_CHARS: usize = 256;
const MAX_DETAIL_CHARS: usize = 2048;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AuditEntry {
    /// 0 for the first entry, then +1.
    pub seq: u64,
    pub at_ms: i64,
    /// Who acted: operator call sign or identity fingerprint.
    pub actor: String,
    /// What happened, e.g. `eidetic.search.authorized`.
    pub action: String,
    /// What it happened to, e.g. a search or mission id.
    pub subject: String,
    /// Short structured detail (`key=value; key=value`).
    pub detail: String,
    /// Hash of the previous entry (32 zero bytes for the first).
    pub prev_hash: Vec<u8>,
    pub hash: Vec<u8>,
}

fn field(s: &str, max: usize, what: &str) -> Result<String, AetherError> {
    let t = s.trim();
    if t.chars().count() > max || t.chars().any(|c| c.is_control()) {
        return Err(AetherError::malformed(&format!("audit {what} must be at most {max} characters on one line")));
    }
    Ok(t.to_owned())
}

/// Rejects details that look like a biometric template or embedded media: long runs of
/// numbers (vectors), base64 blobs or data URIs. Templates belong in the sealed store only.
pub(crate) fn looks_biometric(detail: &str) -> bool {
    let lower = detail.to_ascii_lowercase();
    if lower.contains("data:image") || lower.contains("embedding") || lower.contains("template=") || lower.contains("vector=") {
        return true;
    }
    let numbers = detail.split(|c: char| c == ',' || c == ' ' || c == ';' || c == '[' || c == ']').filter(|t| t.parse::<f64>().is_ok()).count();
    if numbers > 16 {
        return true;
    }
    detail.split_whitespace().any(|w| w.len() > 120 && w.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=' | '-' | '_')))
}

fn entry_hash(e: &AuditEntry) -> Vec<u8> {
    let mut h = Sha256::new();
    h.update(DOMAIN);
    h.update(e.seq.to_be_bytes());
    h.update(e.at_ms.to_be_bytes());
    for f in [&e.actor, &e.action, &e.subject, &e.detail] {
        h.update((f.len() as u32).to_be_bytes());
        h.update(f.as_bytes());
    }
    h.update(&e.prev_hash);
    h.finalize().to_vec()
}

/// Creates the entry that follows `prev` (or the first entry when `prev` is `None`).
#[uniffi::export]
pub fn audit_append(
    prev: Option<AuditEntry>,
    at_ms: i64,
    actor: String,
    action: String,
    subject: String,
    detail: String,
) -> Result<AuditEntry, AetherError> {
    let actor = field(&actor, MAX_FIELD_CHARS, "actor")?;
    let action = field(&action, MAX_FIELD_CHARS, "action")?;
    let subject = field(&subject, MAX_FIELD_CHARS, "subject")?;
    let detail = field(&detail, MAX_DETAIL_CHARS, "detail")?;
    if actor.is_empty() || action.is_empty() {
        return Err(AetherError::malformed("audit entries need an actor and an action"));
    }
    if looks_biometric(&detail) {
        return Err(AetherError::malformed("biometric data may not be written to the audit log"));
    }
    let (seq, prev_hash) = match &prev {
        None => (0, vec![0u8; 32]),
        Some(p) => {
            if entry_hash(p) != p.hash {
                return Err(AetherError::state("previous audit entry does not match its hash"));
            }
            if at_ms < p.at_ms {
                return Err(AetherError::state("audit time went backwards"));
            }
            (p.seq + 1, p.hash.clone())
        }
    };
    let mut e = AuditEntry { seq, at_ms, actor, action, subject, detail, prev_hash, hash: vec![] };
    e.hash = entry_hash(&e);
    Ok(e)
}

/// Verifies a whole chain from its first entry. Returns the head hash (hex) on success; the
/// error names the first entry that fails.
#[uniffi::export]
pub fn audit_verify(entries: Vec<AuditEntry>) -> Result<String, AetherError> {
    let mut prev_hash = vec![0u8; 32];
    let mut prev_at = i64::MIN;
    for (i, e) in entries.iter().enumerate() {
        if e.seq != i as u64 || e.prev_hash != prev_hash || entry_hash(e) != e.hash || e.at_ms < prev_at {
            return Err(AetherError::state(&format!("audit chain broken at entry {i}")));
        }
        prev_hash = e.hash.clone();
        prev_at = e.at_ms;
    }
    Ok(prev_hash.iter().map(|b| format!("{b:02x}")).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn chain(n: usize) -> Vec<AuditEntry> {
        let mut v: Vec<AuditEntry> = Vec::new();
        for i in 0..n {
            let e = audit_append(v.last().cloned(), 1000 + i as i64, "BRAVO-2".into(), "test.step".into(), format!("s{i}"), format!("n={i}")).unwrap();
            v.push(e);
        }
        v
    }

    #[test]
    fn verifies_and_detects_tampering() {
        let c = chain(5);
        assert_eq!(audit_verify(c.clone()).unwrap().len(), 64);
        assert!(audit_verify(vec![]).is_ok());
        let mut edited = c.clone();
        edited[2].detail = "n=99".into();
        assert!(audit_verify(edited).is_err());
        let mut dropped = c.clone();
        dropped.remove(1);
        assert!(audit_verify(dropped).is_err());
        let mut swapped = c.clone();
        swapped.swap(1, 2);
        assert!(audit_verify(swapped).is_err());
        let mut forged = c[..3].to_vec();
        forged.truncate(2);
        assert!(audit_verify(forged).is_ok(), "a prefix is still a valid chain");
    }

    #[test]
    fn refuses_bad_entries() {
        let c = chain(1);
        assert!(audit_append(None, 0, "".into(), "a".into(), "".into(), "".into()).is_err());
        assert!(audit_append(None, 0, "x".into(), "a\nb".into(), "".into(), "".into()).is_err());
        assert!(audit_append(c.last().cloned(), 10, "x".into(), "a".into(), "".into(), "".into()).is_err());
        let vector = (0..128).map(|i| format!("0.{i}")).collect::<Vec<_>>().join(",");
        assert!(audit_append(None, 0, "x".into(), "a".into(), "".into(), vector).is_err());
        assert!(audit_append(None, 0, "x".into(), "a".into(), "".into(), "photo=data:image/png;base64,AAAA".into()).is_err());
        assert!(audit_append(None, 0, "x".into(), "a".into(), "".into(), "score=0.93; margin=0.21".into()).is_ok());
        let mut tampered = c[0].clone();
        tampered.actor = "ALPHA".into();
        assert!(audit_append(Some(tampered), 2000, "x".into(), "a".into(), "".into(), "".into()).is_err());
    }
}
