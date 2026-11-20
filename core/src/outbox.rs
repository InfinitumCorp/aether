//! Device-local queue of texts waiting for a live ÆTHER session.
//! The queue holds plaintext until the caller encrypts it with an existing session.
//! It is not a new cipher and not a relay.

use std::collections::{HashMap, VecDeque};

/// Maximum queued texts per peer. The oldest is dropped when this is exceeded.
pub const MAX_QUEUED: usize = 100;

#[derive(Debug, Default)]
pub struct Outbox {
    queues: HashMap<String, VecDeque<String>>,
}

impl Outbox {
    pub fn enqueue(&mut self, peer: &str, text: &str) -> Result<(), &'static str> {
        if peer.is_empty() || text.is_empty() || text.len() > crate::MAX_BODY_BYTES {
            return Err("outbox rejected");
        }
        let q = self.queues.entry(peer.to_owned()).or_default();
        if q.len() == MAX_QUEUED {
            q.pop_front();
        }
        q.push_back(text.to_owned());
        Ok(())
    }

    pub fn drain(&mut self, peer: &str) -> Vec<String> {
        self.queues.remove(peer).map(|q| q.into_iter().collect()).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drains_in_order_and_drops_the_oldest_past_the_cap() {
        let mut box_ = Outbox::default();
        box_.enqueue("peer", "one").unwrap();
        box_.enqueue("peer", "two").unwrap();
        assert_eq!(box_.drain("peer"), vec!["one".to_string(), "two".to_string()]);
        assert!(box_.drain("peer").is_empty());
        for i in 0..(MAX_QUEUED + 1) {
            box_.enqueue("peer", &format!("m{i}")).unwrap();
        }
        let got = box_.drain("peer");
        assert_eq!(got.len(), MAX_QUEUED);
        assert_eq!(got[0], "m1");
        assert_eq!(got[MAX_QUEUED - 1], format!("m{MAX_QUEUED}"));
    }

    #[test]
    fn rejects_empty_and_oversized_text() {
        let mut box_ = Outbox::default();
        assert!(box_.enqueue("", "x").is_err());
        assert!(box_.enqueue("peer", "").is_err());
        assert!(box_.enqueue("peer", &"x".repeat(crate::MAX_BODY_BYTES + 1)).is_err());
        assert_eq!(box_.enqueue("peer", "ok").unwrap(), ());
    }
}
