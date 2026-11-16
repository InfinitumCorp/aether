//! ÆTHER Talk™ messenger features (from FabriC++): message ids, replies, forwards, edits, deletes,
//! reactions, read receipts, typing, disappearing timers, groups and file transfer (photos, files,
//! voice messages), all carried end-to-end encrypted inside a Talk session as `MessageKind::Social`.
//!
//! Wire body: `AETHER-SOCIAL/1\n` followed by one line of `|`-separated, escaped fields in a fixed
//! order. Groups are fanned out pairwise: the sender encrypts the same event to every member over
//! that member's own session, so the mesh never sees group membership in the clear.

use crate::{AetherError, Precedence};

const HEADER: &str = "AETHER-SOCIAL/1";
/// Raw bytes per file chunk; base64 keeps one chunk well inside a Noise frame.
pub const SOCIAL_CHUNK_BYTES: u32 = 32 * 1024;
/// Largest file (photo, document, voice message) one transfer may carry.
pub const SOCIAL_MAX_FILE_BYTES: u64 = 25 * 1024 * 1024;
const MAX_TEXT_CHARS: usize = 8000;
const MAX_MEMBERS: usize = 64;
/// Disappearing-message timers a chat may use, in seconds (0 = off).
pub const SOCIAL_TIMERS: [u32; 7] = [0, 30, 300, 3600, 28_800, 86_400, 604_800];

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SocialKind {
    /// A message: `id`, `text` (may be empty when `target` names an attached file), `reply_to`,
    /// `forwarded`, `expires_s`, `group_id`.
    Message,
    /// Edit of the sender's own message `target`; `text` is the new text.
    Edit,
    /// Delete for everyone of the sender's own message `target`.
    Delete,
    /// Reaction `text` (an emoji) on message `target`; `flag` = true removes it.
    React,
    /// Everything up to and including message `target` has been read.
    Read,
    /// Typing indicator; `flag` = true while typing.
    Typing,
    /// Group create or update: `group_id`, name in `text`, `members` (identity keys, hex).
    Group,
    /// The sender leaves group `group_id`.
    Leave,
    /// Offer of file `id`: name in `text`, `mime`, `size`, `sha256`, `total` chunks.
    FileOffer,
    /// Chunk `index` of file `target`; base64 data in `text`.
    FileChunk,
    /// Disappearing-message timer for the chat (or `group_id`) set to `expires_s`.
    Timer,
    /// Pin message `target` at the top of the chat (or `group_id`); `flag` = true unpins it.
    Pin,
    /// Wipe the whole chat (or `group_id`) on every device: messages, files and pins.
    Wipe,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SocialEvent {
    pub kind: SocialKind,
    pub id: String,
    pub target: String,
    pub group_id: String,
    pub text: String,
    pub reply_to: String,
    pub forwarded: bool,
    pub flag: bool,
    pub expires_s: u32,
    pub members: Vec<String>,
    pub mime: String,
    pub size: u64,
    pub sha256: String,
    pub index: u32,
    pub total: u32,
    /// Message precedence: Routine, Priority, Immediate or Flash.
    pub precedence: Precedence,
    /// Group admins (identity keys, hex). They change the group, pin, wipe and, in a channel, post.
    pub admins: Vec<String>,
    /// Unit channel: a broadcast group where only admins post.
    pub channel: bool,
}

impl SocialEvent {
    fn blank(kind: SocialKind) -> Self {
        Self {
            kind,
            id: String::new(),
            target: String::new(),
            group_id: String::new(),
            text: String::new(),
            reply_to: String::new(),
            forwarded: false,
            flag: false,
            expires_s: 0,
            members: Vec::new(),
            mime: String::new(),
            size: 0,
            sha256: String::new(),
            index: 0,
            total: 0,
            precedence: Precedence::Routine,
            admins: Vec::new(),
            channel: false,
        }
    }
}

fn bad(reason: &str) -> AetherError {
    AetherError::malformed(reason)
}

fn is_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn is_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|b| b.is_ascii_hexdigit())
}

fn is_base64(s: &str) -> bool {
    !s.is_empty()
        && s.len() % 4 == 0
        && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'=')
}

fn need_id(s: &str, what: &str) -> Result<(), AetherError> {
    if is_id(s) {
        Ok(())
    } else {
        Err(bad(&format!("{what} is missing or invalid")))
    }
}

fn text_ok(s: &str, max: usize, what: &str) -> Result<(), AetherError> {
    if s.chars().count() > max {
        return Err(bad(&format!("{what} is too long")));
    }
    if s.chars().any(|c| c.is_control() && c != '\n' && c != '\t') {
        return Err(bad(&format!("{what} has control characters")));
    }
    Ok(())
}

/// Number of chunks a file of `size` bytes is split into.
#[uniffi::export]
pub fn social_chunk_count(size: u64) -> u32 {
    size.div_ceil(SOCIAL_CHUNK_BYTES as u64) as u32
}

/// Checks an event against the rules for its kind. Encoding and decoding both call this.
#[uniffi::export]
pub fn social_validate(event: SocialEvent) -> Result<(), AetherError> {
    let e = &event;
    if !e.group_id.is_empty() {
        need_id(&e.group_id, "group id")?;
    }
    match e.kind {
        SocialKind::Message => {
            need_id(&e.id, "message id")?;
            text_ok(&e.text, MAX_TEXT_CHARS, "message")?;
            if e.text.trim().is_empty() && e.target.is_empty() {
                return Err(bad("a message needs text or an attached file"));
            }
            if !e.target.is_empty() {
                need_id(&e.target, "attached file id")?;
            }
            if !e.reply_to.is_empty() {
                need_id(&e.reply_to, "reply target")?;
            }
            if !SOCIAL_TIMERS.contains(&e.expires_s) {
                return Err(bad("unsupported disappearing timer"));
            }
        }
        SocialKind::Edit => {
            need_id(&e.target, "edited message")?;
            text_ok(&e.text, MAX_TEXT_CHARS, "edit")?;
            if e.text.trim().is_empty() {
                return Err(bad("an edit needs text"));
            }
        }
        SocialKind::Delete | SocialKind::Read => need_id(&e.target, "target message")?,
        SocialKind::React => {
            need_id(&e.target, "reacted message")?;
            let n = e.text.chars().count();
            if n == 0 || n > 8 || e.text.chars().any(|c| c.is_alphanumeric() || c.is_whitespace() || c.is_control()) {
                return Err(bad("a reaction is one emoji"));
            }
        }
        SocialKind::Typing => {}
        SocialKind::Group => {
            need_id(&e.group_id, "group id")?;
            text_ok(&e.text, 64, "group name")?;
            if e.text.trim().is_empty() || e.text.contains('\n') {
                return Err(bad("a group needs a one-line name"));
            }
            if e.members.len() < 2 || e.members.len() > MAX_MEMBERS {
                return Err(bad("a group has 2 to 64 members"));
            }
            let mut seen = std::collections::HashSet::new();
            for m in &e.members {
                if !is_hex(m, 64) || !seen.insert(m.to_ascii_lowercase()) {
                    return Err(bad("group members must be distinct identity keys"));
                }
            }
            if e.admins.is_empty() {
                return Err(bad("a group needs at least one admin"));
            }
            let mut admins = std::collections::HashSet::new();
            for a in &e.admins {
                let a = a.to_ascii_lowercase();
                if !seen.contains(&a) || !admins.insert(a) {
                    return Err(bad("group admins must be distinct members"));
                }
            }
        }
        SocialKind::Leave => need_id(&e.group_id, "group id")?,
        SocialKind::FileOffer => {
            need_id(&e.id, "file id")?;
            text_ok(&e.text, 200, "file name")?;
            if e.text.trim().is_empty() || e.text.contains(['/', '\\', '\n']) {
                return Err(bad("a file needs a plain name"));
            }
            if e.mime.is_empty() || e.mime.len() > 100 || !e.mime.contains('/') || e.mime.contains(['|', '\n']) {
                return Err(bad("file type is invalid"));
            }
            if e.size == 0 || e.size > SOCIAL_MAX_FILE_BYTES {
                return Err(bad("files are 1 byte to 25 MB"));
            }
            if !is_hex(&e.sha256, 64) {
                return Err(bad("file digest is invalid"));
            }
            if e.total != social_chunk_count(e.size) {
                return Err(bad("chunk count does not match the size"));
            }
        }
        SocialKind::FileChunk => {
            need_id(&e.target, "file id")?;
            if e.total == 0 || e.index >= e.total {
                return Err(bad("chunk index is out of range"));
            }
            let max_b64 = (SOCIAL_CHUNK_BYTES as usize).div_ceil(3) * 4;
            if !is_base64(&e.text) || e.text.len() > max_b64 {
                return Err(bad("chunk data is invalid"));
            }
        }
        SocialKind::Timer => {
            if !SOCIAL_TIMERS.contains(&e.expires_s) {
                return Err(bad("unsupported disappearing timer"));
            }
        }
        SocialKind::Pin => need_id(&e.target, "pinned message")?,
        SocialKind::Wipe => {}
    }
    if e.kind != SocialKind::Group && (e.channel || !e.admins.is_empty()) {
        return Err(bad("only a group event carries admins or a channel flag"));
    }
    Ok(())
}

fn kind_code(k: SocialKind) -> &'static str {
    match k {
        SocialKind::Message => "msg",
        SocialKind::Edit => "edit",
        SocialKind::Delete => "del",
        SocialKind::React => "react",
        SocialKind::Read => "read",
        SocialKind::Typing => "typing",
        SocialKind::Group => "group",
        SocialKind::Leave => "leave",
        SocialKind::FileOffer => "file",
        SocialKind::FileChunk => "chunk",
        SocialKind::Timer => "timer",
        SocialKind::Pin => "pin",
        SocialKind::Wipe => "wipe",
    }
}

fn kind_from(code: &str) -> Option<SocialKind> {
    Some(match code {
        "msg" => SocialKind::Message,
        "edit" => SocialKind::Edit,
        "del" => SocialKind::Delete,
        "react" => SocialKind::React,
        "read" => SocialKind::Read,
        "typing" => SocialKind::Typing,
        "group" => SocialKind::Group,
        "leave" => SocialKind::Leave,
        "file" => SocialKind::FileOffer,
        "chunk" => SocialKind::FileChunk,
        "timer" => SocialKind::Timer,
        "pin" => SocialKind::Pin,
        "wipe" => SocialKind::Wipe,
        _ => return None,
    })
}

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => o.push_str("\\\\"),
            '|' => o.push_str("\\p"),
            '\n' => o.push_str("\\n"),
            '\t' => o.push_str("\\t"),
            ',' => o.push_str("\\c"),
            _ => o.push(c),
        }
    }
    o
}

fn unesc(s: &str) -> Result<String, AetherError> {
    let mut o = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            o.push(c);
            continue;
        }
        o.push(match it.next() {
            Some('\\') => '\\',
            Some('p') => '|',
            Some('n') => '\n',
            Some('t') => '\t',
            Some('c') => ',',
            _ => return Err(bad("bad escape in social message")),
        });
    }
    Ok(o)
}

/// Fields in the first release of the format (no precedence, admins or channel); still accepted.
const FIELDS_V1: usize = 15;
const FIELDS: usize = 18;

fn precedence_code(p: Precedence) -> &'static str {
    match p {
        Precedence::Routine => "R",
        Precedence::Priority => "P",
        Precedence::Immediate => "O",
        Precedence::Flash => "Z",
    }
}

fn precedence_from(s: &str) -> Option<Precedence> {
    Some(match s {
        "R" => Precedence::Routine,
        "P" => Precedence::Priority,
        "O" => Precedence::Immediate,
        "Z" => Precedence::Flash,
        _ => return None,
    })
}

#[uniffi::export]
pub fn encode_social(event: SocialEvent) -> Result<String, AetherError> {
    social_validate(event.clone())?;
    let e = event;
    let fields = [
        kind_code(e.kind).to_owned(),
        esc(&e.id),
        esc(&e.target),
        esc(&e.group_id),
        esc(&e.text),
        esc(&e.reply_to),
        (e.forwarded as u8).to_string(),
        (e.flag as u8).to_string(),
        e.expires_s.to_string(),
        e.members.iter().map(|m| esc(&m.to_ascii_lowercase())).collect::<Vec<_>>().join(","),
        esc(&e.mime),
        e.size.to_string(),
        esc(&e.sha256.to_ascii_lowercase()),
        e.index.to_string(),
        e.total.to_string(),
        precedence_code(e.precedence).to_owned(),
        e.admins.iter().map(|m| esc(&m.to_ascii_lowercase())).collect::<Vec<_>>().join(","),
        (e.channel as u8).to_string(),
    ];
    let body = format!("{HEADER}\n{}", fields.join("|"));
    if body.len() > crate::MAX_BODY_BYTES {
        return Err(bad("social message too large"));
    }
    Ok(body)
}

#[uniffi::export]
pub fn decode_social(body: String) -> Result<SocialEvent, AetherError> {
    let rest = body.strip_prefix(HEADER).and_then(|r| r.strip_prefix('\n')).ok_or_else(|| bad("not a social message"))?;
    if rest.contains('\n') {
        return Err(bad("social message has extra lines"));
    }
    let f: Vec<&str> = rest.split('|').collect();
    if f.len() != FIELDS && f.len() != FIELDS_V1 {
        return Err(bad("social message has the wrong number of fields"));
    }
    let flag = |s: &str| match s {
        "0" => Ok(false),
        "1" => Ok(true),
        _ => Err(bad("bad flag")),
    };
    let num = |s: &str| s.parse::<u64>().map_err(|_| bad("bad number"));
    let mut e = SocialEvent::blank(kind_from(f[0]).ok_or_else(|| bad("unknown social kind"))?);
    e.id = unesc(f[1])?;
    e.target = unesc(f[2])?;
    e.group_id = unesc(f[3])?;
    e.text = unesc(f[4])?;
    e.reply_to = unesc(f[5])?;
    e.forwarded = flag(f[6])?;
    e.flag = flag(f[7])?;
    e.expires_s = u32::try_from(num(f[8])?).map_err(|_| bad("bad timer"))?;
    e.members = if f[9].is_empty() { Vec::new() } else { f[9].split(',').map(unesc).collect::<Result<_, _>>()? };
    e.mime = unesc(f[10])?;
    e.size = num(f[11])?;
    e.sha256 = unesc(f[12])?;
    e.index = u32::try_from(num(f[13])?).map_err(|_| bad("bad index"))?;
    e.total = u32::try_from(num(f[14])?).map_err(|_| bad("bad total"))?;
    if f.len() == FIELDS {
        e.precedence = precedence_from(f[15]).ok_or_else(|| bad("bad precedence"))?;
        e.admins = if f[16].is_empty() { Vec::new() } else { f[16].split(',').map(unesc).collect::<Result<_, _>>()? };
        e.channel = flag(f[17])?;
    } else if e.kind == SocialKind::Group {
        // First-release group events carried no roles: every member counts as an admin.
        e.admins = e.members.clone();
    }
    social_validate(e.clone())?;
    Ok(e)
}

/// A blank event of `kind`, for clients to fill in.
#[uniffi::export]
pub fn social_event(kind: SocialKind) -> SocialEvent {
    SocialEvent::blank(kind)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(text: &str) -> SocialEvent {
        SocialEvent { id: "m-1".into(), text: text.into(), ..SocialEvent::blank(SocialKind::Message) }
    }

    fn rt(e: SocialEvent) -> SocialEvent {
        decode_social(encode_social(e).unwrap()).unwrap()
    }

    #[test]
    fn message_round_trip_with_escapes() {
        let mut e = msg("line | one\nline \\ two, three\tend");
        e.reply_to = "m-0".into();
        e.forwarded = true;
        e.expires_s = 3600;
        e.group_id = "g-alpha".into();
        assert_eq!(rt(e.clone()), e);
    }

    #[test]
    fn message_rules() {
        assert!(encode_social(msg("")).is_err());
        assert!(encode_social(msg(&"x".repeat(MAX_TEXT_CHARS + 1))).is_err());
        assert!(encode_social(SocialEvent { id: "bad id!".into(), ..msg("hi") }).is_err());
        assert!(encode_social(SocialEvent { expires_s: 42, ..msg("hi") }).is_err());
        assert!(encode_social(msg("bell\u{7}")).is_err());
        // Attachment with no caption is fine.
        assert!(encode_social(SocialEvent { target: "f-1".into(), ..msg("") }).is_ok());
    }

    #[test]
    fn actions() {
        let edit = SocialEvent { target: "m-1".into(), text: "fixed".into(), ..SocialEvent::blank(SocialKind::Edit) };
        assert_eq!(rt(edit.clone()), edit);
        let del = SocialEvent { target: "m-1".into(), ..SocialEvent::blank(SocialKind::Delete) };
        assert_eq!(rt(del.clone()), del);
        let react = SocialEvent { target: "m-1".into(), text: "👍".into(), ..SocialEvent::blank(SocialKind::React) };
        assert_eq!(rt(react.clone()), react);
        assert!(encode_social(SocialEvent { text: "ok".into(), ..react.clone() }).is_err());
        let read = SocialEvent { target: "m-9".into(), ..SocialEvent::blank(SocialKind::Read) };
        assert_eq!(rt(read.clone()), read);
        let typing = SocialEvent { flag: true, ..SocialEvent::blank(SocialKind::Typing) };
        assert_eq!(rt(typing.clone()), typing);
        assert!(encode_social(SocialEvent::blank(SocialKind::Edit)).is_err());
    }

    #[test]
    fn groups() {
        let a = "a".repeat(64);
        let b = "B".repeat(64);
        let g = SocialEvent {
            group_id: "g-1".into(),
            text: "BRAVO | net".into(),
            members: vec![a.clone(), b.clone()],
            admins: vec![a.clone()],
            channel: true,
            ..SocialEvent::blank(SocialKind::Group)
        };
        let back = rt(g.clone());
        assert_eq!(back.members, vec![a.clone(), b.to_ascii_lowercase()]);
        assert_eq!(back.admins, vec![a.clone()]);
        assert!(back.channel);
        assert!(encode_social(SocialEvent { admins: vec![], ..g.clone() }).is_err());
        assert!(encode_social(SocialEvent { admins: vec!["c".repeat(64)], ..g.clone() }).is_err());
        assert!(encode_social(SocialEvent { admins: vec![a.clone(), a.clone()], ..g.clone() }).is_err());
        assert_eq!(back.text, "BRAVO | net");
        assert!(encode_social(SocialEvent { members: vec![a.clone()], ..g.clone() }).is_err());
        assert!(encode_social(SocialEvent { members: vec![a.clone(), a.to_uppercase()], ..g.clone() }).is_err());
        assert!(encode_social(SocialEvent { members: vec![a, "xyz".into()], ..g }).is_err());
        let leave = SocialEvent { group_id: "g-1".into(), ..SocialEvent::blank(SocialKind::Leave) };
        assert_eq!(rt(leave.clone()), leave);
    }

    #[test]
    fn files() {
        let size = 100_000u64;
        let offer = SocialEvent {
            id: "f-1".into(),
            text: "photo.jpg".into(),
            mime: "image/jpeg".into(),
            size,
            sha256: "ab".repeat(32),
            total: social_chunk_count(size),
            ..SocialEvent::blank(SocialKind::FileOffer)
        };
        assert_eq!(offer.total, 4);
        assert_eq!(rt(offer.clone()), offer);
        assert!(encode_social(SocialEvent { total: 3, ..offer.clone() }).is_err());
        assert!(encode_social(SocialEvent { size: SOCIAL_MAX_FILE_BYTES + 1, total: social_chunk_count(SOCIAL_MAX_FILE_BYTES + 1), ..offer.clone() }).is_err());
        assert!(encode_social(SocialEvent { text: "../etc/passwd".into(), ..offer }).is_err());

        let full = "A".repeat((SOCIAL_CHUNK_BYTES as usize).div_ceil(3) * 4);
        let chunk = SocialEvent { target: "f-1".into(), text: full.clone(), index: 3, total: 4, ..SocialEvent::blank(SocialKind::FileChunk) };
        let body = encode_social(chunk.clone()).unwrap();
        assert!(body.len() <= crate::MAX_BODY_BYTES);
        assert_eq!(decode_social(body).unwrap(), chunk);
        assert!(encode_social(SocialEvent { index: 4, ..chunk.clone() }).is_err());
        assert!(encode_social(SocialEvent { text: format!("{full}AAAA"), ..chunk.clone() }).is_err());
        assert!(encode_social(SocialEvent { text: "not base64!".into(), ..chunk }).is_err());
    }

    #[test]
    fn military() {
        let flash = SocialEvent { precedence: Precedence::Flash, ..msg("contact front") };
        assert_eq!(rt(flash.clone()), flash);
        let pin = SocialEvent { target: "m-1".into(), group_id: "g-1".into(), ..SocialEvent::blank(SocialKind::Pin) };
        assert_eq!(rt(pin.clone()), pin);
        assert!(encode_social(SocialEvent::blank(SocialKind::Pin)).is_err());
        let wipe = SocialEvent { group_id: "g-1".into(), ..SocialEvent::blank(SocialKind::Wipe) };
        assert_eq!(rt(wipe.clone()), wipe);
        assert!(encode_social(SocialEvent { channel: true, ..msg("x") }).is_err());
        let body = encode_social(flash).unwrap();
        assert!(decode_social(body.replace("|Z|", "|Q|")).is_err());
    }

    #[test]
    fn first_release_bodies_still_decode() {
        let v1 = "AETHER-SOCIAL/1\nmsg|m-1|||hi||0|0|0|||0||0|0";
        let e = decode_social(v1.into()).unwrap();
        assert_eq!(e.text, "hi");
        assert_eq!(e.precedence, Precedence::Routine);
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        let g = format!("AETHER-SOCIAL/1\ngroup|||g-1|Net||0|0|0|{a},{b}||0||0|0");
        assert_eq!(decode_social(g).unwrap().admins, vec![a, b]);
    }

    #[test]
    fn timer_and_garbage() {
        let t = SocialEvent { expires_s: 86_400, ..SocialEvent::blank(SocialKind::Timer) };
        assert_eq!(rt(t.clone()), t);
        assert!(encode_social(SocialEvent { expires_s: 7, ..t }).is_err());
        assert!(decode_social("AETHER-SOCIAL/1\nmsg|x".into()).is_err());
        assert!(decode_social("hello".into()).is_err());
        assert!(decode_social("AETHER-SOCIAL/1\nnope|||||||0|0|0||||0|0|0".into()).is_err());
        let ok = encode_social(msg("hi")).unwrap();
        assert!(decode_social(format!("{ok}\nextra")).is_err());
        assert!(decode_social(ok.replace("|0|0|0|", "|2|0|0|")).is_err());
    }
}
