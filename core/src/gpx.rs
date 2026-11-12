//! ÆTHER Maps™ data exchange: GPX 1.1 import and export, KML export, and position reports
//! shared end-to-end encrypted inside an ÆTHER Talk™ session (`MessageKind::Position`).
//!
//! GPX import accepts GPX 1.0 and 1.1 from any field device (waypoints, routes, tracks with
//! segments); unknown elements are skipped. Nothing here resolves entities or fetches anything.

use crate::nav::TrackPoint;
use crate::AetherError;
use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;

/// Largest GPX file accepted, to bound memory on a phone.
pub const MAX_GPX_BYTES: usize = 32 * 1024 * 1024;
const MAX_NAME_CHARS: usize = 128;
const MAX_NOTE_CHARS: usize = 2048;

/// A named point: a waypoint ("landmark"), or one point of a route.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Waypoint {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
    pub ele_m: Option<f64>,
    pub time_ms: Option<i64>,
    pub note: String,
    /// Map symbol name (GPX `<sym>`), e.g. "Flag", "Danger Area".
    pub symbol: String,
}

/// A continuous run of fixes. A track has a new segment each time recording resumes.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrackSegment {
    pub points: Vec<TrackPoint>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Track {
    pub name: String,
    pub note: String,
    pub segments: Vec<TrackSegment>,
}

/// A planned route: an ordered list of points to navigate through.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Route {
    pub name: String,
    pub note: String,
    pub points: Vec<Waypoint>,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct GpxDocument {
    pub waypoints: Vec<Waypoint>,
    pub routes: Vec<Route>,
    pub tracks: Vec<Track>,
}

fn bad(reason: &str) -> AetherError {
    AetherError::malformed(reason)
}

fn clip(s: &str, max: usize) -> String {
    s.trim().chars().filter(|c| !c.is_control() || *c == '\n').take(max).collect()
}

// ---- Time --------------------------------------------------------------------------------

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

/// Parses an ISO 8601 / RFC 3339 timestamp (`2026-11-12T08:30:00Z`, fractions and offsets allowed).
pub(crate) fn parse_time(s: &str) -> Option<i64> {
    let s = s.trim();
    let b = s.as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[7] != b'-' || !(b[10] == b'T' || b[10] == b' ') || b[13] != b':' || b[16] != b':' {
        return None;
    }
    let num = |r: std::ops::Range<usize>| s.get(r)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, se) = (num(0..4)?, num(5..7)?, num(8..10)?, num(11..13)?, num(14..16)?, num(17..19)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    let mut i = 19;
    let mut ms = 0i64;
    if b.get(i) == Some(&b'.') {
        i += 1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        let frac = &s[start..i];
        if frac.is_empty() {
            return None;
        }
        ms = format!("{:0<3}", &frac[..frac.len().min(3)]).parse().ok()?;
    }
    let offset_min = match &s[i..] {
        "" | "Z" | "z" => 0,
        tz if (tz.starts_with('+') || tz.starts_with('-')) && tz.len() == 6 && tz.as_bytes()[3] == b':' => {
            let sign = if tz.starts_with('-') { -1 } else { 1 };
            sign * (tz[1..3].parse::<i64>().ok()? * 60 + tz[4..6].parse::<i64>().ok()?)
        }
        _ => return None,
    };
    let secs = days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se - offset_min * 60;
    Some(secs * 1000 + ms)
}

/// Formats milliseconds since the epoch as RFC 3339 UTC (`2026-11-12T08:30:00.250Z`).
pub(crate) fn format_time(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let millis = ms.rem_euclid(1000);
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    let base = format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}", rem / 3600, rem % 3600 / 60, rem % 60);
    if millis == 0 { format!("{base}Z") } else { format!("{base}.{millis:03}Z") }
}

// ---- Import ------------------------------------------------------------------------------

#[derive(Default)]
struct PointDraft {
    lat: f64,
    lon: f64,
    ele: Option<f64>,
    time: Option<i64>,
    name: String,
    note: String,
    sym: String,
}

fn point_start(e: &BytesStart) -> Result<PointDraft, AetherError> {
    let (mut lat, mut lon) = (None, None);
    for a in e.attributes().flatten() {
        let v = a.unescape_value().map_err(|_| bad("bad GPX attribute"))?;
        match a.key.local_name().as_ref() {
            b"lat" => lat = v.trim().parse::<f64>().ok(),
            b"lon" => lon = v.trim().parse::<f64>().ok(),
            _ => {}
        }
    }
    match (lat, lon) {
        (Some(lat), Some(lon)) if (-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon) => {
            Ok(PointDraft { lat, lon, ..Default::default() })
        }
        _ => Err(bad("GPX point without a valid lat/lon")),
    }
}

impl PointDraft {
    fn waypoint(self) -> Waypoint {
        Waypoint {
            name: clip(&self.name, MAX_NAME_CHARS),
            lat: self.lat,
            lon: self.lon,
            ele_m: self.ele,
            time_ms: self.time,
            note: clip(&self.note, MAX_NOTE_CHARS),
            symbol: clip(&self.sym, MAX_NAME_CHARS),
        }
    }
    fn track_point(self) -> TrackPoint {
        TrackPoint { lat: self.lat, lon: self.lon, ele_m: self.ele, time_ms: self.time }
    }
}

/// Parses a GPX 1.0 or 1.1 document.
#[uniffi::export]
pub fn parse_gpx(xml: String) -> Result<GpxDocument, AetherError> {
    if xml.len() > MAX_GPX_BYTES {
        return Err(bad("GPX file too large"));
    }
    let mut reader = Reader::from_str(&xml);
    reader.config_mut().trim_text(true);
    let mut doc = GpxDocument::default();
    let mut saw_root = false;
    // Element path, so <name> is attributed to the right parent.
    let mut path: Vec<Vec<u8>> = Vec::new();
    let mut point: Option<PointDraft> = None;
    let mut route: Option<Route> = None;
    let mut track: Option<Track> = None;
    let mut segment: Option<TrackSegment> = None;
    let mut text = String::new();
    loop {
        let ev = reader.read_event().map_err(|_| bad("GPX is not well-formed XML"))?;
        match ev {
            Event::Start(ref e) | Event::Empty(ref e) => {
                let empty = matches!(ev, Event::Empty(_));
                let name = e.local_name().as_ref().to_vec();
                match name.as_slice() {
                    b"gpx" => saw_root = true,
                    b"wpt" | b"rtept" | b"trkpt" => point = Some(point_start(e)?),
                    b"rte" => route = Some(Route { name: String::new(), note: String::new(), points: vec![] }),
                    b"trk" => track = Some(Track { name: String::new(), note: String::new(), segments: vec![] }),
                    b"trkseg" => segment = Some(TrackSegment { points: vec![] }),
                    _ => {}
                }
                text.clear();
                if empty {
                    close_element(&name, &mut doc, &mut point, &mut route, &mut track, &mut segment, &path, "");
                } else {
                    path.push(name);
                }
            }
            Event::Text(t) => text.push_str(&t.unescape().map_err(|_| bad("bad GPX text"))?),
            Event::CData(t) => text.push_str(&String::from_utf8_lossy(&t.into_inner())),
            Event::End(e) => {
                let name = e.local_name().as_ref().to_vec();
                path.pop();
                let body = std::mem::take(&mut text);
                close_element(&name, &mut doc, &mut point, &mut route, &mut track, &mut segment, &path, &body);
            }
            Event::Eof => break,
            _ => {}
        }
    }
    if !saw_root {
        return Err(bad("not a GPX document"));
    }
    Ok(doc)
}

#[allow(clippy::too_many_arguments)]
fn close_element(
    name: &[u8],
    doc: &mut GpxDocument,
    point: &mut Option<PointDraft>,
    route: &mut Option<Route>,
    track: &mut Option<Track>,
    segment: &mut Option<TrackSegment>,
    parents: &[Vec<u8>],
    body: &str,
) {
    let parent = parents.last().map(Vec::as_slice);
    match name {
        b"wpt" => {
            if let Some(p) = point.take() {
                doc.waypoints.push(p.waypoint());
            }
        }
        b"rtept" => {
            if let (Some(p), Some(r)) = (point.take(), route.as_mut()) {
                r.points.push(p.waypoint());
            }
        }
        b"trkpt" => {
            if let Some(p) = point.take() {
                match (segment.as_mut(), track.as_mut()) {
                    (Some(s), _) => s.points.push(p.track_point()),
                    // GPX 1.0 files sometimes omit <trkseg>.
                    (None, Some(t)) => match t.segments.last_mut() {
                        Some(s) => s.points.push(p.track_point()),
                        None => t.segments.push(TrackSegment { points: vec![p.track_point()] }),
                    },
                    _ => {}
                }
            }
        }
        b"trkseg" => {
            if let (Some(s), Some(t)) = (segment.take(), track.as_mut()) {
                if !s.points.is_empty() {
                    t.segments.push(s);
                }
            }
        }
        b"rte" => {
            if let Some(r) = route.take() {
                doc.routes.push(r);
            }
        }
        b"trk" => {
            if let Some(t) = track.take() {
                doc.tracks.push(t);
            }
        }
        b"ele" => {
            if let Some(p) = point.as_mut() {
                p.ele = body.trim().parse::<f64>().ok().filter(|e| e.is_finite());
            }
        }
        b"time" => {
            if let Some(p) = point.as_mut() {
                p.time = parse_time(body);
            }
        }
        b"name" | b"desc" | b"cmt" | b"sym" => {
            let field = name;
            match (parent, point.as_mut()) {
                (Some(b"wpt" | b"rtept" | b"trkpt"), Some(p)) => match field {
                    b"name" => p.name = body.to_owned(),
                    b"sym" => p.sym = body.to_owned(),
                    _ if p.note.is_empty() => p.note = body.to_owned(),
                    _ => {}
                },
                (Some(b"rte"), _) => {
                    if let Some(r) = route.as_mut() {
                        match field {
                            b"name" => r.name = clip(body, MAX_NAME_CHARS),
                            b"desc" | b"cmt" if r.note.is_empty() => r.note = clip(body, MAX_NOTE_CHARS),
                            _ => {}
                        }
                    }
                }
                (Some(b"trk"), _) => {
                    if let Some(t) = track.as_mut() {
                        match field {
                            b"name" => t.name = clip(body, MAX_NAME_CHARS),
                            b"desc" | b"cmt" if t.note.is_empty() => t.note = clip(body, MAX_NOTE_CHARS),
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        _ => {}
    }
}

// ---- Export ------------------------------------------------------------------------------

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            '\'' => o.push_str("&apos;"),
            c if c.is_control() && c != '\n' && c != '\t' => {}
            c => o.push(c),
        }
    }
    o
}

fn push_point(out: &mut String, tag: &str, lat: f64, lon: f64, ele: Option<f64>, time: Option<i64>, w: Option<&Waypoint>, indent: &str) {
    out.push_str(&format!("{indent}<{tag} lat=\"{lat:.7}\" lon=\"{lon:.7}\">"));
    if let Some(e) = ele {
        out.push_str(&format!("<ele>{e:.1}</ele>"));
    }
    if let Some(t) = time {
        out.push_str(&format!("<time>{}</time>", format_time(t)));
    }
    if let Some(w) = w {
        if !w.name.is_empty() {
            out.push_str(&format!("<name>{}</name>", esc(&w.name)));
        }
        if !w.note.is_empty() {
            out.push_str(&format!("<desc>{}</desc>", esc(&w.note)));
        }
        if !w.symbol.is_empty() {
            out.push_str(&format!("<sym>{}</sym>", esc(&w.symbol)));
        }
    }
    out.push_str(&format!("</{tag}>\n"));
}

/// Writes a GPX 1.1 document.
#[uniffi::export]
pub fn write_gpx(doc: GpxDocument) -> String {
    let mut o = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"ÆTHER Maps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
    );
    for w in &doc.waypoints {
        push_point(&mut o, "wpt", w.lat, w.lon, w.ele_m, w.time_ms, Some(w), "  ");
    }
    for r in &doc.routes {
        o.push_str(&format!("  <rte><name>{}</name>", esc(&r.name)));
        if !r.note.is_empty() {
            o.push_str(&format!("<desc>{}</desc>", esc(&r.note)));
        }
        o.push('\n');
        for w in &r.points {
            push_point(&mut o, "rtept", w.lat, w.lon, w.ele_m, w.time_ms, Some(w), "    ");
        }
        o.push_str("  </rte>\n");
    }
    for t in &doc.tracks {
        o.push_str(&format!("  <trk><name>{}</name>", esc(&t.name)));
        if !t.note.is_empty() {
            o.push_str(&format!("<desc>{}</desc>", esc(&t.note)));
        }
        o.push('\n');
        for s in &t.segments {
            o.push_str("    <trkseg>\n");
            for p in &s.points {
                push_point(&mut o, "trkpt", p.lat, p.lon, p.ele_m, p.time_ms, None, "      ");
            }
            o.push_str("    </trkseg>\n");
        }
        o.push_str("  </trk>\n");
    }
    o.push_str("</gpx>\n");
    o
}

fn kml_coord(lat: f64, lon: f64, ele: Option<f64>) -> String {
    match ele {
        Some(e) => format!("{lon:.7},{lat:.7},{e:.1}"),
        None => format!("{lon:.7},{lat:.7}"),
    }
}

/// Writes a KML 2.2 document (waypoints as placemarks, routes and track segments as lines).
#[uniffi::export]
pub fn write_kml(doc: GpxDocument, title: String) -> String {
    let mut o = format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document><name>{}</name>\n",
        esc(&title)
    );
    for w in &doc.waypoints {
        o.push_str(&format!(
            "  <Placemark><name>{}</name><description>{}</description><Point><coordinates>{}</coordinates></Point></Placemark>\n",
            esc(&w.name),
            esc(&w.note),
            kml_coord(w.lat, w.lon, w.ele_m)
        ));
    }
    let line = |name: &str, coords: Vec<String>| {
        format!(
            "  <Placemark><name>{}</name><LineString><tessellate>1</tessellate><coordinates>{}</coordinates></LineString></Placemark>\n",
            esc(name),
            coords.join(" ")
        )
    };
    for r in &doc.routes {
        o.push_str(&line(&r.name, r.points.iter().map(|w| kml_coord(w.lat, w.lon, w.ele_m)).collect()));
    }
    for t in &doc.tracks {
        for s in &t.segments {
            o.push_str(&line(&t.name, s.points.iter().map(|p| kml_coord(p.lat, p.lon, p.ele_m)).collect()));
        }
    }
    o.push_str("</Document>\n</kml>\n");
    o
}

// ---- Position reports over ÆTHER Talk™ ------------------------------------------------------

const POS_MAGIC: &str = "AETHER-POS/1";

/// What a shared position means.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PositionKind {
    /// The sender's own GPS fix ("my position").
    OwnFix,
    /// A marked point: rally point, objective, hazard, landing zone.
    Marker,
}

/// A position shared with a teammate, end-to-end encrypted inside a session.
///
/// Body layout (UTF-8, newline separated): `AETHER-POS/1 \n own|marker \n label \n lat \n lon \n
/// ele \n accuracy \n time_ms \n symbol` (empty fields for absent values).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PositionReport {
    pub kind: PositionKind,
    pub label: String,
    pub lat: f64,
    pub lon: f64,
    pub ele_m: Option<f64>,
    pub accuracy_m: Option<f64>,
    pub time_ms: i64,
    pub symbol: String,
}

fn validate_report(r: &PositionReport) -> Result<PositionReport, AetherError> {
    if !r.lat.is_finite() || !r.lon.is_finite() || !(-90.0..=90.0).contains(&r.lat) || !(-180.0..=180.0).contains(&r.lon) {
        return Err(bad("position out of range"));
    }
    let label = r.label.trim();
    if label.chars().count() > MAX_NAME_CHARS || label.chars().any(char::is_control) {
        return Err(bad("label must be at most 128 characters on one line"));
    }
    let symbol = r.symbol.trim();
    if symbol.chars().count() > 32 || symbol.chars().any(char::is_control) {
        return Err(bad("symbol must be at most 32 characters"));
    }
    if r.ele_m.is_some_and(|e| !e.is_finite()) || r.accuracy_m.is_some_and(|a| !a.is_finite() || a < 0.0) {
        return Err(bad("bad elevation or accuracy"));
    }
    if r.time_ms < 0 {
        return Err(bad("bad time"));
    }
    Ok(PositionReport { label: label.to_owned(), symbol: symbol.to_owned(), ..r.clone() })
}

/// Validates a report and encodes it as a `Position` message body.
#[uniffi::export]
pub fn encode_position(report: PositionReport) -> Result<String, AetherError> {
    let r = validate_report(&report)?;
    let opt = |v: Option<f64>, p: usize| v.map(|x| format!("{x:.p$}")).unwrap_or_default();
    Ok([
        POS_MAGIC.to_owned(),
        match r.kind { PositionKind::OwnFix => "own", PositionKind::Marker => "marker" }.to_owned(),
        r.label,
        format!("{:.7}", r.lat),
        format!("{:.7}", r.lon),
        opt(r.ele_m, 1),
        opt(r.accuracy_m, 1),
        r.time_ms.to_string(),
        r.symbol,
    ]
    .join("\n"))
}

/// Parses and validates a `Position` message body received from a peer.
#[uniffi::export]
pub fn decode_position(body: String) -> Result<PositionReport, AetherError> {
    let parts: Vec<&str> = body.split('\n').collect();
    if parts.len() != 9 || parts[0] != POS_MAGIC {
        return Err(bad("not a position report"));
    }
    let kind = match parts[1] {
        "own" => PositionKind::OwnFix,
        "marker" => PositionKind::Marker,
        _ => return Err(bad("unknown position kind")),
    };
    let num = |s: &str| s.parse::<f64>().map_err(|_| bad("bad number in position"));
    let opt = |s: &str| if s.is_empty() { Ok(None) } else { num(s).map(Some) };
    validate_report(&PositionReport {
        kind,
        label: parts[2].to_owned(),
        lat: num(parts[3])?,
        lon: num(parts[4])?,
        ele_m: opt(parts[5])?,
        accuracy_m: opt(parts[6])?,
        time_ms: parts[7].parse().map_err(|_| bad("bad time in position"))?,
        symbol: parts[8].to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0"?>
<gpx version="1.1" creator="field" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="46.5" lon="7.9"><ele>2100</ele><name>RP &amp; Alpha</name><desc>rally</desc><sym>Flag</sym></wpt>
  <wpt lat="46.6" lon="7.95"/>
  <rte><name>Route 1</name><rtept lat="46.5" lon="7.9"><name>SP</name></rtept><rtept lat="46.51" lon="7.91"/></rte>
  <trk><name>Patrol</name>
    <trkseg>
      <trkpt lat="46.50" lon="7.90"><ele>2100.5</ele><time>2026-11-12T08:00:00Z</time></trkpt>
      <trkpt lat="46.501" lon="7.901"><ele>2104</ele><time>2026-11-12T08:01:00.5Z</time></trkpt>
    </trkseg>
    <trkseg><trkpt lat="46.6" lon="7.95"/></trkseg>
    <trkseg></trkseg>
  </trk>
</gpx>"#;

    #[test]
    fn parses_gpx() {
        let d = parse_gpx(SAMPLE.into()).unwrap();
        assert_eq!(d.waypoints.len(), 2);
        let w = &d.waypoints[0];
        assert_eq!((w.name.as_str(), w.note.as_str(), w.symbol.as_str(), w.ele_m), ("RP & Alpha", "rally", "Flag", Some(2100.0)));
        assert_eq!(d.waypoints[1].name, "");
        assert_eq!(d.routes.len(), 1);
        assert_eq!(d.routes[0].name, "Route 1");
        assert_eq!(d.routes[0].points.len(), 2);
        assert_eq!(d.routes[0].points[0].name, "SP");
        assert_eq!(d.tracks.len(), 1);
        let t = &d.tracks[0];
        assert_eq!((t.name.as_str(), t.segments.len()), ("Patrol", 2));
        assert_eq!(t.segments[0].points[0].time_ms, Some(1_794_470_400_000));
        assert_eq!(t.segments[0].points[1].time_ms, Some(1_794_470_460_500));
    }

    #[test]
    fn gpx_round_trip() {
        let d = parse_gpx(SAMPLE.into()).unwrap();
        let back = parse_gpx(write_gpx(d.clone())).unwrap();
        assert_eq!(back, d);
        let kml = write_kml(d, "Op <Test>".into());
        assert!(kml.contains("<name>Op &lt;Test&gt;</name>") && kml.contains("7.9000000,46.5000000,2100.0"));
        assert_eq!(kml.matches("<LineString>").count(), 3);
    }

    #[test]
    fn rejects_bad_gpx() {
        assert!(parse_gpx("<kml/>".into()).is_err());
        assert!(parse_gpx("<gpx><wpt lat=\"91\" lon=\"0\"/></gpx>".into()).is_err());
        assert!(parse_gpx("<gpx><wpt lat=\"x\" lon=\"0\"/></gpx>".into()).is_err());
        assert!(parse_gpx("<gpx><wpt lat=\"1\" lon=\"0\"></gpx>".into()).is_err());
        // GPX 1.0 track without <trkseg>.
        let d = parse_gpx("<gpx><trk><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1\" lon=\"3\"/></trk></gpx>".into()).unwrap();
        assert_eq!(d.tracks[0].segments[0].points.len(), 2);
    }

    #[test]
    fn times() {
        assert_eq!(parse_time("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_time("2026-11-12T10:00:00+02:00"), parse_time("2026-11-12T08:00:00Z"));
        assert_eq!(parse_time("2026-11-12T08:00:00.123456Z"), Some(1_794_470_400_123));
        assert_eq!(parse_time("2026-13-12T08:00:00Z"), None);
        assert_eq!(parse_time("yesterday"), None);
        for ms in [0, 1_794_470_400_000, 1_794_470_460_500, 951_782_400_000 /* 2000-02-29 */] {
            assert_eq!(parse_time(&format_time(ms)), Some(ms));
        }
        assert_eq!(format_time(951_782_400_000), "2000-02-29T00:00:00Z");
    }

    fn report() -> PositionReport {
        PositionReport {
            kind: PositionKind::Marker,
            label: "LZ Falcon".into(),
            lat: 46.512_345_6,
            lon: 7.912_345_6,
            ele_m: Some(2100.5),
            accuracy_m: None,
            time_ms: 1_794_470_400_000,
            symbol: "LZ".into(),
        }
    }

    #[test]
    fn position_round_trip() {
        let body = encode_position(report()).unwrap();
        assert_eq!(decode_position(body).unwrap(), report());
        let own = PositionReport { kind: PositionKind::OwnFix, label: "".into(), accuracy_m: Some(4.0), ele_m: None, symbol: "".into(), ..report() };
        assert_eq!(decode_position(encode_position(own.clone()).unwrap()).unwrap(), own);
    }

    #[test]
    fn rejects_bad_positions() {
        assert!(encode_position(PositionReport { lat: 91.0, ..report() }).is_err());
        assert!(encode_position(PositionReport { label: "a\nb".into(), ..report() }).is_err());
        assert!(encode_position(PositionReport { accuracy_m: Some(-1.0), ..report() }).is_err());
        assert!(decode_position("AETHER-POS/2\nown\n\n1\n2\n\n\n0\n".into()).is_err());
        assert!(decode_position("AETHER-POS/1\nfoe\n\n1\n2\n\n\n0\n".into()).is_err());
        assert!(decode_position("hello".into()).is_err());
    }
}
