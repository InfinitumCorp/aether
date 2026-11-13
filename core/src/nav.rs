//! ÆTHER Maps™ navigation core: the offline-GPS toolkit shared by every client.
//!
//! * Coordinates: decimal degrees, DMS, DDM, UTM and MGRS (WGS 84), formatted and parsed.
//! * Geodesy on the WGS 84 ellipsoid: distance, initial/final bearing (Vincenty inverse),
//!   and projecting a point by bearing and distance (Vincenty direct).
//! * Measurement: path length, polygon area and perimeter.
//! * Tracks: statistics (distance, ascent/descent with a noise threshold, moving time,
//!   speeds), Douglas–Peucker simplification and an elevation profile.
//! * Web Mercator tile math for offline raster map packs (MBTiles, XYZ).
//!
//! Everything is pure computation: no I/O, no network, no clock.

use crate::AetherError;

const WGS84_A: f64 = 6_378_137.0;
const WGS84_F: f64 = 1.0 / 298.257_223_563;
const WGS84_B: f64 = WGS84_A * (1.0 - WGS84_F);
/// Mean Earth radius (IUGG), for spherical approximations.
const MEAN_RADIUS: f64 = 6_371_008.8;
const UTM_K0: f64 = 0.9996;
const MGRS_BANDS: &[u8; 20] = b"CDEFGHJKLMNPQRSTUVWX";
const MGRS_ROWS: &[u8; 20] = b"ABCDEFGHJKLMNPQRSTUV";
const MGRS_COLS: [&[u8; 8]; 3] = [b"ABCDEFGH", b"JKLMNPQR", b"STUVWXYZ"];

/// A position on the WGS 84 ellipsoid, in decimal degrees.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct GeoPoint {
    pub lat: f64,
    pub lon: f64,
}

/// How a coordinate is shown to the operator.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CoordFormat {
    /// `38.889480, -77.035250`
    Decimal,
    /// `38°53'22.1"N 77°02'06.9"W`
    Dms,
    /// `38°53.3688'N 77°02.1150'W`
    Ddm,
    /// `18S 323484E 4306480N`
    Utm,
    /// `18S UJ 23484 06480` (1 m precision)
    Mgrs,
}

/// A UTM grid position. `band` is the MGRS latitude band letter (C–X); N and above are north.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct UtmCoordinate {
    pub zone: u8,
    pub band: String,
    pub easting: f64,
    pub northing: f64,
}

/// Result of the geodesic inverse problem between two points.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Geodesic {
    pub distance_m: f64,
    /// Bearing at the start, degrees clockwise from true north, 0–360.
    pub initial_bearing_deg: f64,
    /// Bearing on arrival, degrees clockwise from true north, 0–360.
    pub final_bearing_deg: f64,
}

/// One recorded GPS fix.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrackPoint {
    pub lat: f64,
    pub lon: f64,
    pub ele_m: Option<f64>,
    pub time_ms: Option<i64>,
}

/// Summary of a recorded track.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrackStats {
    pub points: u32,
    pub distance_m: f64,
    pub ascent_m: f64,
    pub descent_m: f64,
    pub min_ele_m: Option<f64>,
    pub max_ele_m: Option<f64>,
    pub duration_ms: Option<i64>,
    pub moving_ms: Option<i64>,
    pub max_speed_mps: Option<f64>,
    /// Average speed over moving time only.
    pub moving_speed_mps: Option<f64>,
}

/// One sample of an elevation profile.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ProfilePoint {
    pub distance_m: f64,
    pub ele_m: f64,
}

/// A Web Mercator (XYZ / slippy-map) tile address. MBTiles stores rows flipped (TMS); see [`tms_row`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct TileAddress {
    pub zoom: u8,
    pub x: u32,
    pub y: u32,
}

fn bad(reason: &str) -> AetherError {
    AetherError::malformed(reason)
}

fn check_point(lat: f64, lon: f64) -> Result<(), AetherError> {
    if !lat.is_finite() || !lon.is_finite() || !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) {
        return Err(bad("coordinate out of range"));
    }
    Ok(())
}

fn norm_deg(d: f64) -> f64 {
    let r = d.rem_euclid(360.0);
    if r >= 360.0 { 0.0 } else { r }
}

fn norm_lon(lon: f64) -> f64 {
    let l = (lon + 180.0).rem_euclid(360.0) - 180.0;
    if l == -180.0 && lon > 0.0 { 180.0 } else { l }
}

// ---- Geodesy ------------------------------------------------------------------------------

/// Geodesic distance and bearings between two points (Vincenty inverse on WGS 84).
/// Falls back to a great-circle answer for nearly antipodal points where Vincenty does not converge.
#[uniffi::export]
pub fn geodesic_inverse(from: GeoPoint, to: GeoPoint) -> Result<Geodesic, AetherError> {
    check_point(from.lat, from.lon)?;
    check_point(to.lat, to.lon)?;
    Ok(inverse(from, to))
}

fn inverse(from: GeoPoint, to: GeoPoint) -> Geodesic {
    let (a, b, f) = (WGS84_A, WGS84_B, WGS84_F);
    let l = (to.lon - from.lon).to_radians();
    let u1 = ((1.0 - f) * from.lat.to_radians().tan()).atan();
    let u2 = ((1.0 - f) * to.lat.to_radians().tan()).atan();
    let (s1, c1, s2, c2) = (u1.sin(), u1.cos(), u2.sin(), u2.cos());
    let mut lambda = l;
    for _ in 0..200 {
        let (sl, cl) = (lambda.sin(), lambda.cos());
        let sin_sigma = ((c2 * sl).powi(2) + (c1 * s2 - s1 * c2 * cl).powi(2)).sqrt();
        if sin_sigma == 0.0 {
            return Geodesic { distance_m: 0.0, initial_bearing_deg: 0.0, final_bearing_deg: 0.0 };
        }
        let cos_sigma = s1 * s2 + c1 * c2 * cl;
        let sigma = sin_sigma.atan2(cos_sigma);
        let sin_alpha = c1 * c2 * sl / sin_sigma;
        let cos2_alpha = 1.0 - sin_alpha * sin_alpha;
        let cos_2sm = if cos2_alpha != 0.0 { cos_sigma - 2.0 * s1 * s2 / cos2_alpha } else { 0.0 };
        let cc = f / 16.0 * cos2_alpha * (4.0 + f * (4.0 - 3.0 * cos2_alpha));
        let prev = lambda;
        lambda = l + (1.0 - cc) * f * sin_alpha
            * (sigma + cc * sin_sigma * (cos_2sm + cc * cos_sigma * (-1.0 + 2.0 * cos_2sm * cos_2sm)));
        if (lambda - prev).abs() < 1e-12 {
            let u_sq = cos2_alpha * (a * a - b * b) / (b * b);
            let aa = 1.0 + u_sq / 16384.0 * (4096.0 + u_sq * (-768.0 + u_sq * (320.0 - 175.0 * u_sq)));
            let bb = u_sq / 1024.0 * (256.0 + u_sq * (-128.0 + u_sq * (74.0 - 47.0 * u_sq)));
            let d_sigma = bb * sin_sigma
                * (cos_2sm + bb / 4.0
                    * (cos_sigma * (-1.0 + 2.0 * cos_2sm * cos_2sm)
                        - bb / 6.0 * cos_2sm * (-3.0 + 4.0 * sin_sigma * sin_sigma) * (-3.0 + 4.0 * cos_2sm * cos_2sm)));
            let (sl, cl) = (lambda.sin(), lambda.cos());
            let a1 = (c2 * sl).atan2(c1 * s2 - s1 * c2 * cl);
            let a2 = (c1 * sl).atan2(-s1 * c2 + c1 * s2 * cl);
            return Geodesic {
                distance_m: b * aa * (sigma - d_sigma),
                initial_bearing_deg: norm_deg(a1.to_degrees()),
                final_bearing_deg: norm_deg(a2.to_degrees()),
            };
        }
    }
    spherical_inverse(from, to)
}

fn spherical_inverse(from: GeoPoint, to: GeoPoint) -> Geodesic {
    let (p1, p2) = (from.lat.to_radians(), to.lat.to_radians());
    let dl = (to.lon - from.lon).to_radians();
    let h = ((p2 - p1) / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    let d = 2.0 * MEAN_RADIUS * h.sqrt().min(1.0).asin();
    let brg = |pa: f64, pb: f64, dl: f64| (dl.sin() * pb.cos()).atan2(pa.cos() * pb.sin() - pa.sin() * pb.cos() * dl.cos());
    Geodesic {
        distance_m: d,
        initial_bearing_deg: norm_deg(brg(p1, p2, dl).to_degrees()),
        final_bearing_deg: norm_deg(brg(p2, p1, -dl).to_degrees() + 180.0),
    }
}

/// The point reached by travelling `distance_m` from `from` on initial bearing `bearing_deg`
/// (Vincenty direct on WGS 84). This is "project a waypoint" in the field.
#[uniffi::export]
pub fn geodesic_destination(from: GeoPoint, bearing_deg: f64, distance_m: f64) -> Result<GeoPoint, AetherError> {
    check_point(from.lat, from.lon)?;
    if !bearing_deg.is_finite() || !distance_m.is_finite() || distance_m < 0.0 || distance_m > 20_000_000.0 {
        return Err(bad("bearing or distance out of range"));
    }
    let (a, b, f) = (WGS84_A, WGS84_B, WGS84_F);
    let alpha1 = bearing_deg.to_radians();
    let (sa1, ca1) = (alpha1.sin(), alpha1.cos());
    let tan_u1 = (1.0 - f) * from.lat.to_radians().tan();
    let cos_u1 = 1.0 / (1.0 + tan_u1 * tan_u1).sqrt();
    let sin_u1 = tan_u1 * cos_u1;
    let sigma1 = tan_u1.atan2(ca1);
    let sin_alpha = cos_u1 * sa1;
    let cos2_alpha = 1.0 - sin_alpha * sin_alpha;
    let u_sq = cos2_alpha * (a * a - b * b) / (b * b);
    let aa = 1.0 + u_sq / 16384.0 * (4096.0 + u_sq * (-768.0 + u_sq * (320.0 - 175.0 * u_sq)));
    let bb = u_sq / 1024.0 * (256.0 + u_sq * (-128.0 + u_sq * (74.0 - 47.0 * u_sq)));
    let mut sigma = distance_m / (b * aa);
    let (mut sin_sigma, mut cos_sigma, mut cos_2sm);
    let mut iter = 0;
    loop {
        cos_2sm = (2.0 * sigma1 + sigma).cos();
        sin_sigma = sigma.sin();
        cos_sigma = sigma.cos();
        let d_sigma = bb * sin_sigma
            * (cos_2sm + bb / 4.0
                * (cos_sigma * (-1.0 + 2.0 * cos_2sm * cos_2sm)
                    - bb / 6.0 * cos_2sm * (-3.0 + 4.0 * sin_sigma * sin_sigma) * (-3.0 + 4.0 * cos_2sm * cos_2sm)));
        let prev = sigma;
        sigma = distance_m / (b * aa) + d_sigma;
        iter += 1;
        if (sigma - prev).abs() < 1e-12 || iter > 200 {
            break;
        }
    }
    let x = sin_u1 * sin_sigma - cos_u1 * cos_sigma * ca1;
    let lat = (sin_u1 * cos_sigma + cos_u1 * sin_sigma * ca1).atan2((1.0 - f) * (sin_alpha * sin_alpha + x * x).sqrt());
    let lambda = (sin_sigma * sa1).atan2(cos_u1 * cos_sigma - sin_u1 * sin_sigma * ca1);
    let cc = f / 16.0 * cos2_alpha * (4.0 + f * (4.0 - 3.0 * cos2_alpha));
    let l = lambda - (1.0 - cc) * f * sin_alpha
        * (sigma + cc * sin_sigma * (cos_2sm + cc * cos_sigma * (-1.0 + 2.0 * cos_2sm * cos_2sm)));
    Ok(GeoPoint { lat: lat.to_degrees(), lon: norm_lon(from.lon + l.to_degrees()) })
}

/// Total geodesic length of a path through `points`, in metres.
#[uniffi::export]
pub fn path_length(points: Vec<GeoPoint>) -> Result<f64, AetherError> {
    for p in &points {
        check_point(p.lat, p.lon)?;
    }
    Ok(points.windows(2).map(|w| inverse(w[0], w[1]).distance_m).sum())
}

/// Area of the polygon whose vertices are `points` (closed implicitly), in square metres.
/// Spherical-excess on the mean Earth sphere: within about 0.5% of the ellipsoidal area.
#[uniffi::export]
pub fn polygon_area(points: Vec<GeoPoint>) -> Result<f64, AetherError> {
    for p in &points {
        check_point(p.lat, p.lon)?;
    }
    if points.len() < 3 {
        return Ok(0.0);
    }
    let n = points.len();
    let mut sum = 0.0;
    for i in 0..n {
        let (p1, p2) = (points[i], points[(i + 1) % n]);
        let mut dl = (p2.lon - p1.lon).to_radians();
        if dl > std::f64::consts::PI {
            dl -= 2.0 * std::f64::consts::PI;
        } else if dl < -std::f64::consts::PI {
            dl += 2.0 * std::f64::consts::PI;
        }
        sum += dl * (2.0 + p1.lat.to_radians().sin() + p2.lat.to_radians().sin());
    }
    Ok((sum * MEAN_RADIUS * MEAN_RADIUS / 2.0).abs())
}

/// Perimeter of the closed polygon `points`, in metres.
#[uniffi::export]
pub fn polygon_perimeter(points: Vec<GeoPoint>) -> Result<f64, AetherError> {
    let mut closed = points.clone();
    if let Some(first) = points.first() {
        closed.push(*first);
    }
    path_length(closed)
}

// ---- Tracks -------------------------------------------------------------------------------

/// Elevation changes smaller than this are treated as GPS noise when summing ascent/descent.
const ELEVATION_NOISE_M: f64 = 3.0;
/// Below this speed the operator counts as stopped for moving time.
const MOVING_SPEED_MPS: f64 = 0.5;
/// Fixes implying more than this speed are glitches and are left out of max speed.
const MAX_PLAUSIBLE_MPS: f64 = 340.0;

/// Distance, climb, time and speed for a recorded track.
#[uniffi::export]
pub fn track_stats(points: Vec<TrackPoint>) -> Result<TrackStats, AetherError> {
    for p in &points {
        check_point(p.lat, p.lon)?;
    }
    let mut distance = 0.0;
    let mut moving_ms: i64 = 0;
    let mut timed = false;
    let mut max_speed: Option<f64> = None;
    for w in points.windows(2) {
        let d = inverse(GeoPoint { lat: w[0].lat, lon: w[0].lon }, GeoPoint { lat: w[1].lat, lon: w[1].lon }).distance_m;
        distance += d;
        if let (Some(t0), Some(t1)) = (w[0].time_ms, w[1].time_ms) {
            let dt = t1 - t0;
            if dt > 0 {
                timed = true;
                let v = d / (dt as f64 / 1000.0);
                if v >= MOVING_SPEED_MPS {
                    moving_ms += dt;
                }
                if v <= MAX_PLAUSIBLE_MPS {
                    max_speed = Some(max_speed.map_or(v, |m: f64| m.max(v)));
                }
            }
        }
    }
    let (mut ascent, mut descent) = (0.0, 0.0);
    let elevations: Vec<f64> = points.iter().filter_map(|p| p.ele_m).filter(|e| e.is_finite()).collect();
    if let Some(&first) = elevations.first() {
        let mut anchor = first;
        for &e in &elevations[1..] {
            let delta = e - anchor;
            if delta >= ELEVATION_NOISE_M {
                ascent += delta;
                anchor = e;
            } else if delta <= -ELEVATION_NOISE_M {
                descent -= delta;
                anchor = e;
            }
        }
    }
    let times: Vec<i64> = points.iter().filter_map(|p| p.time_ms).collect();
    let duration = match (times.first(), times.last()) {
        (Some(a), Some(b)) if times.len() > 1 => Some(b - a),
        _ => None,
    };
    let moving = timed.then_some(moving_ms);
    let moving_distance = distance;
    Ok(TrackStats {
        points: points.len() as u32,
        distance_m: distance,
        ascent_m: ascent,
        descent_m: descent,
        min_ele_m: elevations.iter().copied().reduce(f64::min),
        max_ele_m: elevations.iter().copied().reduce(f64::max),
        duration_ms: duration,
        moving_ms: moving,
        max_speed_mps: max_speed,
        moving_speed_mps: moving.filter(|&m| m > 0).map(|m| moving_distance / (m as f64 / 1000.0)),
    })
}

/// Cumulative distance against elevation, for points that carry an elevation.
#[uniffi::export]
pub fn elevation_profile(points: Vec<TrackPoint>) -> Result<Vec<ProfilePoint>, AetherError> {
    for p in &points {
        check_point(p.lat, p.lon)?;
    }
    let mut out = Vec::new();
    let mut dist = 0.0;
    for (i, p) in points.iter().enumerate() {
        if i > 0 {
            let q = &points[i - 1];
            dist += inverse(GeoPoint { lat: q.lat, lon: q.lon }, GeoPoint { lat: p.lat, lon: p.lon }).distance_m;
        }
        if let Some(e) = p.ele_m.filter(|e| e.is_finite()) {
            out.push(ProfilePoint { distance_m: dist, ele_m: e });
        }
    }
    Ok(out)
}

/// Douglas–Peucker simplification: drops points that lie within `tolerance_m` of the line
/// between their neighbours. First and last points are always kept.
#[uniffi::export]
pub fn simplify_track(points: Vec<TrackPoint>, tolerance_m: f64) -> Result<Vec<TrackPoint>, AetherError> {
    for p in &points {
        check_point(p.lat, p.lon)?;
    }
    if !tolerance_m.is_finite() || tolerance_m < 0.0 {
        return Err(bad("tolerance must be a non-negative number"));
    }
    if points.len() < 3 {
        return Ok(points);
    }
    // Local equirectangular projection around the mean latitude: fine for track-scale tolerances.
    let lat0 = (points.iter().map(|p| p.lat).sum::<f64>() / points.len() as f64).to_radians();
    let xy: Vec<(f64, f64)> = points
        .iter()
        .map(|p| (p.lon.to_radians() * lat0.cos() * MEAN_RADIUS, p.lat.to_radians() * MEAN_RADIUS))
        .collect();
    let mut keep = vec![false; points.len()];
    keep[0] = true;
    *keep.last_mut().unwrap() = true;
    let mut stack = vec![(0usize, points.len() - 1)];
    while let Some((s, e)) = stack.pop() {
        if e <= s + 1 {
            continue;
        }
        let (ax, ay) = xy[s];
        let (bx, by) = xy[e];
        let (dx, dy) = (bx - ax, by - ay);
        let len2 = dx * dx + dy * dy;
        let (mut best, mut best_i) = (-1.0, s);
        for (i, &(px, py)) in xy.iter().enumerate().take(e).skip(s + 1) {
            let d = if len2 == 0.0 {
                ((px - ax).powi(2) + (py - ay).powi(2)).sqrt()
            } else {
                let t = (((px - ax) * dx + (py - ay) * dy) / len2).clamp(0.0, 1.0);
                ((px - ax - t * dx).powi(2) + (py - ay - t * dy).powi(2)).sqrt()
            };
            if d > best {
                best = d;
                best_i = i;
            }
        }
        if best > tolerance_m {
            keep[best_i] = true;
            stack.push((s, best_i));
            stack.push((best_i, e));
        }
    }
    Ok(points.into_iter().zip(keep).filter_map(|(p, k)| k.then_some(p)).collect())
}

// ---- Tiles --------------------------------------------------------------------------------

/// The XYZ tile containing `point` at `zoom` (0–22). Latitude is clamped to the Mercator limit.
#[uniffi::export]
pub fn tile_for_point(point: GeoPoint, zoom: u8) -> Result<TileAddress, AetherError> {
    check_point(point.lat, point.lon)?;
    if zoom > 22 {
        return Err(bad("zoom must be 0-22"));
    }
    let (x, y) = mercator_fraction(point);
    let n = (1u64 << zoom) as f64;
    let max = (1u64 << zoom) - 1;
    Ok(TileAddress { zoom, x: ((x * n).floor() as u64).min(max) as u32, y: ((y * n).floor() as u64).min(max) as u32 })
}

/// North-west corner of an XYZ tile.
#[uniffi::export]
pub fn tile_origin(tile: TileAddress) -> Result<GeoPoint, AetherError> {
    if tile.zoom > 22 || u64::from(tile.x) >= 1u64 << tile.zoom || u64::from(tile.y) >= 1u64 << tile.zoom {
        return Err(bad("tile out of range"));
    }
    let n = (1u64 << tile.zoom) as f64;
    let lon = tile.x as f64 / n * 360.0 - 180.0;
    let lat = (std::f64::consts::PI * (1.0 - 2.0 * tile.y as f64 / n)).sinh().atan().to_degrees();
    Ok(GeoPoint { lat, lon })
}

/// MBTiles (TMS) row for an XYZ tile: the y axis counts up from the south.
#[uniffi::export]
pub fn tms_row(tile: TileAddress) -> u32 {
    ((1u64 << tile.zoom) - 1 - u64::from(tile.y)) as u32
}

/// Fractional Web Mercator position of `point` in the unit square (x east, y south).
#[uniffi::export]
pub fn mercator_unit(point: GeoPoint) -> Result<GeoPoint, AetherError> {
    check_point(point.lat, point.lon)?;
    let (x, y) = mercator_fraction(point);
    Ok(GeoPoint { lat: y, lon: x })
}

fn mercator_fraction(p: GeoPoint) -> (f64, f64) {
    let lat = p.lat.clamp(-85.051_128_78, 85.051_128_78).to_radians();
    let x = (p.lon + 180.0) / 360.0;
    let y = (1.0 - (lat.tan() + 1.0 / lat.cos()).ln() / std::f64::consts::PI) / 2.0;
    (x, y)
}

// ---- UTM / MGRS ---------------------------------------------------------------------------

fn utm_zone(lat: f64, lon: f64) -> u8 {
    if (56.0..64.0).contains(&lat) && (3.0..12.0).contains(&lon) {
        return 32;
    }
    if (72.0..=84.0).contains(&lat) && lon >= 0.0 && lon < 42.0 {
        return match lon {
            l if l < 9.0 => 31,
            l if l < 21.0 => 33,
            l if l < 33.0 => 35,
            _ => 37,
        };
    }
    ((((lon + 180.0) / 6.0).floor() as i32).clamp(0, 59) + 1) as u8
}

fn band_letter(lat: f64) -> u8 {
    MGRS_BANDS[(((lat + 80.0) / 8.0).floor() as i32).clamp(0, 19) as usize]
}

fn central_meridian(zone: u8) -> f64 {
    (zone as f64 - 1.0) * 6.0 - 180.0 + 3.0
}

/// Transverse Mercator forward (USGS / Snyder series) in a given zone.
fn tm_forward(lat: f64, lon: f64, zone: u8) -> (f64, f64) {
    let e2 = WGS84_F * (2.0 - WGS84_F);
    let ep2 = e2 / (1.0 - e2);
    let phi = lat.to_radians();
    let (s, c, t) = (phi.sin(), phi.cos(), phi.tan());
    let n = WGS84_A / (1.0 - e2 * s * s).sqrt();
    let tt = t * t;
    let cc = ep2 * c * c;
    let mut dl = lon - central_meridian(zone);
    if dl > 180.0 {
        dl -= 360.0;
    } else if dl < -180.0 {
        dl += 360.0;
    }
    let a = c * dl.to_radians();
    let (e4, e6) = (e2 * e2, e2 * e2 * e2);
    let m = WGS84_A
        * ((1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0) * phi
            - (3.0 * e2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0) * (2.0 * phi).sin()
            + (15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0) * (4.0 * phi).sin()
            - (35.0 * e6 / 3072.0) * (6.0 * phi).sin());
    let x = UTM_K0 * n
        * (a + (1.0 - tt + cc) * a.powi(3) / 6.0 + (5.0 - 18.0 * tt + tt * tt + 72.0 * cc - 58.0 * ep2) * a.powi(5) / 120.0)
        + 500_000.0;
    let mut y = UTM_K0
        * (m + n * t
            * (a * a / 2.0
                + (5.0 - tt + 9.0 * cc + 4.0 * cc * cc) * a.powi(4) / 24.0
                + (61.0 - 58.0 * tt + tt * tt + 600.0 * cc - 330.0 * ep2) * a.powi(6) / 720.0));
    if lat < 0.0 {
        y += 10_000_000.0;
    }
    (x, y)
}

fn tm_inverse(easting: f64, northing: f64, zone: u8, north: bool) -> (f64, f64) {
    let e2 = WGS84_F * (2.0 - WGS84_F);
    let ep2 = e2 / (1.0 - e2);
    let (e4, e6) = (e2 * e2, e2 * e2 * e2);
    let x = easting - 500_000.0;
    let y = if north { northing } else { northing - 10_000_000.0 };
    let m = y / UTM_K0;
    let mu = m / (WGS84_A * (1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0));
    let e1 = (1.0 - (1.0 - e2).sqrt()) / (1.0 + (1.0 - e2).sqrt());
    let phi1 = mu
        + (3.0 * e1 / 2.0 - 27.0 * e1.powi(3) / 32.0) * (2.0 * mu).sin()
        + (21.0 * e1 * e1 / 16.0 - 55.0 * e1.powi(4) / 32.0) * (4.0 * mu).sin()
        + (151.0 * e1.powi(3) / 96.0) * (6.0 * mu).sin()
        + (1097.0 * e1.powi(4) / 512.0) * (8.0 * mu).sin();
    let (s1, c1, t1) = (phi1.sin(), phi1.cos(), phi1.tan());
    let n1 = WGS84_A / (1.0 - e2 * s1 * s1).sqrt();
    let tt = t1 * t1;
    let cc = ep2 * c1 * c1;
    let r1 = WGS84_A * (1.0 - e2) / (1.0 - e2 * s1 * s1).powf(1.5);
    let d = x / (n1 * UTM_K0);
    let lat = phi1
        - (n1 * t1 / r1)
            * (d * d / 2.0 - (5.0 + 3.0 * tt + 10.0 * cc - 4.0 * cc * cc - 9.0 * ep2) * d.powi(4) / 24.0
                + (61.0 + 90.0 * tt + 298.0 * cc + 45.0 * tt * tt - 252.0 * ep2 - 3.0 * cc * cc) * d.powi(6) / 720.0);
    let lon = (d - (1.0 + 2.0 * tt + cc) * d.powi(3) / 6.0
        + (5.0 - 2.0 * cc + 28.0 * tt - 3.0 * cc * cc + 8.0 * ep2 + 24.0 * tt * tt) * d.powi(5) / 120.0)
        / c1;
    (lat.to_degrees(), norm_lon(central_meridian(zone) + lon.to_degrees()))
}

/// UTM position of a point. UTM and MGRS cover 80°S to 84°N; the polar (UPS) zones are not supported.
#[uniffi::export]
pub fn to_utm(point: GeoPoint) -> Result<UtmCoordinate, AetherError> {
    check_point(point.lat, point.lon)?;
    if !(-80.0..=84.0).contains(&point.lat) {
        return Err(bad("UTM covers 80°S to 84°N only"));
    }
    let zone = utm_zone(point.lat, point.lon);
    let (easting, northing) = tm_forward(point.lat, point.lon, zone);
    Ok(UtmCoordinate { zone, band: (band_letter(point.lat) as char).to_string(), easting, northing })
}

/// Latitude/longitude of a UTM position.
#[uniffi::export]
pub fn from_utm(utm: UtmCoordinate) -> Result<GeoPoint, AetherError> {
    let band = utm.band.trim().to_ascii_uppercase();
    let b = *band.as_bytes().first().ok_or_else(|| bad("missing latitude band"))?;
    if band.len() != 1 || !MGRS_BANDS.contains(&b) {
        return Err(bad("latitude band must be C-X (not I or O)"));
    }
    if !(1..=60).contains(&utm.zone) {
        return Err(bad("UTM zone must be 1-60"));
    }
    if !(100_000.0..1_000_000.0).contains(&utm.easting) || !(0.0..=10_000_000.0).contains(&utm.northing) {
        return Err(bad("easting or northing out of range"));
    }
    let (lat, lon) = tm_inverse(utm.easting, utm.northing, utm.zone, b >= b'N');
    Ok(GeoPoint { lat, lon })
}

/// MGRS reference with `digits` per axis (1–5: 10 km down to 1 m). Truncates, as MGRS requires.
#[uniffi::export]
pub fn to_mgrs(point: GeoPoint, digits: u8) -> Result<String, AetherError> {
    if !(1..=5).contains(&digits) {
        return Err(bad("MGRS precision must be 1-5 digits"));
    }
    let utm = to_utm(point)?;
    let set = ((utm.zone - 1) % 3) as usize;
    let col_index = (utm.easting / 100_000.0).floor() as i64 - 1;
    let col = MGRS_COLS[set][col_index.clamp(0, 7) as usize] as char;
    let row_offset = if utm.zone % 2 == 0 { 5 } else { 0 };
    let row = MGRS_ROWS[(((utm.northing / 100_000.0).floor() as i64 + row_offset).rem_euclid(20)) as usize] as char;
    let div = 10f64.powi(5 - digits as i32);
    // A small epsilon keeps exact grid lines from truncating into the previous square.
    let e = (((utm.easting.rem_euclid(100_000.0)) + 1e-6) / div).floor() as u64;
    let n = (((utm.northing.rem_euclid(100_000.0)) + 1e-6) / div).floor() as u64;
    let w = digits as usize;
    Ok(format!("{}{} {}{} {:0w$} {:0w$}", utm.zone, utm.band, col, row, e, n))
}

/// Parses an MGRS reference (spaces optional), returning the south-west corner of the referenced square
/// shifted to its centre, so a 4-digit reference lands mid-square.
#[uniffi::export]
pub fn from_mgrs(reference: String) -> Result<GeoPoint, AetherError> {
    let s: String = reference.chars().filter(|c| !c.is_whitespace()).collect::<String>().to_ascii_uppercase();
    let bytes = s.as_bytes();
    let zone_len = bytes.iter().take_while(|b| b.is_ascii_digit()).count();
    if zone_len == 0 || zone_len > 2 || bytes.len() < zone_len + 3 {
        return Err(bad("not an MGRS reference"));
    }
    let zone: u8 = s[..zone_len].parse().map_err(|_| bad("bad MGRS zone"))?;
    if !(1..=60).contains(&zone) {
        return Err(bad("UTM zone must be 1-60"));
    }
    let band = bytes[zone_len];
    let col = bytes[zone_len + 1];
    let row = bytes[zone_len + 2];
    if !MGRS_BANDS.contains(&band) {
        return Err(bad("bad MGRS latitude band"));
    }
    let digits = &s[zone_len + 3..];
    if digits.len() % 2 != 0 || digits.len() > 10 || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return Err(bad("MGRS needs an even number of digits (0-10)"));
    }
    let set = ((zone - 1) % 3) as usize;
    let col_index = MGRS_COLS[set].iter().position(|&c| c == col).ok_or_else(|| bad("bad MGRS column letter"))?;
    let row_index = MGRS_ROWS.iter().position(|&c| c == row).ok_or_else(|| bad("bad MGRS row letter"))? as i64;
    let row_offset = if zone % 2 == 0 { 5 } else { 0 };
    let half = digits.len() / 2;
    let scale = 10f64.powi(5 - half as i32);
    let (e_part, n_part) = if half == 0 { (0.0, 0.0) } else { (digits[..half].parse::<f64>().unwrap(), digits[half..].parse::<f64>().unwrap()) };
    let centre = scale / 2.0;
    let easting = (col_index as f64 + 1.0) * 100_000.0 + e_part * scale + centre;
    let mut northing = (row_index - row_offset).rem_euclid(20) as f64 * 100_000.0 + n_part * scale + centre;
    // Lift the northing into the right 2,000 km cycle using the latitude band.
    let band_index = MGRS_BANDS.iter().position(|&c| c == band).unwrap();
    let band_south = -80.0 + 8.0 * band_index as f64;
    let north = band >= b'N';
    let (_, min_n) = tm_forward(band_south, central_meridian(zone), zone);
    let min_n = if north { min_n } else if band_south <= -80.0 { 1_100_000.0 } else { min_n };
    while northing < min_n - 100_000.0 {
        northing += 2_000_000.0;
    }
    let (lat, lon) = tm_inverse(easting, northing, zone, north);
    Ok(GeoPoint { lat, lon })
}

// ---- Formatting and parsing -----------------------------------------------------------------

fn dms_parts(v: f64) -> (u32, u32, f64) {
    let a = v.abs();
    let mut d = a.floor() as u32;
    let mut m = ((a - d as f64) * 60.0).floor() as u32;
    let mut s = ((a - d as f64) * 60.0 - m as f64) * 60.0;
    if (s * 10.0).round() >= 600.0 {
        s = 0.0;
        m += 1;
    }
    if m >= 60 {
        m -= 60;
        d += 1;
    }
    (d, m, s)
}

/// Shows `point` in the chosen format. UTM/MGRS outside 80°S–84°N fall back to decimal degrees.
#[uniffi::export]
pub fn format_coordinate(point: GeoPoint, format: CoordFormat) -> Result<String, AetherError> {
    check_point(point.lat, point.lon)?;
    let ns = if point.lat < 0.0 { 'S' } else { 'N' };
    let ew = if point.lon < 0.0 { 'W' } else { 'E' };
    Ok(match format {
        CoordFormat::Decimal => format!("{:.6}, {:.6}", point.lat, point.lon),
        CoordFormat::Dms => {
            let (ld, lm, ls) = dms_parts(point.lat);
            let (od, om, os) = dms_parts(point.lon);
            format!("{ld}°{lm:02}'{ls:04.1}\"{ns} {od:03}°{om:02}'{os:04.1}\"{ew}")
        }
        CoordFormat::Ddm => {
            let ddm = |v: f64| {
                let a = v.abs();
                let mut d = a.floor() as u32;
                let mut m = (a - d as f64) * 60.0;
                if (m * 10_000.0).round() >= 600_000.0 {
                    m = 0.0;
                    d += 1;
                }
                (d, m)
            };
            let (ld, lm) = ddm(point.lat);
            let (od, om) = ddm(point.lon);
            format!("{ld}°{lm:07.4}'{ns} {od:03}°{om:07.4}'{ew}")
        }
        CoordFormat::Utm => match to_utm(point) {
            Ok(u) => format!("{}{} {:.0}E {:.0}N", u.zone, u.band, u.easting.floor(), u.northing.floor()),
            Err(_) => format_coordinate(point, CoordFormat::Decimal)?,
        },
        CoordFormat::Mgrs => match to_mgrs(point, 5) {
            Ok(m) => m,
            Err(_) => format_coordinate(point, CoordFormat::Decimal)?,
        },
    })
}

#[derive(Debug, PartialEq)]
enum Tok {
    Num(f64),
    Hemi(char),
    Sep,
    Unit,
}

fn tokenize(s: &str) -> Result<Vec<Tok>, AetherError> {
    let mut out = Vec::new();
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_ascii_digit() || c == '.' || ((c == '-' || c == '+') && chars.get(i + 1).is_some_and(|n| n.is_ascii_digit() || *n == '.')) {
            let start = i;
            i += 1;
            while i < chars.len() && (chars[i].is_ascii_digit() || chars[i] == '.') {
                i += 1;
            }
            let t: String = chars[start..i].iter().collect();
            out.push(Tok::Num(t.parse().map_err(|_| bad("bad number in coordinate"))?));
            continue;
        }
        match c.to_ascii_uppercase() {
            'N' | 'S' | 'E' | 'W' => out.push(Tok::Hemi(c.to_ascii_uppercase())),
            ',' | ';' | '/' => out.push(Tok::Sep),
            '°' | '\'' | '"' | '′' | '″' | '’' | '”' | ':' | 'º' => out.push(Tok::Unit),
            c if c.is_whitespace() => {}
            _ => return Err(bad("unrecognized character in coordinate")),
        }
        i += 1;
    }
    // Unit marks (° ' ") only delimit numbers; drop them.
    Ok(out.into_iter().filter(|t| *t != Tok::Unit).collect())
}

fn combine(nums: &[f64]) -> Result<f64, AetherError> {
    if nums.is_empty() || nums.len() > 3 {
        return Err(bad("expected degrees, minutes and seconds"));
    }
    let neg = nums[0] < 0.0 || nums[0].to_bits() == (-0.0f64).to_bits();
    let mut v = nums[0].abs();
    if let Some(&m) = nums.get(1) {
        if !(0.0..60.0).contains(&m) || nums[0].fract() != 0.0 {
            return Err(bad("minutes must be 0-59.999 after whole degrees"));
        }
        v += m / 60.0;
    }
    if let Some(&s) = nums.get(2) {
        if !(0.0..60.0).contains(&s) || nums[1].fract() != 0.0 {
            return Err(bad("seconds must be 0-59.999 after whole minutes"));
        }
        v += s / 3600.0;
    }
    Ok(if neg { -v } else { v })
}

/// Parses a coordinate typed or pasted in any supported format: decimal degrees
/// (`38.8895, -77.0352`), DMS, DDM (with N/S/E/W before or after), UTM (`18S 323484 4306480`)
/// or MGRS (`18SUJ2348406480`).
#[uniffi::export]
pub fn parse_coordinate(text: String) -> Result<GeoPoint, AetherError> {
    let t = text.trim();
    if t.is_empty() || t.len() > 80 {
        return Err(bad("empty or overlong coordinate"));
    }
    if let Some(p) = parse_utm_text(t) {
        return p;
    }
    let compact: String = t.chars().filter(|c| !c.is_whitespace()).collect();
    let cb = compact.as_bytes();
    let zl = cb.iter().take_while(|b| b.is_ascii_digit()).count();
    if (1..=2).contains(&zl) && cb.len() >= zl + 3 && cb[zl..zl + 3].iter().all(|b| b.is_ascii_alphabetic()) {
        return from_mgrs(compact);
    }
    let toks = tokenize(t)?;
    // Split into latitude and longitude groups.
    let (g1, g2): (Vec<&Tok>, Vec<&Tok>) = if let Some(i) = toks.iter().position(|x| *x == Tok::Sep) {
        (toks[..i].iter().collect(), toks[i + 1..].iter().filter(|x| **x != Tok::Sep).collect())
    } else {
        let hemis: Vec<usize> = toks.iter().enumerate().filter(|(_, x)| matches!(x, Tok::Hemi(_))).map(|(i, _)| i).collect();
        match hemis.as_slice() {
            [a, _] if *a == 0 => {
                let second = hemis[1];
                (toks[..second].iter().collect(), toks[second..].iter().collect())
            }
            [a, _] => (toks[..=*a].iter().collect(), toks[a + 1..].iter().collect()),
            [] => {
                let n = toks.len();
                if n % 2 != 0 || n == 0 {
                    return Err(bad("cannot tell latitude from longitude; separate them with a comma"));
                }
                (toks[..n / 2].iter().collect(), toks[n / 2..].iter().collect())
            }
            _ => return Err(bad("cannot tell latitude from longitude")),
        }
    };
    let read = |g: &[&Tok]| -> Result<(f64, Option<char>), AetherError> {
        let nums: Vec<f64> = g.iter().filter_map(|x| if let Tok::Num(n) = x { Some(*n) } else { None }).collect();
        let hemi: Vec<char> = g.iter().filter_map(|x| if let Tok::Hemi(h) = x { Some(*h) } else { None }).collect();
        if hemi.len() > 1 {
            return Err(bad("two hemisphere letters in one value"));
        }
        Ok((combine(&nums)?, hemi.first().copied()))
    };
    let (mut a, ha) = read(&g1)?;
    let (mut b, hb) = read(&g2)?;
    let apply = |v: f64, h: Option<char>| -> Result<f64, AetherError> {
        match h {
            Some('S') | Some('W') if v < 0.0 => Err(bad("use either a minus sign or S/W, not both")),
            Some('S') | Some('W') => Ok(-v),
            _ => Ok(v),
        }
    };
    a = apply(a, ha)?;
    b = apply(b, hb)?;
    let swap = matches!(ha, Some('E') | Some('W')) || matches!(hb, Some('N') | Some('S'));
    if swap && !(matches!(ha, Some('E') | Some('W')) && matches!(hb, Some('N') | Some('S'))) {
        return Err(bad("both values are latitudes or both are longitudes"));
    }
    let (lat, lon) = if swap { (b, a) } else { (a, b) };
    check_point(lat, lon)?;
    Ok(GeoPoint { lat, lon })
}

/// `18S 323484 4306480`, `18S 323484E 4306480N`, `18 S 323484 4306480`.
fn parse_utm_text(t: &str) -> Option<Result<GeoPoint, AetherError>> {
    let mut parts: Vec<String> = t.split_whitespace().map(str::to_ascii_uppercase).collect();
    if parts.len() == 4 && parts[0].len() <= 2 && parts[1].len() == 1 {
        let z = parts.remove(0);
        parts[0] = format!("{z}{}", parts[0]);
    }
    if parts.len() != 3 {
        return None;
    }
    let zb = parts[0].as_bytes();
    let zl = zb.iter().take_while(|b| b.is_ascii_digit()).count();
    if !(1..=2).contains(&zl) || zb.len() != zl + 1 || !zb[zl].is_ascii_alphabetic() {
        return None;
    }
    let e = parts[1].trim_end_matches('E').parse::<f64>().ok()?;
    let n = parts[2].trim_end_matches('N').parse::<f64>().ok()?;
    let zone: u8 = parts[0][..zl].parse().ok()?;
    Some(from_utm(UtmCoordinate { zone, band: parts[0][zl..].to_owned(), easting: e, northing: n }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(lat: f64, lon: f64) -> GeoPoint {
        GeoPoint { lat, lon }
    }

    fn close(a: f64, b: f64, tol: f64) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn vincenty_matches_reference() {
        // Flinders Peak to Buninyong (Vincenty 1975 test line): 54 972.271 m, 306°52'05.37".
        let flinders = p(-(37.0 + 57.0 / 60.0 + 3.72030 / 3600.0), 144.0 + 25.0 / 60.0 + 29.52440 / 3600.0);
        let buninyong = p(-(37.0 + 39.0 / 60.0 + 10.15610 / 3600.0), 143.0 + 55.0 / 60.0 + 35.38390 / 3600.0);
        let g = geodesic_inverse(flinders, buninyong).unwrap();
        assert!(close(g.distance_m, 54_972.271, 0.01), "{}", g.distance_m);
        assert!(close(g.initial_bearing_deg, 306.0 + 52.0 / 60.0 + 5.37 / 3600.0, 1e-4));
        let back = geodesic_destination(flinders, g.initial_bearing_deg, g.distance_m).unwrap();
        assert!(close(back.lat, buninyong.lat, 1e-8) && close(back.lon, buninyong.lon, 1e-8));
    }

    #[test]
    fn antipodal_does_not_hang() {
        let g = geodesic_inverse(p(0.0, 0.0), p(0.5, 179.7)).unwrap();
        assert!(g.distance_m > 19_900_000.0 && g.distance_m < 20_100_000.0);
        assert_eq!(geodesic_inverse(p(10.0, 10.0), p(10.0, 10.0)).unwrap().distance_m, 0.0);
    }

    #[test]
    fn utm_and_mgrs_round_trip() {
        let monument = p(38.889_484, -77.035_278);
        let u = to_utm(monument).unwrap();
        assert_eq!((u.zone, u.band.as_str()), (18, "S"));
        assert!((300_000.0..340_000.0).contains(&u.easting) && (4_300_000.0..4_310_000.0).contains(&u.northing), "{u:?}");
        // On the central meridian: easting is exactly 500 km and northing is k0 × the meridian arc
        // (WGS 84 arc from the equator to 45° = 4 984 944.378 m).
        let cm = to_utm(p(45.0, 9.0)).unwrap();
        assert!(close(cm.easting, 500_000.0, 1e-6) && close(cm.northing, 0.9996 * 4_984_944.378, 0.01), "{cm:?}");
        let eq = to_utm(p(0.0, 3.0)).unwrap();
        assert!(close(eq.northing, 0.0, 1e-6) && close(eq.easting, 500_000.0, 1e-6));
        let m = to_mgrs(monument, 5).unwrap();
        assert!(m.starts_with("18S UJ "), "{m}");
        let back = from_mgrs(m).unwrap();
        assert!(close(back.lat, monument.lat, 2e-5) && close(back.lon, monument.lon, 2e-5));
        let back = from_utm(u).unwrap();
        assert!(close(back.lat, monument.lat, 1e-7) && close(back.lon, monument.lon, 1e-7));
        for &(lat, lon) in &[(-33.8568, 151.2153), (51.5007, -0.1246), (-54.8, -68.3), (60.0, 5.3), (78.2, 15.6), (0.1, 0.1), (-0.1, -179.9)] {
            let pt = p(lat, lon);
            let back = from_mgrs(to_mgrs(pt, 5).unwrap()).unwrap();
            assert!(close(back.lat, lat, 2e-5) && close(back.lon, lon, 3e-5), "{lat},{lon} -> {back:?}");
        }
        assert_eq!(to_utm(p(60.0, 5.3)).unwrap().zone, 32);
        assert_eq!(to_utm(p(78.2, 15.6)).unwrap().zone, 33);
        assert!(to_utm(p(85.0, 0.0)).is_err());
        assert!(from_mgrs("18SUJ123".into()).is_err());
        assert!(from_mgrs("18IUJ1234".into()).is_err());
        let coarse = to_mgrs(monument, 2).unwrap();
        assert_eq!(coarse.split(' ').count(), 4);
    }

    #[test]
    fn formats_and_parses_every_format() {
        let pt = p(38.889_484, -77.035_278);
        for f in [CoordFormat::Decimal, CoordFormat::Dms, CoordFormat::Ddm, CoordFormat::Utm, CoordFormat::Mgrs] {
            let s = format_coordinate(pt, f).unwrap();
            let back = parse_coordinate(s.clone()).unwrap();
            assert!(close(back.lat, pt.lat, 2e-5) && close(back.lon, pt.lon, 2e-5), "{f:?} {s} -> {back:?}");
        }
        assert_eq!(format_coordinate(pt, CoordFormat::Dms).unwrap(), "38°53'22.1\"N 077°02'07.0\"W");
        let w = parse_coordinate("N 38 53 22.1 W 77 02 07".into()).unwrap();
        assert!(close(w.lat, 38.889_47, 1e-4) && close(w.lon, -77.035_28, 1e-4));
        let s = parse_coordinate("77.0353W 38.8895N".into()).unwrap();
        assert!(close(s.lat, 38.8895, 1e-9) && close(s.lon, -77.0353, 1e-9));
        assert!(parse_coordinate("-38.5S, 10".into()).is_err());
        assert!(parse_coordinate("95, 10".into()).is_err());
        assert!(parse_coordinate("38 75 10, 10".into()).is_err());
        assert!(parse_coordinate("38N 10N".into()).is_err());
        assert!(parse_coordinate("hello".into()).is_err());
        assert!(parse_coordinate("38.1 10.2 11.1".into()).is_err());
    }

    #[test]
    fn measures_paths_and_areas() {
        // A 0.01° square at the equator: ~1113.2 m sides.
        let sq = vec![p(0.0, 0.0), p(0.0, 0.01), p(0.01, 0.01), p(0.01, 0.0)];
        let area = polygon_area(sq.clone()).unwrap();
        assert!(close(area, 1_236_000.0, 15_000.0), "{area}");
        let per = polygon_perimeter(sq.clone()).unwrap();
        assert!(close(per, 4.0 * 1_110.0, 15.0), "{per}");
        assert!(close(path_length(sq).unwrap(), 3.0 * 1_110.0, 12.0));
        assert_eq!(polygon_area(vec![p(0.0, 0.0), p(1.0, 1.0)]).unwrap(), 0.0);
    }

    fn walk() -> Vec<TrackPoint> {
        // East along the equator, 10 m every 10 s, climbing 1 m per fix, with a 60 s stop.
        let mut v = Vec::new();
        let mut t = 0;
        for i in 0..20 {
            let lon = i as f64 * 10.0 / 111_319.49;
            v.push(TrackPoint { lat: 0.0, lon, ele_m: Some(100.0 + i as f64), time_ms: Some(t) });
            t += if i == 9 { 60_000 } else { 10_000 };
        }
        v
    }

    #[test]
    fn track_statistics() {
        let s = track_stats(walk()).unwrap();
        assert_eq!(s.points, 20);
        assert!(close(s.distance_m, 190.0, 0.5), "{}", s.distance_m);
        assert!(close(s.ascent_m, 18.0, 3.0) && s.descent_m == 0.0, "{s:?}");
        assert_eq!((s.min_ele_m, s.max_ele_m), (Some(100.0), Some(119.0)));
        assert_eq!(s.duration_ms, Some(18 * 10_000 + 60_000));
        assert_eq!(s.moving_ms, Some(18 * 10_000));
        assert!(close(s.max_speed_mps.unwrap(), 1.0, 0.01));
        // GPS jitter of ±1 m never adds climb.
        let noisy: Vec<TrackPoint> = (0..50)
            .map(|i| TrackPoint { lat: 0.0, lon: 0.0, ele_m: Some(200.0 + if i % 2 == 0 { 1.0 } else { -1.0 }), time_ms: None })
            .collect();
        let n = track_stats(noisy).unwrap();
        assert_eq!((n.ascent_m, n.descent_m, n.duration_ms, n.moving_ms), (0.0, 0.0, None, None));
        assert_eq!(track_stats(vec![]).unwrap().distance_m, 0.0);
    }

    #[test]
    fn profile_and_simplification() {
        let prof = elevation_profile(walk()).unwrap();
        assert_eq!(prof.len(), 20);
        assert!(close(prof[19].distance_m, 190.0, 0.5) && prof[0].distance_m == 0.0);
        let straight = simplify_track(walk(), 1.0).unwrap();
        assert_eq!(straight.len(), 2);
        let mut bent = walk();
        bent[10].lat = 50.0 / 111_000.0;
        // A 50 m spike keeps itself and both neighbours (each is far from its chord).
        let kept = simplify_track(bent, 5.0).unwrap();
        assert_eq!(kept.len(), 5);
        assert!(kept.iter().any(|t| t.lat > 0.0));
        assert!(simplify_track(walk(), -1.0).is_err());
    }

    #[test]
    fn tile_math() {
        let t = tile_for_point(p(38.889_484, -77.035_278), 12).unwrap();
        assert_eq!((t.x, t.y), (1171, 1567));
        let o = tile_origin(t).unwrap();
        assert!(o.lat >= 38.889 && o.lon <= -77.035);
        assert_eq!(tms_row(TileAddress { zoom: 1, x: 0, y: 0 }), 1);
        assert_eq!(tile_for_point(p(90.0, 180.0), 3).unwrap(), TileAddress { zoom: 3, x: 7, y: 0 });
        assert!(tile_origin(TileAddress { zoom: 2, x: 4, y: 0 }).is_err());
    }
}
