use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    sync::{Arc, Mutex},
};

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use rand_core::{OsRng, RngCore};
use zeroize::Zeroizing;

use crate::AetherError;

const MAGIC: &[u8; 8] = b"AETHERV1";
const ID_LEN: usize = 16;
const NONCE_LEN: usize = 24;
const TAG_LEN: usize = 16;
const CHECK_LEN: usize = NONCE_LEN + TAG_LEN;
const HEADER_LEN: usize = MAGIC.len() + ID_LEN + CHECK_LEN;
const MAX_RECORD: usize = 16 * 1024 * 1024;

/// Append-only encrypted record store.
///
/// File layout:
/// `MAGIC | vault_id[16] | check(nonce[24] | tag[16]) | record*`
/// where each `record = len:u32be | nonce[24] | ciphertext`.
///
/// Every record is XChaCha20-Poly1305 sealed with AAD `vault_id | index:u64be`,
/// so records cannot be swapped between vaults or reordered within one. The
/// `check` block authenticates the key on open. A torn final write (crash
/// mid-append) is truncated away on the next open.
#[derive(uniffi::Object)]
pub struct Vault {
    path: PathBuf,
    cipher: XChaCha20Poly1305,
    vault_id: [u8; ID_LEN],
    count: Mutex<u64>,
}

fn aad(vault_id: &[u8; ID_LEN], index: u64) -> [u8; ID_LEN + 8] {
    let mut out = [0u8; ID_LEN + 8];
    out[..ID_LEN].copy_from_slice(vault_id);
    out[ID_LEN..].copy_from_slice(&index.to_be_bytes());
    out
}

fn check_aad(vault_id: &[u8; ID_LEN]) -> Vec<u8> {
    [b"check".as_slice(), vault_id].concat()
}

fn nonce() -> [u8; NONCE_LEN] {
    let mut n = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut n);
    n
}

impl Vault {
    fn create(file: &mut File, cipher: &XChaCha20Poly1305) -> Result<[u8; ID_LEN], AetherError> {
        let mut vault_id = [0u8; ID_LEN];
        OsRng.fill_bytes(&mut vault_id);
        let n = nonce();
        let tag = cipher
            .encrypt(XNonce::from_slice(&n), Payload { msg: &[], aad: &check_aad(&vault_id) })
            .map_err(|_| AetherError::vault("seal check"))?;
        file.write_all(MAGIC).map_err(AetherError::vault)?;
        file.write_all(&vault_id).map_err(AetherError::vault)?;
        file.write_all(&n).map_err(AetherError::vault)?;
        file.write_all(&tag).map_err(AetherError::vault)?;
        file.sync_all().map_err(AetherError::vault)?;
        Ok(vault_id)
    }

    /// Walks every record, decrypting when `out` is given. Returns
    /// (record count, byte offset just past the last complete record).
    fn scan(&self, file: &mut File, mut out: Option<&mut Vec<Vec<u8>>>) -> Result<(u64, u64), AetherError> {
        let len = file.metadata().map_err(AetherError::vault)?.len();
        let mut pos = HEADER_LEN as u64;
        let mut index = 0u64;
        file.seek(SeekFrom::Start(pos)).map_err(AetherError::vault)?;
        loop {
            if len - pos < 4 + NONCE_LEN as u64 + TAG_LEN as u64 {
                break;
            }
            let mut lenb = [0u8; 4];
            file.read_exact(&mut lenb).map_err(AetherError::vault)?;
            let ct_len = u32::from_be_bytes(lenb) as u64;
            if ct_len < TAG_LEN as u64 || ct_len as usize > MAX_RECORD + TAG_LEN {
                return Err(AetherError::vault("corrupt record length"));
            }
            let end = pos + 4 + NONCE_LEN as u64 + ct_len;
            if end > len {
                break; // torn tail
            }
            let mut n = [0u8; NONCE_LEN];
            file.read_exact(&mut n).map_err(AetherError::vault)?;
            let mut ct = vec![0u8; ct_len as usize];
            file.read_exact(&mut ct).map_err(AetherError::vault)?;
            let a = aad(&self.vault_id, index);
            let pt = self
                .cipher
                .decrypt(XNonce::from_slice(&n), Payload { msg: &ct, aad: &a })
                .map_err(|_| AetherError::vault("record failed authentication"))?;
            if let Some(o) = out.as_deref_mut() {
                o.push(pt);
            }
            pos = end;
            index += 1;
        }
        Ok((index, pos))
    }
}

#[uniffi::export]
impl Vault {
    /// Opens (or creates) the vault at `path` with a 32-byte `key`.
    #[uniffi::constructor]
    pub fn open(path: String, key: Vec<u8>) -> Result<Arc<Self>, AetherError> {
        let key = Zeroizing::new(key);
        if key.len() != 32 {
            return Err(AetherError::InvalidKey);
        }
        let cipher = XChaCha20Poly1305::new_from_slice(&key).map_err(|_| AetherError::InvalidKey)?;
        let path = PathBuf::from(path);
        let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&path).map_err(AetherError::vault)?;
        let size = file.metadata().map_err(AetherError::vault)?.len();

        let vault_id = if size == 0 {
            Self::create(&mut file, &cipher)?
        } else {
            if size < HEADER_LEN as u64 {
                return Err(AetherError::vault("truncated header"));
            }
            let mut header = [0u8; HEADER_LEN];
            file.read_exact(&mut header).map_err(AetherError::vault)?;
            if &header[..8] != MAGIC {
                return Err(AetherError::vault("not an ÆTHER vault"));
            }
            let vault_id: [u8; ID_LEN] = header[8..8 + ID_LEN].try_into().expect("16 bytes");
            let n = &header[8 + ID_LEN..8 + ID_LEN + NONCE_LEN];
            let tag = &header[8 + ID_LEN + NONCE_LEN..];
            cipher
                .decrypt(XNonce::from_slice(n), Payload { msg: tag, aad: &check_aad(&vault_id) })
                .map_err(|_| AetherError::InvalidKey)?;
            vault_id
        };

        let vault = Self { path, cipher, vault_id, count: Mutex::new(0) };
        let (count, good_end) = vault.scan(&mut file, None)?;
        if good_end < file.metadata().map_err(AetherError::vault)?.len() {
            file.set_len(good_end).map_err(AetherError::vault)?;
            file.sync_all().map_err(AetherError::vault)?;
        }
        *vault.count.lock().map_err(|_| AetherError::state("poisoned"))? = count;
        Ok(Arc::new(vault))
    }

    /// Seals and appends one record; returns its index.
    pub fn append(&self, record: Vec<u8>) -> Result<u64, AetherError> {
        let record = Zeroizing::new(record);
        if record.len() > MAX_RECORD {
            return Err(AetherError::vault("record too large"));
        }
        let mut count = self.count.lock().map_err(|_| AetherError::state("poisoned"))?;
        let index = *count;
        let n = nonce();
        let ct = self
            .cipher
            .encrypt(XNonce::from_slice(&n), Payload { msg: &record, aad: &aad(&self.vault_id, index) })
            .map_err(|_| AetherError::vault("seal record"))?;
        let mut frame = Vec::with_capacity(4 + NONCE_LEN + ct.len());
        frame.extend_from_slice(&(ct.len() as u32).to_be_bytes());
        frame.extend_from_slice(&n);
        frame.extend_from_slice(&ct);
        let mut file = OpenOptions::new().append(true).open(&self.path).map_err(AetherError::vault)?;
        file.write_all(&frame).map_err(AetherError::vault)?;
        file.sync_data().map_err(AetherError::vault)?;
        *count += 1;
        Ok(index)
    }

    /// Decrypts and returns every record in order.
    pub fn read_all(&self) -> Result<Vec<Vec<u8>>, AetherError> {
        let _guard = self.count.lock().map_err(|_| AetherError::state("poisoned"))?;
        let mut file = File::open(&self.path).map_err(AetherError::vault)?;
        let mut out = Vec::new();
        self.scan(&mut file, Some(&mut out))?;
        Ok(out)
    }

    pub fn len(&self) -> u64 {
        self.count.lock().map(|c| *c).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn path(dir: &tempfile::TempDir, name: &str) -> String {
        dir.path().join(name).to_string_lossy().into_owned()
    }

    #[test]
    fn persists_across_reopen() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        let key = vec![9u8; 32];
        let v = Vault::open(p.clone(), key.clone()).unwrap();
        v.append(b"one".to_vec()).unwrap();
        v.append(b"two".to_vec()).unwrap();
        drop(v);
        let v = Vault::open(p, key).unwrap();
        assert_eq!(v.len(), 2);
        assert_eq!(v.read_all().unwrap(), vec![b"one".to_vec(), b"two".to_vec()]);
    }

    #[test]
    fn wrong_key_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        Vault::open(p.clone(), vec![1u8; 32]).unwrap().append(b"x".to_vec()).unwrap();
        assert!(matches!(Vault::open(p, vec![2u8; 32]), Err(AetherError::InvalidKey)));
    }

    #[test]
    fn plaintext_not_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        Vault::open(p.clone(), vec![1u8; 32]).unwrap().append(b"top secret payload".to_vec()).unwrap();
        let raw = std::fs::read(&p).unwrap();
        assert!(!raw.windows(6).any(|w| w == b"secret"));
    }

    #[test]
    fn tampering_is_detected() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        let key = vec![1u8; 32];
        Vault::open(p.clone(), key.clone()).unwrap().append(b"hello".to_vec()).unwrap();
        let mut raw = std::fs::read(&p).unwrap();
        let last = raw.len() - 1;
        raw[last] ^= 0xff;
        std::fs::write(&p, raw).unwrap();
        assert!(Vault::open(p, key).is_err());
    }

    #[test]
    fn records_cannot_be_reordered() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        let key = vec![1u8; 32];
        let v = Vault::open(p.clone(), key.clone()).unwrap();
        v.append(b"AAAA".to_vec()).unwrap();
        v.append(b"BBBB".to_vec()).unwrap();
        drop(v);
        let raw = std::fs::read(&p).unwrap();
        let rec = 4 + NONCE_LEN + 4 + TAG_LEN;
        let (head, recs) = raw.split_at(HEADER_LEN);
        let swapped = [head, &recs[rec..], &recs[..rec]].concat();
        std::fs::write(&p, swapped).unwrap();
        assert!(Vault::open(p, key).is_err());
    }

    #[test]
    fn torn_tail_is_recovered() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "v");
        let key = vec![1u8; 32];
        let v = Vault::open(p.clone(), key.clone()).unwrap();
        v.append(b"kept".to_vec()).unwrap();
        v.append(b"torn".to_vec()).unwrap();
        drop(v);
        let raw = std::fs::read(&p).unwrap();
        std::fs::write(&p, &raw[..raw.len() - 5]).unwrap();
        let v = Vault::open(p, key).unwrap();
        assert_eq!(v.read_all().unwrap(), vec![b"kept".to_vec()]);
        v.append(b"after".to_vec()).unwrap();
        assert_eq!(v.len(), 2);
    }

    #[test]
    fn bad_key_length() {
        let dir = tempfile::tempdir().unwrap();
        assert!(matches!(Vault::open(path(&dir, "v"), vec![0u8; 16]), Err(AetherError::InvalidKey)));
    }
}
