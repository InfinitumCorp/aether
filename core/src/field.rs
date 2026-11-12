//! ÆTHER Field™: field reporting and tasking, built from [HRVST]'s capture → refine → packet
//! pipeline and its mission lifecycle, for use inside a unit.
//!
//! * A **capture** is an operator's observation at a place. [`field_refine`] strips contact
//!   details (email addresses, phone numbers, links), scores quality and seals a **packet**
//!   with a SHA-256 proof. Captures at protected or private places (hospital, school, private
//!   residence, home interior) stay on the device unless the operator explicitly releases them.
//! * A **mission** is a tasking: post → claim → submit a packet → accept or reject. A rejected
//!   packet cannot be submitted to that mission again; the claimer can release; the poster can
//!   cancel an open mission, or one claimed more than 7 days ago with no submission.
//!   Missions may not task collection at protected places.
//!
//! Unlike HRVST there is no market, no credits and no outside buyer: packets and missions move
//! only between ÆTHER devices, end-to-end encrypted (`MessageKind::Field`).

use crate::AetherError;
use sha2::{Digest, Sha256};

const STALE_CLAIM_MS: i64 = 7 * 24 * 3600 * 1000;
const MAX_NOTE_CHARS: usize = 4000;
const MAX_LABEL_CHARS: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlaceClass {
    Field,
    Street,
    PublicBuilding,
    Retail,
    Military,
    Infrastructure,
    /// Protected: stays on the device unless released.
    Hospital,
    /// Protected: stays on the device unless released.
    School,
    /// Private: stays on the device unless released.
    PrivateResidence,
    /// Private: stays on the device unless released.
    HomeInterior,
}

impl PlaceClass {
    fn protected(self) -> bool {
        matches!(self, Self::Hospital | Self::School | Self::PrivateResidence | Self::HomeInterior)
    }
    fn code(self) -> &'static str {
        match self {
            Self::Field => "field",
            Self::Street => "street",
            Self::PublicBuilding => "public_building",
            Self::Retail => "retail",
            Self::Military => "military",
            Self::Infrastructure => "infrastructure",
            Self::Hospital => "hospital",
            Self::School => "school",
            Self::PrivateResidence => "private_residence",
            Self::HomeInterior => "home_interior",
        }
    }
    fn from_code(s: &str) -> Option<Self> {
        [
            Self::Field, Self::Street, Self::PublicBuilding, Self::Retail, Self::Military, Self::Infrastructure,
            Self::Hospital, Self::School, Self::PrivateResidence, Self::HomeInterior,
        ]
        .into_iter()
        .find(|p| p.code() == s)
    }
}

/// Whether a place class stays on the device unless the operator releases it.
#[uniffi::export]
pub fn place_is_protected(place: PlaceClass) -> bool {
    place.protected()
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Capture {
    pub id: String,
    pub owner: String,
    pub note: String,
    pub place_label: String,
    pub place_class: PlaceClass,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    pub at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Packet {
    pub id: String,
    pub capture_id: String,
    pub owner: String,
    /// The note after contact details were removed.
    pub observation: String,
    pub place_label: String,
    pub place_class: PlaceClass,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    pub at_ms: i64,
    /// 0–100: detail, location and timeliness.
    pub quality: u8,
    /// How many contact details were removed.
    pub redactions: u32,
    /// SHA-256 over the packet content (hex); any change breaks it.
    pub proof: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RefineOutcome {
    /// None when the capture stayed on the device (protected place, not released).
    pub packet: Option<Packet>,
    pub reason: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Precedence {
    Routine,
    Priority,
    Immediate,
    Flash,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MissionState {
    Open,
    Claimed,
    Submitted,
    Accepted,
    Cancelled,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Mission {
    pub id: String,
    pub title: String,
    pub brief: String,
    pub place_class: PlaceClass,
    pub precedence: Precedence,
    pub posted_by: String,
    pub posted_at_ms: i64,
    pub state: MissionState,
    pub claimed_by: Option<String>,
    pub claimed_at_ms: Option<i64>,
    pub packet_id: Option<String>,
    /// Packets the poster turned down; they cannot be submitted again.
    pub rejected_packets: Vec<String>,
}

fn bad(reason: &str) -> AetherError {
    AetherError::malformed(reason)
}

fn state(reason: &str) -> AetherError {
    AetherError::state(reason)
}

fn clean(s: &str, max: usize, what: &str, allow_newlines: bool) -> Result<String, AetherError> {
    let t = s.trim();
    if t.chars().count() > max || t.chars().any(|c| c.is_control() && !(allow_newlines && c == '\n')) {
        return Err(bad(&format!("{what} must be at most {max} characters")));
    }
    Ok(t.to_owned())
}

fn need(s: &str, what: &str) -> Result<String, AetherError> {
    let t = clean(s, MAX_LABEL_CHARS, what, false)?;
    if t.is_empty() {
        return Err(bad(&format!("{what} is required")));
    }
    Ok(t)
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Replaces email addresses, phone numbers and links with `[removed]`. Returns the count.
fn redact(note: &str) -> (String, u32) {
    let mut out = Vec::new();
    let mut n = 0;
    for word in note.split(' ') {
        let core = word.trim_matches(|c: char| matches!(c, ',' | '.' | ';' | ':' | '(' | ')' | '"' | '\''));
        let lower = core.to_ascii_lowercase();
        let is_email = core.contains('@') && core.split('@').count() == 2 && core.split('@').all(|p| !p.is_empty()) && core.contains('.');
        let is_link = lower.starts_with("http://") || lower.starts_with("https://") || lower.starts_with("www.");
        let digits = core.chars().filter(char::is_ascii_digit).count();
        let phoneish = core.chars().all(|c| c.is_ascii_digit() || matches!(c, '+' | '-' | '(' | ')' | '.'));
        let is_phone = phoneish && digits >= 7 && !core.contains('.') || (core.starts_with('+') && digits >= 7 && phoneish);
        if is_email || is_link || is_phone {
            n += 1;
            out.push(word.replace(core, "[removed]"));
        } else {
            out.push(word.to_owned());
        }
    }
    (out.join(" "), n)
}

fn packet_proof(p: &Packet) -> String {
    let mut h = Sha256::new();
    h.update(b"AETHER-FIELD-PACKET/1");
    for f in [&p.id, &p.capture_id, &p.owner, &p.observation, &p.place_label, p.place_class.code()] {
        h.update((f.len() as u32).to_be_bytes());
        h.update(f.as_bytes());
    }
    h.update(p.lat.unwrap_or(f64::NAN).to_bits().to_be_bytes());
    h.update(p.lon.unwrap_or(f64::NAN).to_bits().to_be_bytes());
    h.update(p.at_ms.to_be_bytes());
    h.update([p.quality]);
    h.update(p.redactions.to_be_bytes());
    hex(&h.finalize())
}

/// Checks a packet's proof (for packets received from a teammate).
#[uniffi::export]
pub fn field_verify_packet(packet: Packet) -> bool {
    packet_proof(&packet) == packet.proof
}

/// Refines a capture into a packet. `release` must be true for a protected place.
#[uniffi::export]
pub fn field_refine(capture: Capture, release: bool, now_ms: i64) -> Result<RefineOutcome, AetherError> {
    let id = need(&capture.id, "capture id")?;
    let owner = need(&capture.owner, "owner")?;
    let note = clean(&capture.note, MAX_NOTE_CHARS, "note", true)?;
    let label = clean(&capture.place_label, MAX_LABEL_CHARS, "place label", false)?;
    match (capture.lat, capture.lon) {
        (Some(la), Some(lo)) if la.is_finite() && lo.is_finite() && (-90.0..=90.0).contains(&la) && (-180.0..=180.0).contains(&lo) => {}
        (None, None) => {}
        _ => return Err(bad("capture position is invalid")),
    }
    if note.is_empty() {
        return Err(bad("a capture needs an observation"));
    }
    if capture.place_class.protected() && !release {
        return Ok(RefineOutcome {
            packet: None,
            reason: format!("Kept on this device: {} is a protected place. Release it explicitly to package it.", capture.place_class.code()),
        });
    }
    let (observation, redactions) = redact(&note);
    let words = observation.split_whitespace().count();
    let mut quality = (words.min(60) as f32 / 60.0 * 50.0) as u8;
    if capture.lat.is_some() {
        quality += 30;
    }
    if !label.is_empty() {
        quality += 10;
    }
    if (0..=3_600_000).contains(&(now_ms - capture.at_ms)) {
        quality += 10;
    }
    let mut h = Sha256::new();
    h.update(id.as_bytes());
    h.update(capture.at_ms.to_be_bytes());
    let mut p = Packet {
        id: format!("PKT-{}", &hex(&h.finalize())[..12]),
        capture_id: id,
        owner,
        observation,
        place_label: label,
        place_class: capture.place_class,
        lat: capture.lat,
        lon: capture.lon,
        at_ms: capture.at_ms,
        quality: quality.min(100),
        redactions,
        proof: String::new(),
    };
    p.proof = packet_proof(&p);
    let reason = if redactions > 0 { format!("Packaged. {redactions} contact detail(s) removed.") } else { "Packaged.".into() };
    Ok(RefineOutcome { packet: Some(p), reason })
}

// ---- Missions ------------------------------------------------------------------------------

/// Posts a new tasking.
#[uniffi::export]
pub fn mission_post(
    id: String,
    title: String,
    brief: String,
    place_class: PlaceClass,
    precedence: Precedence,
    posted_by: String,
    now_ms: i64,
) -> Result<Mission, AetherError> {
    if place_class.protected() {
        return Err(state("missions may not task collection at protected or private places"));
    }
    Ok(Mission {
        id: need(&id, "mission id")?,
        title: need(&title, "title")?,
        brief: clean(&brief, MAX_NOTE_CHARS, "brief", true)?,
        place_class,
        precedence,
        posted_by: need(&posted_by, "poster")?,
        posted_at_ms: now_ms,
        state: MissionState::Open,
        claimed_by: None,
        claimed_at_ms: None,
        packet_id: None,
        rejected_packets: vec![],
    })
}

#[uniffi::export]
pub fn mission_claim(mission: Mission, who: String, now_ms: i64) -> Result<Mission, AetherError> {
    let who = need(&who, "claimer")?;
    if mission.state != MissionState::Open {
        return Err(state("only an open mission can be claimed"));
    }
    if who == mission.posted_by {
        return Err(state("the poster cannot claim their own mission"));
    }
    Ok(Mission { state: MissionState::Claimed, claimed_by: Some(who), claimed_at_ms: Some(now_ms), ..mission })
}

#[uniffi::export]
pub fn mission_submit(mission: Mission, who: String, packet: Packet) -> Result<Mission, AetherError> {
    if mission.state != MissionState::Claimed || mission.claimed_by.as_deref() != Some(who.trim()) {
        return Err(state("only the claimer of a claimed mission can submit"));
    }
    if packet.owner != who.trim() {
        return Err(state("you can only submit your own packet"));
    }
    if !field_verify_packet(packet.clone()) {
        return Err(state("packet proof does not verify"));
    }
    if mission.rejected_packets.contains(&packet.id) {
        return Err(state("that packet was already turned down for this mission"));
    }
    if packet.place_class != mission.place_class {
        return Err(state("packet place does not match the mission"));
    }
    Ok(Mission { state: MissionState::Submitted, packet_id: Some(packet.id), ..mission })
}

#[uniffi::export]
pub fn mission_accept(mission: Mission, who: String) -> Result<Mission, AetherError> {
    if mission.state != MissionState::Submitted || who.trim() != mission.posted_by {
        return Err(state("only the poster can accept a submitted mission"));
    }
    Ok(Mission { state: MissionState::Accepted, ..mission })
}

/// The poster turns a submission down: the mission reopens to the same claimer and that packet is barred.
#[uniffi::export]
pub fn mission_reject(mission: Mission, who: String) -> Result<Mission, AetherError> {
    if mission.state != MissionState::Submitted || who.trim() != mission.posted_by {
        return Err(state("only the poster can reject a submitted mission"));
    }
    let mut rejected = mission.rejected_packets.clone();
    rejected.extend(mission.packet_id.clone());
    Ok(Mission { state: MissionState::Claimed, packet_id: None, rejected_packets: rejected, ..mission })
}

/// The claimer gives the mission back.
#[uniffi::export]
pub fn mission_release(mission: Mission, who: String) -> Result<Mission, AetherError> {
    if mission.state != MissionState::Claimed || mission.claimed_by.as_deref() != Some(who.trim()) {
        return Err(state("only the claimer can release a claimed mission"));
    }
    Ok(Mission { state: MissionState::Open, claimed_by: None, claimed_at_ms: None, ..mission })
}

/// The poster withdraws an open mission, or one claimed over 7 days ago with nothing submitted.
#[uniffi::export]
pub fn mission_cancel(mission: Mission, who: String, now_ms: i64) -> Result<Mission, AetherError> {
    if who.trim() != mission.posted_by {
        return Err(state("only the poster can cancel a mission"));
    }
    let stale = mission.state == MissionState::Claimed && mission.claimed_at_ms.is_some_and(|t| now_ms - t > STALE_CLAIM_MS);
    if mission.state != MissionState::Open && !stale {
        return Err(state("only an open mission, or a claim over 7 days old, can be cancelled"));
    }
    Ok(Mission { state: MissionState::Cancelled, ..mission })
}

// ---- Wire format ---------------------------------------------------------------------------

/// A packet or a mission snapshot shared between teammates (`MessageKind::Field`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FieldMessage {
    pub packet: Option<Packet>,
    pub mission: Option<Mission>,
}

fn esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('\n', "\\n").replace('|', "\\p")
}

fn unesc(s: &str) -> Result<String, AetherError> {
    let mut o = String::new();
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('\\') => o.push('\\'),
                Some('n') => o.push('\n'),
                Some('p') => o.push('|'),
                _ => return Err(bad("bad escape in field message")),
            }
        } else {
            o.push(c);
        }
    }
    Ok(o)
}

fn opt_f(v: Option<f64>) -> String {
    v.map(|x| format!("{x:.7}")).unwrap_or_default()
}

fn opt_i(v: Option<i64>) -> String {
    v.map(|x| x.to_string()).unwrap_or_default()
}

fn precedence_code(p: Precedence) -> &'static str {
    match p {
        Precedence::Routine => "R",
        Precedence::Priority => "P",
        Precedence::Immediate => "O",
        Precedence::Flash => "Z",
    }
}

fn state_code(s: MissionState) -> &'static str {
    match s {
        MissionState::Open => "open",
        MissionState::Claimed => "claimed",
        MissionState::Submitted => "submitted",
        MissionState::Accepted => "accepted",
        MissionState::Cancelled => "cancelled",
    }
}

/// Encodes a field message body. Packets are re-verified so a broken proof is never sent.
#[uniffi::export]
pub fn encode_field_message(message: FieldMessage) -> Result<String, AetherError> {
    let mut lines = vec!["AETHER-FIELD/1".to_owned()];
    match (&message.packet, &message.mission) {
        (Some(p), None) => {
            if !field_verify_packet(p.clone()) {
                return Err(state("packet proof does not verify"));
            }
            lines.push(
                [
                    "packet", &p.id, &p.capture_id, &p.owner, &p.observation, &p.place_label, p.place_class.code(),
                    &opt_f(p.lat), &opt_f(p.lon), &p.at_ms.to_string(), &p.quality.to_string(), &p.redactions.to_string(), &p.proof,
                ]
                .map(esc)
                .join("|"),
            );
        }
        (None, Some(m)) => lines.push(
            [
                "mission", &m.id, &m.title, &m.brief, m.place_class.code(), precedence_code(m.precedence), &m.posted_by,
                &m.posted_at_ms.to_string(), state_code(m.state), m.claimed_by.as_deref().unwrap_or(""), &opt_i(m.claimed_at_ms),
                m.packet_id.as_deref().unwrap_or(""), &m.rejected_packets.join(","),
            ]
            .map(esc)
            .join("|"),
        ),
        _ => return Err(bad("a field message carries exactly one packet or mission")),
    }
    Ok(lines.join("\n"))
}

#[uniffi::export]
pub fn decode_field_message(body: String) -> Result<FieldMessage, AetherError> {
    let (magic, rest) = body.split_once('\n').ok_or_else(|| bad("not a field message"))?;
    if magic != "AETHER-FIELD/1" || rest.contains('\n') {
        return Err(bad("not a field message"));
    }
    let f: Vec<String> = rest.split('|').map(unesc).collect::<Result<_, _>>()?;
    let num_f = |s: &str| -> Result<Option<f64>, AetherError> {
        if s.is_empty() { Ok(None) } else { s.parse().map(Some).map_err(|_| bad("bad number")) }
    };
    let num_i = |s: &str| -> Result<i64, AetherError> { s.parse().map_err(|_| bad("bad number")) };
    let place = |s: &str| PlaceClass::from_code(s).ok_or_else(|| bad("unknown place class"));
    let opt_s = |s: &str| (!s.is_empty()).then(|| s.to_owned());
    match f.first().map(String::as_str) {
        Some("packet") if f.len() == 13 => {
            let p = Packet {
                id: f[1].clone(),
                capture_id: f[2].clone(),
                owner: f[3].clone(),
                observation: f[4].clone(),
                place_label: f[5].clone(),
                place_class: place(&f[6])?,
                lat: num_f(&f[7])?,
                lon: num_f(&f[8])?,
                at_ms: num_i(&f[9])?,
                quality: f[10].parse().map_err(|_| bad("bad quality"))?,
                redactions: f[11].parse().map_err(|_| bad("bad redactions"))?,
                proof: f[12].clone(),
            };
            if !field_verify_packet(p.clone()) {
                return Err(state("packet proof does not verify"));
            }
            Ok(FieldMessage { packet: Some(p), mission: None })
        }
        Some("mission") if f.len() == 13 => {
            let precedence = match f[5].as_str() {
                "R" => Precedence::Routine,
                "P" => Precedence::Priority,
                "O" => Precedence::Immediate,
                "Z" => Precedence::Flash,
                _ => return Err(bad("unknown precedence")),
            };
            let st = match f[8].as_str() {
                "open" => MissionState::Open,
                "claimed" => MissionState::Claimed,
                "submitted" => MissionState::Submitted,
                "accepted" => MissionState::Accepted,
                "cancelled" => MissionState::Cancelled,
                _ => return Err(bad("unknown mission state")),
            };
            let pc = place(&f[4])?;
            if pc.protected() {
                return Err(state("missions may not task collection at protected or private places"));
            }
            Ok(FieldMessage {
                packet: None,
                mission: Some(Mission {
                    id: need(&f[1], "mission id")?,
                    title: need(&f[2], "title")?,
                    brief: clean(&f[3], MAX_NOTE_CHARS, "brief", true)?,
                    place_class: pc,
                    precedence,
                    posted_by: need(&f[6], "poster")?,
                    posted_at_ms: num_i(&f[7])?,
                    state: st,
                    claimed_by: opt_s(&f[9]),
                    claimed_at_ms: if f[10].is_empty() { None } else { Some(num_i(&f[10])?) },
                    packet_id: opt_s(&f[11]),
                    rejected_packets: if f[12].is_empty() { vec![] } else { f[12].split(',').map(str::to_owned).collect() },
                }),
            })
        }
        _ => Err(bad("not a field message")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cap(place: PlaceClass) -> Capture {
        Capture {
            id: "C-1".into(),
            owner: "BRAVO-2".into(),
            note: "Bridge intact, two trucks parked. Contact ops@example.org or +1 555 0100 or https://x.test/a".into(),
            place_label: "Bridge 4".into(),
            place_class: place,
            lat: Some(46.5),
            lon: Some(7.9),
            at_ms: 1_000,
        }
    }

    #[test]
    fn refine_redacts_and_seals() {
        let out = field_refine(cap(PlaceClass::Infrastructure), false, 2_000).unwrap();
        let p = out.packet.unwrap();
        assert!(!p.observation.contains("ops@example.org") && !p.observation.contains("https://"), "{}", p.observation);
        assert!(p.observation.contains("Bridge intact"));
        assert!(p.redactions >= 2, "{}", p.redactions);
        assert!(p.quality >= 50 && p.quality <= 100);
        assert!(field_verify_packet(p.clone()));
        assert!(!field_verify_packet(Packet { observation: "edited".into(), ..p }));
        assert!(out.reason.contains("removed"));
    }

    #[test]
    fn protected_places_stay_on_device() {
        for pc in [PlaceClass::Hospital, PlaceClass::School, PlaceClass::PrivateResidence, PlaceClass::HomeInterior] {
            assert!(field_refine(cap(pc), false, 2_000).unwrap().packet.is_none());
            assert!(field_refine(cap(pc), true, 2_000).unwrap().packet.is_some());
            assert!(mission_post("M".into(), "t".into(), "".into(), pc, Precedence::Routine, "HQ".into(), 0).is_err());
        }
        assert!(field_refine(Capture { note: " ".into(), ..cap(PlaceClass::Field) }, false, 0).is_err());
        assert!(field_refine(Capture { lat: Some(1.0), lon: None, ..cap(PlaceClass::Field) }, false, 0).is_err());
    }

    #[test]
    fn mission_lifecycle() {
        let packet = field_refine(cap(PlaceClass::Infrastructure), false, 2_000).unwrap().packet.unwrap();
        let m = mission_post("M-1".into(), "Recon bridge 4".into(), "Condition and traffic".into(), PlaceClass::Infrastructure, Precedence::Priority, "HQ".into(), 0).unwrap();
        assert!(mission_claim(m.clone(), "HQ".into(), 1).is_err());
        let claimed = mission_claim(m.clone(), "BRAVO-2".into(), 1).unwrap();
        assert!(mission_claim(claimed.clone(), "CHARLIE".into(), 2).is_err());
        assert!(mission_submit(claimed.clone(), "CHARLIE".into(), packet.clone()).is_err());
        let sub = mission_submit(claimed.clone(), "BRAVO-2".into(), packet.clone()).unwrap();
        assert!(mission_accept(sub.clone(), "BRAVO-2".into()).is_err());
        let rej = mission_reject(sub.clone(), "HQ".into()).unwrap();
        assert_eq!(rej.state, MissionState::Claimed);
        assert!(mission_submit(rej.clone(), "BRAVO-2".into(), packet.clone()).is_err(), "rejected packet resent");
        let released = mission_release(rej, "BRAVO-2".into()).unwrap();
        assert_eq!((released.state, released.claimed_by.as_deref()), (MissionState::Open, None));
        let acc = mission_accept(sub, "HQ".into()).unwrap();
        assert_eq!(acc.state, MissionState::Accepted);
        assert!(mission_cancel(acc, "HQ".into(), 0).is_err());
        assert!(mission_cancel(claimed.clone(), "HQ".into(), 2).is_err());
        assert_eq!(mission_cancel(claimed, "HQ".into(), 1 + STALE_CLAIM_MS + 1).unwrap().state, MissionState::Cancelled);
        assert_eq!(mission_cancel(m, "HQ".into(), 0).unwrap().state, MissionState::Cancelled);
    }

    #[test]
    fn wire_round_trip() {
        let packet = field_refine(Capture { note: "line one\nline | two \\ end".into(), ..cap(PlaceClass::Military) }, false, 2_000).unwrap().packet.unwrap();
        let fm = FieldMessage { packet: Some(packet), mission: None };
        assert_eq!(decode_field_message(encode_field_message(fm.clone()).unwrap()).unwrap(), fm);
        let mut m = mission_post("M-2".into(), "Watch | road".into(), "a\nb".into(), PlaceClass::Street, Precedence::Flash, "HQ".into(), 5).unwrap();
        m.rejected_packets = vec!["PKT-a".into(), "PKT-b".into()];
        let fm = FieldMessage { packet: None, mission: Some(m) };
        assert_eq!(decode_field_message(encode_field_message(fm.clone()).unwrap()).unwrap(), fm);
        assert!(encode_field_message(FieldMessage { packet: None, mission: None }).is_err());
        assert!(decode_field_message("AETHER-FIELD/1\nmission|x".into()).is_err());
        let tampered = encode_field_message(FieldMessage {
            packet: field_refine(cap(PlaceClass::Field), false, 0).unwrap().packet,
            mission: None,
        })
        .unwrap()
        .replace("Bridge intact", "Bridge down");
        assert!(decode_field_message(tampered).is_err());
    }
}
