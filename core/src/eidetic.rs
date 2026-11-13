//! ÆTHER eidetic MIL™: identity verification for force protection, on the device.
//!
//! The eidetic pipeline (authorize → match → human review → record), MIL edition:
//!
//! 1. **Authorize.** [`eidetic_authorize`] checks role, purpose, legal authority and dataset.
//!    Only these purposes exist: checkpoint verification, base access control, personnel
//!    recovery and casualty identification. There is deliberately no targeting, surveillance
//!    or open-population search purpose, and matches are never an input to fires or strike decisions.
//! 2. **Match.** [`eidetic_match`] compares a probe template against an *enrolled roster* only
//!    (own forces or an authorized access list). Each enrolled person carries a consent or
//!    enrollment-order reference. The result is a confidence score with its evidence (score,
//!    margin over the runner-up, alternatives), never a bare yes/no.
//! 3. **Review.** Every search opens a pending review, even at high confidence. Only a
//!    reviewer who did not run the search can confirm, reject or mark it inconclusive.
//! 4. **Record.** Each step is written to the hash-chained audit log ([`crate::audit_append`])
//!    with ids, purpose and scores. Templates live only in a sealed ÆTHER Vault™ and never
//!    enter the audit log, messages or process logs.
//!
//! Template extraction (the face model) is outside the core; it hands this module vectors.

use crate::AetherError;
use sha2::{Digest, Sha256};

pub const MIN_TEMPLATE_DIM: usize = 64;
pub const MAX_TEMPLATE_DIM: usize = 2048;
/// Below this cosine similarity a candidate is not reported at all.
pub const MIN_REPORTED_SCORE: f32 = 0.35;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EideticRole {
    /// Runs searches at a checkpoint or access point.
    Operator,
    /// Trained reviewer who confirms or rejects results. Cannot review their own searches.
    Reviewer,
    /// Reads the audit log; cannot search or review.
    Auditor,
    /// Enrolls people into rosters; cannot search or review.
    Enrollment,
}

/// The only purposes ÆTHER eidetic MIL™ will run a search for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EideticPurpose {
    /// Verify a person presenting at a checkpoint against the authorized access list.
    CheckpointVerification,
    /// Admit a person to a base, facility or restricted area.
    AccessControl,
    /// Identify recovered own-force personnel.
    PersonnelRecovery,
    /// Identify own-force casualties.
    CasualtyIdentification,
}

/// Rosters a search can run against. Every member was enrolled with a consent or order reference.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EideticDataset {
    /// Consenting own-force personnel.
    OwnForces,
    /// People authorized to enter a site (staff, contractors, visitors) who enrolled for access.
    AccessList,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchRequest {
    pub search_id: String,
    pub searcher: String,
    pub role: EideticRole,
    pub purpose: EideticPurpose,
    pub dataset: EideticDataset,
    /// The order, directive or standing authority the search runs under (e.g. "FRAGO 12-3, ROE annex C").
    pub legal_authority: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PolicyDecision {
    pub allowed: bool,
    /// Why, in one sentence; shown to the operator and written to the audit log.
    pub reason: String,
}

/// One enrolled person's template, held in the sealed store.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EnrolledTemplate {
    pub subject_id: String,
    pub display_name: String,
    pub dataset: EideticDataset,
    /// Consent record or enrollment order reference. Required.
    pub consent_ref: String,
    pub vector: Vec<f32>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MatchCandidate {
    pub subject_id: String,
    pub display_name: String,
    pub score: f32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MatchResult {
    pub search_id: String,
    /// Best candidates first, at most 3, each at or above [`MIN_REPORTED_SCORE`].
    pub candidates: Vec<MatchCandidate>,
    /// 0–1: the best score, discounted when the runner-up is close.
    pub confidence: f32,
    /// Best score minus runner-up score (1.0 when there is no runner-up).
    pub margin: f32,
    /// Plain-language evidence for the reviewer.
    pub evidence: String,
    /// Fingerprint of the probe (SHA-256 prefix) so the review can be tied to it without storing it.
    pub probe_digest: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReviewOutcome {
    Pending,
    Confirmed,
    Rejected,
    Inconclusive,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Review {
    pub search_id: String,
    pub searcher: String,
    pub outcome: ReviewOutcome,
    pub reviewer: Option<String>,
    pub decided_at_ms: Option<i64>,
    /// Subject the reviewer confirmed, when confirmed.
    pub confirmed_subject: Option<String>,
    pub note: String,
}

fn bad(reason: &str) -> AetherError {
    AetherError::malformed(reason)
}

fn deny(reason: &str) -> PolicyDecision {
    PolicyDecision { allowed: false, reason: reason.to_owned() }
}

fn clean_id(s: &str, what: &str) -> Result<String, AetherError> {
    let t = s.trim();
    if t.is_empty() || t.chars().count() > 128 || t.chars().any(char::is_control) {
        return Err(bad(&format!("{what} must be 1-128 characters on one line")));
    }
    Ok(t.to_owned())
}

/// Datasets each purpose may search.
fn allowed_datasets(p: EideticPurpose) -> &'static [EideticDataset] {
    match p {
        EideticPurpose::CheckpointVerification | EideticPurpose::AccessControl => &[EideticDataset::AccessList, EideticDataset::OwnForces],
        EideticPurpose::PersonnelRecovery | EideticPurpose::CasualtyIdentification => &[EideticDataset::OwnForces],
    }
}

/// Decides whether a search may run. A denial is a normal result (record it); malformed
/// requests are errors.
#[uniffi::export]
pub fn eidetic_authorize(request: SearchRequest) -> Result<PolicyDecision, AetherError> {
    clean_id(&request.search_id, "search id")?;
    clean_id(&request.searcher, "searcher")?;
    if request.role != EideticRole::Operator {
        return Ok(deny("Only an operator may run a search; reviewers, auditors and enrollment staff may not."));
    }
    let authority = request.legal_authority.trim();
    if authority.chars().count() < 4 || authority.chars().count() > 256 || authority.chars().any(char::is_control) {
        return Ok(deny("A search needs the order or standing authority it runs under."));
    }
    if !allowed_datasets(request.purpose).contains(&request.dataset) {
        return Ok(deny("That roster is not searchable for this purpose."));
    }
    Ok(PolicyDecision { allowed: true, reason: "Allowed: operator, permitted purpose, stated authority, permitted roster.".into() })
}

/// Checks an enrollment before the template is sealed into the store.
#[uniffi::export]
pub fn eidetic_check_enrollment(template: EnrolledTemplate, enrolled_by_role: EideticRole) -> Result<EnrolledTemplate, AetherError> {
    if enrolled_by_role != EideticRole::Enrollment {
        return Err(AetherError::state("only enrollment staff may enroll people"));
    }
    let consent = template.consent_ref.trim();
    if consent.chars().count() < 4 || consent.chars().any(char::is_control) {
        return Err(bad("enrollment needs a consent record or enrollment order reference"));
    }
    Ok(EnrolledTemplate {
        subject_id: clean_id(&template.subject_id, "subject id")?,
        display_name: clean_id(&template.display_name, "display name")?,
        consent_ref: consent.to_owned(),
        vector: normalize(&template.vector)?,
        dataset: template.dataset,
    })
}

fn normalize(v: &[f32]) -> Result<Vec<f32>, AetherError> {
    if v.len() < MIN_TEMPLATE_DIM || v.len() > MAX_TEMPLATE_DIM || v.iter().any(|x| !x.is_finite()) {
        return Err(bad("template must be 64-2048 finite values"));
    }
    let n = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if n < 1e-6 {
        return Err(bad("template is all zeros"));
    }
    Ok(v.iter().map(|x| x / n).collect())
}

fn probe_digest(v: &[f32]) -> String {
    let mut h = Sha256::new();
    for x in v {
        h.update(x.to_le_bytes());
    }
    h.finalize()[..8].iter().map(|b| format!("{b:02x}")).collect()
}

/// Matches an authorized search's probe against the roster. `roster` is filtered to the
/// request's dataset here, so a caller cannot widen the search by passing more people.
#[uniffi::export]
pub fn eidetic_match(request: SearchRequest, probe: Vec<f32>, roster: Vec<EnrolledTemplate>) -> Result<MatchResult, AetherError> {
    if !eidetic_authorize(request.clone())?.allowed {
        return Err(AetherError::state("search is not authorized"));
    }
    let probe = normalize(&probe)?;
    let mut scored: Vec<MatchCandidate> = Vec::new();
    for t in roster.iter().filter(|t| t.dataset == request.dataset) {
        if t.vector.len() != probe.len() {
            return Err(bad("roster template size does not match the probe"));
        }
        if t.consent_ref.trim().is_empty() {
            continue;
        }
        let tv = normalize(&t.vector)?;
        let score = tv.iter().zip(&probe).map(|(a, b)| a * b).sum::<f32>().clamp(-1.0, 1.0);
        scored.push(MatchCandidate { subject_id: t.subject_id.clone(), display_name: t.display_name.clone(), score });
    }
    scored.sort_by(|a, b| b.score.total_cmp(&a.score));
    let best = scored.first().map_or(0.0, |c| c.score.max(0.0));
    let second = scored.get(1).map(|c| c.score.max(0.0));
    let margin = second.map_or(1.0, |s| best - s);
    // Confidence: the best score, pulled down when the runner-up is within 0.1.
    let confidence = (best * (0.5 + 0.5 * (margin / 0.1).min(1.0))).clamp(0.0, 1.0);
    let candidates: Vec<MatchCandidate> = scored.into_iter().filter(|c| c.score >= MIN_REPORTED_SCORE).take(3).collect();
    let evidence = match candidates.first() {
        None => "No enrolled person scored above the reporting floor. Treat as not on the roster.".to_owned(),
        Some(c) => format!(
            "Best: {} at {:.2}. Margin over next: {:.2}. {} candidate(s) shown. A reviewer must confirm before anyone acts on this.",
            c.display_name,
            c.score,
            margin,
            candidates.len()
        ),
    };
    Ok(MatchResult {
        search_id: request.search_id.trim().to_owned(),
        candidates,
        confidence,
        margin,
        evidence,
        probe_digest: probe_digest(&probe),
    })
}

/// Opens the review every search gets, regardless of confidence.
#[uniffi::export]
pub fn eidetic_open_review(search_id: String, searcher: String) -> Result<Review, AetherError> {
    Ok(Review {
        search_id: clean_id(&search_id, "search id")?,
        searcher: clean_id(&searcher, "searcher")?,
        outcome: ReviewOutcome::Pending,
        reviewer: None,
        decided_at_ms: None,
        confirmed_subject: None,
        note: String::new(),
    })
}

/// Records a reviewer's decision. The person who ran the search cannot decide it, a review is
/// decided once, and a confirmation must name one of the reported candidates.
#[uniffi::export]
pub fn eidetic_decide(
    review: Review,
    result: MatchResult,
    reviewer: String,
    reviewer_role: EideticRole,
    outcome: ReviewOutcome,
    confirmed_subject: Option<String>,
    note: String,
    at_ms: i64,
) -> Result<Review, AetherError> {
    let reviewer = clean_id(&reviewer, "reviewer")?;
    if reviewer_role != EideticRole::Reviewer {
        return Err(AetherError::state("only a reviewer may decide a review"));
    }
    if reviewer.eq_ignore_ascii_case(&review.searcher) {
        return Err(AetherError::state("the person who ran a search cannot review it"));
    }
    if review.outcome != ReviewOutcome::Pending {
        return Err(AetherError::state("this review is already decided"));
    }
    if result.search_id != review.search_id {
        return Err(AetherError::state("result belongs to a different search"));
    }
    let note = note.trim();
    if note.chars().count() > 1024 || note.chars().any(|c| c.is_control() && c != '\n') {
        return Err(bad("note must be at most 1024 characters"));
    }
    let confirmed = match outcome {
        ReviewOutcome::Pending => return Err(bad("a decision cannot be pending")),
        ReviewOutcome::Confirmed => {
            let s = confirmed_subject.ok_or_else(|| bad("a confirmation must name the person confirmed"))?;
            if !result.candidates.iter().any(|c| c.subject_id == s) {
                return Err(bad("can only confirm a reported candidate"));
            }
            Some(s)
        }
        _ => None,
    };
    Ok(Review {
        outcome,
        reviewer: Some(reviewer),
        decided_at_ms: Some(at_ms),
        confirmed_subject: confirmed,
        note: note.to_owned(),
        ..review
    })
}

// ---- Wire format: review requests and decisions between devices ---------------------------

/// A search sent to another device for its mandatory second-person review. Carries the
/// request, the match result (ids, names, scores, evidence, probe digest) and the pending
/// review. Never the probe or any template.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReviewRequest {
    pub request: SearchRequest,
    pub result: MatchResult,
    /// The searcher's pending review for this search.
    pub review: Review,
}

/// An eidetic message between ÆTHER devices (`MessageKind::Eidetic`): exactly one of a review
/// request (searcher → reviewer) or a decided review (reviewer → searcher).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EideticMessage {
    pub request: Option<ReviewRequest>,
    pub decision: Option<Review>,
}

const EIDETIC_MAGIC: &str = "AETHER-EIDETIC/1";
/// Largest encoded eidetic message body.
const MAX_EIDETIC_BODY: usize = 16 * 1024;
const MAX_TEXT_CHARS: usize = 1024;

fn e_esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('\n', "\\n").replace('|', "\\p")
}

fn e_unesc(s: &str) -> Result<String, AetherError> {
    let mut o = String::new();
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('\\') => o.push('\\'),
                Some('n') => o.push('\n'),
                Some('p') => o.push('|'),
                _ => return Err(bad("bad escape in eidetic message")),
            }
        } else {
            o.push(c);
        }
    }
    Ok(o)
}

fn role_code(r: EideticRole) -> &'static str {
    match r {
        EideticRole::Operator => "operator",
        EideticRole::Reviewer => "reviewer",
        EideticRole::Auditor => "auditor",
        EideticRole::Enrollment => "enrollment",
    }
}

fn purpose_code(p: EideticPurpose) -> &'static str {
    match p {
        EideticPurpose::CheckpointVerification => "checkpoint_verification",
        EideticPurpose::AccessControl => "access_control",
        EideticPurpose::PersonnelRecovery => "personnel_recovery",
        EideticPurpose::CasualtyIdentification => "casualty_identification",
    }
}

fn dataset_code(d: EideticDataset) -> &'static str {
    match d {
        EideticDataset::OwnForces => "own_forces",
        EideticDataset::AccessList => "access_list",
    }
}

fn outcome_code(o: ReviewOutcome) -> &'static str {
    match o {
        ReviewOutcome::Pending => "pending",
        ReviewOutcome::Confirmed => "confirmed",
        ReviewOutcome::Rejected => "rejected",
        ReviewOutcome::Inconclusive => "inconclusive",
    }
}

fn parse_role(s: &str) -> Result<EideticRole, AetherError> {
    [EideticRole::Operator, EideticRole::Reviewer, EideticRole::Auditor, EideticRole::Enrollment]
        .into_iter()
        .find(|r| role_code(*r) == s)
        .ok_or_else(|| bad("unknown role"))
}

fn parse_purpose(s: &str) -> Result<EideticPurpose, AetherError> {
    [
        EideticPurpose::CheckpointVerification,
        EideticPurpose::AccessControl,
        EideticPurpose::PersonnelRecovery,
        EideticPurpose::CasualtyIdentification,
    ]
    .into_iter()
    .find(|p| purpose_code(*p) == s)
    .ok_or_else(|| bad("unknown purpose"))
}

fn parse_dataset(s: &str) -> Result<EideticDataset, AetherError> {
    [EideticDataset::OwnForces, EideticDataset::AccessList]
        .into_iter()
        .find(|d| dataset_code(*d) == s)
        .ok_or_else(|| bad("unknown dataset"))
}

fn parse_outcome(s: &str) -> Result<ReviewOutcome, AetherError> {
    [ReviewOutcome::Pending, ReviewOutcome::Confirmed, ReviewOutcome::Rejected, ReviewOutcome::Inconclusive]
        .into_iter()
        .find(|o| outcome_code(*o) == s)
        .ok_or_else(|| bad("unknown review outcome"))
}

fn parse_unit(s: &str, what: &str) -> Result<f32, AetherError> {
    let v: f32 = s.parse().map_err(|_| bad(&format!("bad {what}")))?;
    if !v.is_finite() || !(-1.0..=1.0).contains(&v) {
        return Err(bad(&format!("{what} out of range")));
    }
    Ok(v)
}

/// Free text that may travel (authority, evidence, note): bounded, and never anything that
/// looks like a template, vector or embedded media.
fn check_text(s: &str, what: &str, allow_newlines: bool) -> Result<(), AetherError> {
    if s.chars().count() > MAX_TEXT_CHARS || s.chars().any(|c| c.is_control() && !(allow_newlines && c == '\n')) {
        return Err(bad(&format!("{what} must be at most {MAX_TEXT_CHARS} characters")));
    }
    if crate::audit::looks_biometric(s) {
        return Err(bad("biometric data may not travel in an eidetic message"));
    }
    Ok(())
}

fn check_id(s: &str, what: &str) -> Result<(), AetherError> {
    if clean_id(s, what)? != s {
        return Err(bad(&format!("{what} has surrounding spaces")));
    }
    if crate::audit::looks_biometric(s) {
        return Err(bad("biometric data may not travel in an eidetic message"));
    }
    Ok(())
}

fn check_digest(s: &str) -> Result<(), AetherError> {
    if s.len() != 16 || !s.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)) {
        return Err(bad("probe digest must be 16 lowercase hex characters"));
    }
    Ok(())
}

/// Everything a review request must satisfy, on both send and receive.
fn check_request(r: &ReviewRequest) -> Result<(), AetherError> {
    let q = &r.request;
    check_id(&q.search_id, "search id")?;
    check_id(&q.searcher, "searcher")?;
    check_text(&q.legal_authority, "legal authority", false)?;
    if !eidetic_authorize(q.clone())?.allowed {
        return Err(AetherError::state("only an authorized search can be sent for review"));
    }
    let m = &r.result;
    if m.search_id != q.search_id || r.review.search_id != q.search_id {
        return Err(AetherError::state("request, result and review belong to different searches"));
    }
    if m.candidates.len() > 3 {
        return Err(bad("at most 3 candidates"));
    }
    let mut last = f32::INFINITY;
    for c in &m.candidates {
        check_id(&c.subject_id, "subject id")?;
        check_id(&c.display_name, "display name")?;
        if !c.score.is_finite() || c.score < MIN_REPORTED_SCORE || c.score > 1.0 || c.score > last {
            return Err(bad("candidate scores must be reportable, at most 1 and best first"));
        }
        last = c.score;
    }
    for (v, what) in [(m.confidence, "confidence"), (m.margin, "margin")] {
        if !v.is_finite() || !(0.0..=1.0).contains(&v) {
            return Err(bad(&format!("{what} out of range")));
        }
    }
    check_text(&m.evidence, "evidence", false)?;
    check_digest(&m.probe_digest)?;
    let rv = &r.review;
    if rv.outcome != ReviewOutcome::Pending
        || rv.reviewer.is_some()
        || rv.decided_at_ms.is_some()
        || rv.confirmed_subject.is_some()
        || !rv.note.is_empty()
    {
        return Err(AetherError::state("a review request carries a pending, undecided review"));
    }
    if rv.searcher != q.searcher {
        return Err(AetherError::state("review searcher does not match the search"));
    }
    Ok(())
}

/// Everything a returned decision must satisfy, on both send and receive.
fn check_decision(r: &Review) -> Result<(), AetherError> {
    check_id(&r.search_id, "search id")?;
    check_id(&r.searcher, "searcher")?;
    let reviewer = r.reviewer.as_deref().ok_or_else(|| bad("a decision names its reviewer"))?;
    check_id(reviewer, "reviewer")?;
    if reviewer.eq_ignore_ascii_case(&r.searcher) {
        return Err(AetherError::state("the person who ran a search cannot review it"));
    }
    if r.decided_at_ms.is_none() {
        return Err(bad("a decision carries its time"));
    }
    match (r.outcome, &r.confirmed_subject) {
        (ReviewOutcome::Pending, _) => return Err(bad("a decision cannot be pending")),
        (ReviewOutcome::Confirmed, Some(s)) => check_id(s, "confirmed subject")?,
        (ReviewOutcome::Confirmed, None) => return Err(bad("a confirmation must name the person confirmed")),
        (_, Some(_)) => return Err(bad("only a confirmation names a person")),
        _ => {}
    }
    check_text(&r.note, "note", true)
}

fn review_fields(r: &Review) -> Vec<String> {
    vec![
        r.search_id.clone(),
        r.searcher.clone(),
        outcome_code(r.outcome).to_owned(),
        r.reviewer.clone().unwrap_or_default(),
        r.decided_at_ms.map(|t| t.to_string()).unwrap_or_default(),
        r.confirmed_subject.clone().unwrap_or_default(),
        r.note.clone(),
    ]
}

fn wire_line(tag: &str, fields: &[String]) -> String {
    std::iter::once(tag.to_owned()).chain(fields.iter().map(|f| e_esc(f))).collect::<Vec<_>>().join("|")
}

/// Encodes an eidetic message body:
///
/// ```text
/// AETHER-EIDETIC/1
/// request|search_id|searcher|role|purpose|dataset|legal_authority
/// result|confidence|margin|evidence|probe_digest
/// candidate|subject_id|display_name|score        (0-3 lines, best first)
/// review|search_id|searcher|pending||||
/// ```
/// or, for a decision, the magic line and one
/// `decision|search_id|searcher|outcome|reviewer|decided_at_ms|confirmed_subject|note` line.
/// Fields are escaped (`\\`, `\n`, `\p` for `|`). The message is checked before encoding.
#[uniffi::export]
pub fn encode_eidetic_message(message: EideticMessage) -> Result<String, AetherError> {
    let mut lines = vec![EIDETIC_MAGIC.to_owned()];
    match (&message.request, &message.decision) {
        (Some(r), None) => {
            check_request(r)?;
            let q = &r.request;
            lines.push(wire_line(
                "request",
                &[
                    q.search_id.clone(),
                    q.searcher.clone(),
                    role_code(q.role).into(),
                    purpose_code(q.purpose).into(),
                    dataset_code(q.dataset).into(),
                    q.legal_authority.clone(),
                ],
            ));
            let m = &r.result;
            lines.push(wire_line("result", &[m.confidence.to_string(), m.margin.to_string(), m.evidence.clone(), m.probe_digest.clone()]));
            for c in &m.candidates {
                lines.push(wire_line("candidate", &[c.subject_id.clone(), c.display_name.clone(), c.score.to_string()]));
            }
            lines.push(wire_line("review", &review_fields(&r.review)));
        }
        (None, Some(d)) => {
            check_decision(d)?;
            lines.push(wire_line("decision", &review_fields(d)));
        }
        _ => return Err(bad("an eidetic message carries exactly one review request or decision")),
    }
    let body = lines.join("\n");
    if body.len() > MAX_EIDETIC_BODY {
        return Err(bad("eidetic message too large"));
    }
    Ok(body)
}

fn parse_review(f: &[String]) -> Result<Review, AetherError> {
    let opt = |s: &String| (!s.is_empty()).then(|| s.clone());
    Ok(Review {
        search_id: f[0].clone(),
        searcher: f[1].clone(),
        outcome: parse_outcome(&f[2])?,
        reviewer: opt(&f[3]),
        decided_at_ms: if f[4].is_empty() { None } else { Some(f[4].parse().map_err(|_| bad("bad decision time"))?) },
        confirmed_subject: opt(&f[5]),
        note: f[6].clone(),
    })
}

/// Decodes and checks an eidetic message body. Strict: exact line layout and field counts,
/// known codes only, and anything that looks like a template, vector or media is refused.
#[uniffi::export]
pub fn decode_eidetic_message(body: String) -> Result<EideticMessage, AetherError> {
    if body.len() > MAX_EIDETIC_BODY {
        return Err(bad("eidetic message too large"));
    }
    let mut lines = body.split('\n');
    if lines.next() != Some(EIDETIC_MAGIC) {
        return Err(bad("not an eidetic message"));
    }
    let rows: Vec<Vec<String>> =
        lines.map(|l| l.split('|').map(e_unesc).collect::<Result<Vec<_>, _>>()).collect::<Result<_, _>>()?;
    let tag = |i: usize| rows.get(i).and_then(|r| r.first()).map(String::as_str);
    match tag(0) {
        Some("decision") if rows.len() == 1 && rows[0].len() == 8 => {
            let d = parse_review(&rows[0][1..])?;
            check_decision(&d)?;
            Ok(EideticMessage { request: None, decision: Some(d) })
        }
        Some("request") if (3..=6).contains(&rows.len()) => {
            let last = rows.len() - 1;
            let (q, res, rv) = (&rows[0], &rows[1], &rows[last]);
            if q.len() != 7 || tag(1) != Some("result") || res.len() != 5 || tag(last) != Some("review") || rv.len() != 8 {
                return Err(bad("not an eidetic message"));
            }
            let request = SearchRequest {
                search_id: q[1].clone(),
                searcher: q[2].clone(),
                role: parse_role(&q[3])?,
                purpose: parse_purpose(&q[4])?,
                dataset: parse_dataset(&q[5])?,
                legal_authority: q[6].clone(),
            };
            let mut candidates = Vec::new();
            for c in &rows[2..last] {
                if c.len() != 4 || c[0] != "candidate" {
                    return Err(bad("not an eidetic message"));
                }
                candidates.push(MatchCandidate { subject_id: c[1].clone(), display_name: c[2].clone(), score: parse_unit(&c[3], "score")? });
            }
            let result = MatchResult {
                search_id: request.search_id.clone(),
                candidates,
                confidence: parse_unit(&res[1], "confidence")?,
                margin: parse_unit(&res[2], "margin")?,
                evidence: res[3].clone(),
                probe_digest: res[4].clone(),
            };
            let r = ReviewRequest { request, result, review: parse_review(&rv[1..])? };
            check_request(&r)?;
            Ok(EideticMessage { request: Some(r), decision: None })
        }
        _ => Err(bad("not an eidetic message")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(purpose: EideticPurpose, dataset: EideticDataset) -> SearchRequest {
        SearchRequest {
            search_id: "S-1".into(),
            searcher: "OP-ALPHA".into(),
            role: EideticRole::Operator,
            purpose,
            dataset,
            legal_authority: "Base access directive 4".into(),
        }
    }

    fn vec_for(seed: u32) -> Vec<f32> {
        (0..128u32).map(|i| ((i.wrapping_mul(2_654_435_761) ^ seed.wrapping_mul(2_246_822_519)) % 1000) as f32 / 1000.0 - 0.5).collect()
    }

    fn roster() -> Vec<EnrolledTemplate> {
        (1..=5)
            .map(|i| EnrolledTemplate {
                subject_id: format!("P-{i}"),
                display_name: format!("Person {i}"),
                dataset: if i == 5 { EideticDataset::OwnForces } else { EideticDataset::AccessList },
                consent_ref: format!("ENR-{i}"),
                vector: vec_for(i),
            })
            .collect()
    }

    #[test]
    fn policy_gate() {
        use EideticDataset::*;
        use EideticPurpose::*;
        assert!(eidetic_authorize(req(CheckpointVerification, AccessList)).unwrap().allowed);
        assert!(eidetic_authorize(req(PersonnelRecovery, OwnForces)).unwrap().allowed);
        assert!(!eidetic_authorize(req(PersonnelRecovery, AccessList)).unwrap().allowed);
        assert!(!eidetic_authorize(SearchRequest { role: EideticRole::Reviewer, ..req(AccessControl, AccessList) }).unwrap().allowed);
        assert!(!eidetic_authorize(SearchRequest { legal_authority: " ".into(), ..req(AccessControl, AccessList) }).unwrap().allowed);
        assert!(eidetic_authorize(SearchRequest { searcher: "".into(), ..req(AccessControl, AccessList) }).is_err());
    }

    #[test]
    fn matches_within_the_requested_roster_only() {
        let mut probe = vec_for(3);
        probe[0] += 0.05;
        let r = eidetic_match(req(EideticPurpose::AccessControl, EideticDataset::AccessList), probe, roster()).unwrap();
        assert_eq!(r.candidates[0].subject_id, "P-3");
        assert!(r.candidates[0].score > 0.99 && r.confidence > 0.5);
        assert!(r.candidates.iter().all(|c| c.subject_id != "P-5"), "own-forces member leaked into an access-list search");
        assert!(r.evidence.contains("reviewer must confirm"));
        assert_eq!(r.probe_digest.len(), 16);
        // The own-forces person is not findable via an access-list search, but is via recovery.
        let rec = eidetic_match(req(EideticPurpose::PersonnelRecovery, EideticDataset::OwnForces), vec_for(5), roster()).unwrap();
        assert_eq!(rec.candidates[0].subject_id, "P-5");
        assert!(eidetic_match(req(EideticPurpose::PersonnelRecovery, EideticDataset::AccessList), vec_for(5), roster()).is_err());
        assert!(eidetic_match(req(EideticPurpose::AccessControl, EideticDataset::AccessList), vec![0.1; 10], roster()).is_err());
    }

    #[test]
    fn enrollment_needs_consent_and_role() {
        let t = roster().remove(0);
        assert!(eidetic_check_enrollment(t.clone(), EideticRole::Operator).is_err());
        assert!(eidetic_check_enrollment(EnrolledTemplate { consent_ref: "".into(), ..t.clone() }, EideticRole::Enrollment).is_err());
        let ok = eidetic_check_enrollment(t, EideticRole::Enrollment).unwrap();
        assert!((ok.vector.iter().map(|x| x * x).sum::<f32>() - 1.0).abs() < 1e-4);
    }

    #[test]
    fn review_rules() {
        let request = req(EideticPurpose::CheckpointVerification, EideticDataset::AccessList);
        let result = eidetic_match(request, vec_for(2), roster()).unwrap();
        let review = eidetic_open_review("S-1".into(), "OP-ALPHA".into()).unwrap();
        let decide = |who: &str, role, outcome, subj: Option<&str>| {
            eidetic_decide(review.clone(), result.clone(), who.into(), role, outcome, subj.map(Into::into), "".into(), 5)
        };
        assert!(decide("OP-ALPHA", EideticRole::Reviewer, ReviewOutcome::Confirmed, Some("P-2")).is_err());
        assert!(decide("REV-1", EideticRole::Operator, ReviewOutcome::Confirmed, Some("P-2")).is_err());
        assert!(decide("REV-1", EideticRole::Reviewer, ReviewOutcome::Confirmed, None).is_err());
        assert!(decide("REV-1", EideticRole::Reviewer, ReviewOutcome::Confirmed, Some("P-9")).is_err());
        assert!(decide("REV-1", EideticRole::Reviewer, ReviewOutcome::Pending, None).is_err());
        let done = decide("REV-1", EideticRole::Reviewer, ReviewOutcome::Confirmed, Some("P-2")).unwrap();
        assert_eq!((done.outcome, done.confirmed_subject.as_deref()), (ReviewOutcome::Confirmed, Some("P-2")));
        assert!(eidetic_decide(done, result, "REV-2".into(), EideticRole::Reviewer, ReviewOutcome::Rejected, None, "".into(), 6).is_err());
    }

    fn review_request() -> ReviewRequest {
        let request = SearchRequest {
            legal_authority: "Gate | order \\ 7".into(),
            ..req(EideticPurpose::CheckpointVerification, EideticDataset::AccessList)
        };
        let result = eidetic_match(request.clone(), vec_for(2), roster()).unwrap();
        let review = eidetic_open_review("S-1".into(), "OP-ALPHA".into()).unwrap();
        ReviewRequest { request, result, review }
    }

    #[test]
    fn eidetic_wire_round_trip() {
        let rr = review_request();
        assert!(!rr.result.candidates.is_empty());
        let msg = EideticMessage { request: Some(rr.clone()), decision: None };
        let body = encode_eidetic_message(msg.clone()).unwrap();
        assert!(body.starts_with("AETHER-EIDETIC/1\nrequest|S-1|OP-ALPHA|operator|checkpoint_verification|access_list|"));
        assert_eq!(decode_eidetic_message(body).unwrap(), msg);

        let decided = eidetic_decide(
            rr.review.clone(),
            rr.result.clone(),
            "REV-1".into(),
            EideticRole::Reviewer,
            ReviewOutcome::Confirmed,
            Some(rr.result.candidates[0].subject_id.clone()),
            "Checked ID card.\nLine | two".into(),
            77,
        )
        .unwrap();
        let msg = EideticMessage { request: None, decision: Some(decided.clone()) };
        let body = encode_eidetic_message(msg.clone()).unwrap();
        assert!(body.starts_with("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|confirmed|REV-1|77|"));
        assert_eq!(decode_eidetic_message(body).unwrap(), msg);
        let rejected = Review { outcome: ReviewOutcome::Rejected, confirmed_subject: None, ..decided };
        let m = EideticMessage { request: None, decision: Some(rejected) };
        assert_eq!(decode_eidetic_message(encode_eidetic_message(m.clone()).unwrap()).unwrap(), m);
    }

    #[test]
    fn eidetic_wire_is_strict() {
        let rr = review_request();
        assert!(encode_eidetic_message(EideticMessage { request: None, decision: None }).is_err());
        let decided = eidetic_decide(rr.review.clone(), rr.result.clone(), "REV-1".into(), EideticRole::Reviewer, ReviewOutcome::Rejected, None, "".into(), 5).unwrap();
        assert!(encode_eidetic_message(EideticMessage { request: Some(rr.clone()), decision: Some(decided.clone()) }).is_err());
        // The searcher cannot appear as the reviewer, and a decision cannot be pending.
        let selfie = Review { reviewer: Some("op-alpha".into()), ..decided.clone() };
        assert!(encode_eidetic_message(EideticMessage { request: None, decision: Some(selfie) }).is_err());
        assert!(decode_eidetic_message("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|confirmed|OP-ALPHA|5|P-2|".into()).is_err());
        assert!(decode_eidetic_message("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|pending|REV-1|5||".into()).is_err());
        assert!(decode_eidetic_message("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|rejected|REV-1|5|P-2|".into()).is_err());
        assert!(decode_eidetic_message("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|confirmed|REV-1|5||".into()).is_err());
        assert!(decode_eidetic_message("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|rejected|REV-1|||".into()).is_err());
        // Wrong magic, extra fields, extra lines, unknown codes.
        let ok = encode_eidetic_message(EideticMessage { request: None, decision: Some(decided) }).unwrap();
        assert!(decode_eidetic_message(ok.clone()).is_ok());
        assert!(decode_eidetic_message(ok.replace("AETHER-EIDETIC/1", "AETHER-EIDETIC/2")).is_err());
        assert!(decode_eidetic_message(format!("{ok}|x")).is_err());
        assert!(decode_eidetic_message(format!("{ok}\n")).is_err());
        assert!(decode_eidetic_message(ok.replace("rejected", "approved")).is_err());
        let req_body = encode_eidetic_message(EideticMessage { request: Some(rr.clone()), decision: None }).unwrap();
        assert!(decode_eidetic_message(req_body.replace("|operator|", "|reviewer|")).is_err(), "unauthorized search accepted");
        assert!(decode_eidetic_message(req_body.replace("review|S-1|OP-ALPHA|pending", "review|S-1|OP-ALPHA|confirmed")).is_err());
        assert!(decode_eidetic_message(req_body.replace("review|S-1|", "review|S-2|")).is_err());
        assert!(decode_eidetic_message(req_body.replace("\nresult|", "\nresults|")).is_err());
        // A pending review that is already decided, a result for another search or a bad digest is not sent.
        let mut bad_rr = rr.clone();
        bad_rr.review.reviewer = Some("REV-1".into());
        assert!(encode_eidetic_message(EideticMessage { request: Some(bad_rr), decision: None }).is_err());
        let mut bad_rr = rr.clone();
        bad_rr.result.search_id = "S-9".into();
        assert!(encode_eidetic_message(EideticMessage { request: Some(bad_rr), decision: None }).is_err());
        let mut bad_rr = rr;
        bad_rr.result.probe_digest = "not-a-digest".into();
        assert!(encode_eidetic_message(EideticMessage { request: Some(bad_rr), decision: None }).is_err());
    }

    #[test]
    fn eidetic_wire_refuses_biometric_payloads() {
        let rr = review_request();
        let vector = (0..128).map(|i| format!("0.{i}")).collect::<Vec<_>>().join(",");
        let mut smuggled = rr.clone();
        smuggled.result.evidence = vector.clone();
        assert!(encode_eidetic_message(EideticMessage { request: Some(smuggled), decision: None }).is_err());
        let mut smuggled = rr.clone();
        smuggled.request.legal_authority = "order 7 template=abc".into();
        assert!(encode_eidetic_message(EideticMessage { request: Some(smuggled), decision: None }).is_err());
        let body = encode_eidetic_message(EideticMessage { request: Some(rr.clone()), decision: None }).unwrap();
        let ev = e_esc(&rr.result.evidence);
        assert!(body.contains(&ev));
        assert!(decode_eidetic_message(body.replace(&ev, &vector)).is_err());
        assert!(decode_eidetic_message(body.replace(&ev, "data:image/png;base64,AAAA")).is_err());
        // An extra row (e.g. a smuggled vector line) is refused.
        let extra = body.replacen("\nreview|", &format!("\nvector|{vector}\nreview|"), 1);
        assert!(decode_eidetic_message(extra).is_err());
        let decided = eidetic_decide(rr.review, rr.result, "REV-1".into(), EideticRole::Reviewer, ReviewOutcome::Inconclusive, None, "".into(), 5).unwrap();
        let note = Review { note: format!("embedding {vector}"), ..decided };
        assert!(encode_eidetic_message(EideticMessage { request: None, decision: Some(note) }).is_err());
        assert!(decode_eidetic_message(format!("AETHER-EIDETIC/1\ndecision|S-1|OP-ALPHA|rejected|REV-1|5||{vector}")).is_err());
        assert!(decode_eidetic_message("x".repeat(MAX_EIDETIC_BODY + 1)).is_err());
    }
}
