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
}
