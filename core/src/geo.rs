//! Opt-in v2fly GeoIP / GeoSite lookups.
//!
//! The lists are the v2ray `GeoIPList` and `GeoSiteList` protobufs
//! ([v2fly/geoip](https://github.com/v2fly/geoip) `geoip.dat` and
//! [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community) `dlc.dat`,
//! saved as `geosite.dat`). Loyalsoldier and sing-box lists are GPL-3 and are not accepted.
//!
//! Nothing here runs unless geo routing is on. The switch defaults off: `describe_endpoint`
//! then returns its input unchanged and does not read the dataset files. Turn it on with
//! `INFINITUM_GEO_ENABLED` (`1` / `true` / `yes` / `on`) or [`set_geo_routing`], which is what
//! the settings toggle and `aether-ca --geo` persist into.

use std::fs;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use sha2::{Digest, Sha256};

const DISALLOWED_SOURCES: [&str; 3] = ["loyalsoldier", "sing-box", "singbox"];

/// A lockfile row: where a dataset is published and the SHA-256 it must match.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeoLockEntry {
    pub name: String,
    pub file: String,
    pub tag: String,
    pub url: String,
    pub sha256: String,
    pub sha256_url: String,
}

#[derive(Debug)]
pub struct GeoError(String);

impl std::fmt::Display for GeoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for GeoError {}

impl GeoError {
    fn new(msg: impl Into<String>) -> Self {
        Self(msg.into())
    }
}

/// `INFINITUM_GEO_ENABLED` is on only for these values. Empty, `0`, `false`, `off`, and `no` stay off.
pub fn geo_flag_is_on(value: Option<&str>) -> bool {
    matches!(
        value.map(str::trim).map(str::to_ascii_lowercase).as_deref(),
        Some("1" | "true" | "yes" | "on")
    )
}

struct V4 {
    addr: u32,
    prefix: u8,
    code: u32,
}

struct V6 {
    addr: [u8; 16],
    prefix: u8,
    code: u32,
}

struct Rule {
    /// 0 plain, 1 regex (not applied), 2 root domain, 3 full domain.
    kind: u8,
    value: String,
}

struct Site {
    code: String,
    rules: Vec<Rule>,
}

/// Parsed geo lists. Lookups borrow category names from this value.
#[derive(Default)]
pub struct GeoDb {
    codes: Vec<String>,
    v4: Vec<V4>,
    v6: Vec<V6>,
    sites: Vec<Site>,
}

impl GeoDb {
    /// Country or list code of the longest matching prefix, if any.
    ///
    /// `inverse_match` entries are not treated as a country: they describe the complement of a
    /// range, so they are left out of this lookup.
    pub fn lookup_ip(&self, ip: IpAddr) -> Option<&str> {
        let idx = match ip {
            IpAddr::V4(v) => best_v4(&self.v4, u32::from(v)),
            IpAddr::V6(v) => best_v6(&self.v6, &v.octets()),
        }?;
        self.codes.get(idx as usize).map(String::as_str)
    }

    /// Category codes whose rules match `host`, in file order, without duplicates.
    ///
    /// Root-domain rules match the name and its subdomains. Full rules match one name.
    /// Plain rules match a substring. Regex rules are kept in the file and not applied:
    /// matching them would pull in a regex engine, and this crate adds no dependency for that.
    pub fn lookup_domain(&self, host: &str) -> Vec<&str> {
        let host = normalize_host(host);
        if host.is_empty() {
            return Vec::new();
        }
        let mut out = Vec::new();
        for site in &self.sites {
            if out.iter().any(|c| *c == site.code) {
                continue;
            }
            if site.rules.iter().any(|r| rule_matches(r, &host)) {
                out.push(site.code.as_str());
            }
        }
        out
    }
}

fn best_v4(nets: &[V4], addr: u32) -> Option<u32> {
    let mut best: Option<(u8, u32)> = None;
    for net in nets {
        if !v4_match(addr, net.addr, net.prefix) {
            continue;
        }
        if best.map(|(p, _)| net.prefix > p).unwrap_or(true) {
            best = Some((net.prefix, net.code));
        }
    }
    best.map(|(_, c)| c)
}

fn best_v6(nets: &[V6], addr: &[u8; 16]) -> Option<u32> {
    let mut best: Option<(u8, u32)> = None;
    for net in nets {
        if !prefix_match(addr, &net.addr, net.prefix) {
            continue;
        }
        if best.map(|(p, _)| net.prefix > p).unwrap_or(true) {
            best = Some((net.prefix, net.code));
        }
    }
    best.map(|(_, c)| c)
}

fn v4_match(addr: u32, net: u32, prefix: u8) -> bool {
    if prefix > 32 {
        return false;
    }
    if prefix == 0 {
        return true;
    }
    let shift = 32 - u32::from(prefix);
    let mask = u32::MAX << shift;
    (addr & mask) == (net & mask)
}

fn prefix_match(addr: &[u8], net: &[u8], prefix: u8) -> bool {
    let bits = (addr.len() * 8) as u8;
    if addr.len() != net.len() || prefix > bits {
        return false;
    }
    let full = (prefix / 8) as usize;
    if addr[..full] != net[..full] {
        return false;
    }
    let rem = prefix % 8;
    if rem == 0 {
        return true;
    }
    let mask = 0xffu8 << (8 - rem);
    (addr[full] & mask) == (net[full] & mask)
}

fn rule_matches(rule: &Rule, host: &str) -> bool {
    if rule.value.is_empty() {
        return false;
    }
    match rule.kind {
        0 => host.contains(&rule.value),
        2 => {
            host == rule.value
                || (host.len() > rule.value.len()
                    && host.as_bytes().get(host.len() - rule.value.len() - 1) == Some(&b'.')
                    && host.ends_with(&rule.value))
        }
        3 => host == rule.value,
        _ => false,
    }
}

fn normalize_host(host: &str) -> String {
    host.trim().trim_end_matches('.').to_ascii_lowercase()
}

/// Host portion of `host`, `host:port`, or `[v6]:port`.
fn endpoint_host(endpoint: &str) -> &str {
    let s = endpoint.trim();
    if let Some(rest) = s.strip_prefix('[') {
        if let Some(end) = rest.find(']') {
            return &rest[..end];
        }
    }
    if s.parse::<IpAddr>().is_ok() {
        return s;
    }
    if let Some((host, port)) = s.rsplit_once(':') {
        if !port.is_empty()
            && port.bytes().all(|b| b.is_ascii_digit())
            && !host.is_empty()
            && !host.contains(':')
        {
            return host;
        }
    }
    s
}

struct R<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> R<'a> {
    fn new(b: &'a [u8]) -> Self {
        Self { b, i: 0 }
    }

    fn done(&self) -> bool {
        self.i >= self.b.len()
    }

    fn varint(&mut self) -> Result<u64, GeoError> {
        let mut n = 0u64;
        let mut shift = 0;
        loop {
            if self.i >= self.b.len() {
                return Err(GeoError::new("truncated geo list"));
            }
            let c = self.b[self.i];
            self.i += 1;
            if shift >= 64 {
                return Err(GeoError::new("bad protobuf varint"));
            }
            n |= u64::from(c & 0x7f) << shift;
            if c & 0x80 == 0 {
                return Ok(n);
            }
            shift += 7;
        }
    }

    fn chunk(&mut self) -> Result<&'a [u8], GeoError> {
        let n = self.varint()?;
        let left = (self.b.len() - self.i) as u64;
        if n > left {
            return Err(GeoError::new("truncated geo list"));
        }
        let n = n as usize;
        let start = self.i;
        self.i += n;
        Ok(&self.b[start..self.i])
    }

    fn skip(&mut self, wire: u32) -> Result<(), GeoError> {
        match wire {
            0 => {
                self.varint()?;
                Ok(())
            }
            1 => self.advance(8),
            2 => {
                let n = self.varint()?;
                let left = (self.b.len() - self.i) as u64;
                if n > left {
                    return Err(GeoError::new("truncated geo list"));
                }
                self.advance(n as usize)
            }
            5 => self.advance(4),
            _ => Err(GeoError::new(format!(
                "unsupported protobuf wire type {wire}"
            ))),
        }
    }

    fn advance(&mut self, n: usize) -> Result<(), GeoError> {
        if self.i + n > self.b.len() {
            return Err(GeoError::new("truncated geo list"));
        }
        self.i += n;
        Ok(())
    }
}

fn utf8_field(bytes: &[u8]) -> Result<String, GeoError> {
    String::from_utf8(bytes.to_vec()).map_err(|_| GeoError::new("geo list string is not utf-8"))
}

/// Decodes a v2ray `GeoIPList` and `GeoSiteList` into memory.
pub fn load_geo_lists(geoip: &[u8], geosite: &[u8]) -> Result<GeoDb, GeoError> {
    let mut db = GeoDb::default();
    parse_geoip(&mut db, geoip)?;
    parse_geosite(&mut db, geosite)?;
    Ok(db)
}

fn parse_geoip(db: &mut GeoDb, bytes: &[u8]) -> Result<(), GeoError> {
    let mut top = R::new(bytes);
    while !top.done() {
        let key = top.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        if field == 1 && wire == 2 {
            parse_geoip_entry(db, top.chunk()?)?;
        } else {
            top.skip(wire)?;
        }
    }
    Ok(())
}

fn parse_geoip_entry(db: &mut GeoDb, bytes: &[u8]) -> Result<(), GeoError> {
    let mut r = R::new(bytes);
    let mut country = String::new();
    let mut code = String::new();
    let mut inverse = false;
    let mut cidrs: Vec<(Vec<u8>, u32)> = Vec::new();
    while !r.done() {
        let key = r.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        match (field, wire) {
            (1, 2) => country = utf8_field(r.chunk()?)?,
            (5, 2) => code = utf8_field(r.chunk()?)?,
            (3, 0) => inverse = r.varint()? != 0,
            (2, 2) => cidrs.push(parse_cidr(r.chunk()?)?),
            _ => r.skip(wire)?,
        }
    }
    if inverse {
        return Ok(());
    }
    let name = if !country.is_empty() { country } else { code };
    if name.is_empty() || cidrs.is_empty() {
        return Ok(());
    }
    let code_idx = db.codes.len() as u32;
    db.codes.push(name);
    for (ip, prefix) in cidrs {
        if ip.len() == 4 && prefix <= 32 {
            let addr = u32::from_be_bytes([ip[0], ip[1], ip[2], ip[3]]);
            db.v4.push(V4 {
                addr,
                prefix: prefix as u8,
                code: code_idx,
            });
        } else if ip.len() == 16 && prefix <= 128 {
            let mut addr = [0u8; 16];
            addr.copy_from_slice(&ip);
            db.v6.push(V6 {
                addr,
                prefix: prefix as u8,
                code: code_idx,
            });
        }
    }
    Ok(())
}

fn parse_cidr(bytes: &[u8]) -> Result<(Vec<u8>, u32), GeoError> {
    let mut r = R::new(bytes);
    let mut ip = Vec::new();
    let mut prefix = 0u32;
    while !r.done() {
        let key = r.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        match (field, wire) {
            (1, 2) => ip = r.chunk()?.to_vec(),
            (2, 0) => prefix = r.varint()? as u32,
            _ => r.skip(wire)?,
        }
    }
    Ok((ip, prefix))
}

fn parse_geosite(db: &mut GeoDb, bytes: &[u8]) -> Result<(), GeoError> {
    let mut top = R::new(bytes);
    while !top.done() {
        let key = top.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        if field == 1 && wire == 2 {
            db.sites.push(parse_site(top.chunk()?)?);
        } else {
            top.skip(wire)?;
        }
    }
    Ok(())
}

fn parse_site(bytes: &[u8]) -> Result<Site, GeoError> {
    let mut r = R::new(bytes);
    let mut code = String::new();
    let mut rules = Vec::new();
    while !r.done() {
        let key = r.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        match (field, wire) {
            (1, 2) => code = utf8_field(r.chunk()?)?,
            (4, 2) => {
                let alt = utf8_field(r.chunk()?)?;
                if code.is_empty() {
                    code = alt;
                }
            }
            (2, 2) => {
                if let Some(rule) = parse_domain(r.chunk()?)? {
                    rules.push(rule);
                }
            }
            _ => r.skip(wire)?,
        }
    }
    Ok(Site { code, rules })
}

fn parse_domain(bytes: &[u8]) -> Result<Option<Rule>, GeoError> {
    let mut r = R::new(bytes);
    let mut kind = 0u8;
    let mut value = String::new();
    while !r.done() {
        let key = r.varint()?;
        let (field, wire) = (key >> 3, (key & 7) as u32);
        match (field, wire) {
            (1, 0) => {
                let v = r.varint()?;
                kind = u8::try_from(v).unwrap_or(255);
            }
            (2, 2) => value = utf8_field(r.chunk()?)?.to_ascii_lowercase(),
            _ => r.skip(wire)?,
        }
    }
    if value.is_empty() {
        return Ok(None);
    }
    Ok(Some(Rule { kind, value }))
}

/// Reads `geoip.dat` and `geosite.dat` from `dir`.
pub fn load_geo_dir(dir: &Path) -> Result<GeoDb, GeoError> {
    let geoip =
        fs::read(dir.join("geoip.dat")).map_err(|e| GeoError::new(format!("geoip.dat: {e}")))?;
    let geosite = fs::read(dir.join("geosite.dat"))
        .map_err(|e| GeoError::new(format!("geosite.dat: {e}")))?;
    load_geo_lists(&geoip, &geosite)
}

struct Runtime {
    /// `Some` after a settings toggle or `--geo` flag. `None` follows the env flag.
    explicit: Option<bool>,
    dir_override: Option<PathBuf>,
    cache_key: Option<String>,
    cache: Option<GeoDb>,
}

static RUNTIME: Mutex<Runtime> = Mutex::new(Runtime {
    explicit: None,
    dir_override: None,
    cache_key: None,
    cache: None,
});

fn runtime() -> std::sync::MutexGuard<'static, Runtime> {
    RUNTIME.lock().unwrap_or_else(|e| e.into_inner())
}

/// Forgets an in-process override and any loaded lists. Tests use this so cases stay independent.
pub fn clear_geo_routing_override() {
    let mut rt = runtime();
    rt.explicit = None;
    rt.dir_override = None;
    rt.cache_key = None;
    rt.cache = None;
}

#[cfg(test)]
fn set_data_dir(path: Option<PathBuf>) {
    let mut rt = runtime();
    rt.dir_override = path;
    rt.cache_key = None;
    rt.cache = None;
}

/// Whether geo routing is on. A saved toggle wins; otherwise `INFINITUM_GEO_ENABLED` is read.
/// Missing or unrecognized values are off.
#[uniffi::export]
pub fn geo_routing_enabled() -> bool {
    match runtime().explicit {
        Some(v) => v,
        None => geo_flag_is_on(std::env::var("INFINITUM_GEO_ENABLED").ok().as_deref()),
    }
}

/// In-process switch used by the settings toggle and `aether-ca --geo`.
/// Does not by itself rewrite the config file; callers persist the choice.
#[uniffi::export]
pub fn set_geo_routing(enabled: bool) {
    runtime().explicit = Some(enabled);
}

fn data_dir(rt: &Runtime) -> PathBuf {
    if let Some(dir) = &rt.dir_override {
        return dir.clone();
    }
    if let Ok(dir) = std::env::var("INFINITUM_GEO_DIR") {
        if !dir.is_empty() {
            return PathBuf::from(dir);
        }
    }
    PathBuf::from("data/geo")
}

fn file_stamp(path: &Path) -> Option<String> {
    let meta = fs::metadata(path).ok()?;
    let modified = meta
        .modified()
        .ok()?
        .duration_since(std::time::UNIX_EPOCH)
        .ok()?
        .as_nanos();
    Some(format!("{}:{}:{}", path.display(), meta.len(), modified))
}

fn cached_db(rt: &mut Runtime) -> Option<&GeoDb> {
    let dir = data_dir(rt);
    let ip = dir.join("geoip.dat");
    let site = dir.join("geosite.dat");
    let key = format!("{:?}{:?}", file_stamp(&ip)?, file_stamp(&site)?);
    if rt.cache_key.as_deref() != Some(key.as_str()) {
        rt.cache = Some(load_geo_dir(&dir).unwrap_or_default());
        rt.cache_key = Some(key);
    }
    rt.cache.as_ref()
}

fn annotation(db: &GeoDb, host: &str) -> Option<String> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return db.lookup_ip(ip).map(|code| format!(" geo={code}"));
    }
    let cats = db.lookup_domain(host);
    if cats.is_empty() {
        None
    } else {
        Some(format!(" geo={}", cats.join(",")))
    }
}

/// Peer label for logs and the mesh list.
///
/// When geo routing is off this is `endpoint` unchanged and the dataset is not opened.
/// When it is on, a matching country or site category is appended (`"203.0.113.4:7337 geo=US"`).
/// A missing or unreadable dataset leaves the endpoint unchanged so a session still comes up.
#[uniffi::export]
pub fn describe_endpoint(endpoint: String) -> String {
    if !geo_routing_enabled() {
        return endpoint;
    }
    let host = endpoint_host(&endpoint).to_owned();
    let mut rt = runtime();
    let Some(note) = cached_db(&mut rt).and_then(|db| annotation(db, &host)) else {
        return endpoint;
    };
    format!("{endpoint}{note}")
}

/// Path of the persisted geo-routing switch.
///
/// `AETHER_GEO_CONFIG` overrides it. Otherwise `$XDG_CONFIG_HOME/aether/geo.conf`,
/// or `~/.config/aether/geo.conf`.
pub fn geo_config_path() -> PathBuf {
    if let Some(p) = std::env::var_os("AETHER_GEO_CONFIG") {
        return PathBuf::from(p);
    }
    if let Some(base) = std::env::var_os("XDG_CONFIG_HOME") {
        return PathBuf::from(base).join("aether").join("geo.conf");
    }
    if let Some(home) = std::env::var_os("HOME") {
        return PathBuf::from(home)
            .join(".config")
            .join("aether")
            .join("geo.conf");
    }
    PathBuf::from("aether-geo.conf")
}

/// `None` when the file is absent (the switch has never been saved, so it stays off
/// unless `INFINITUM_GEO_ENABLED` is set). `Some` is the saved value.
pub fn load_geo_config(path: &Path) -> Result<Option<bool>, GeoError> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(GeoError::new(format!("{}: {e}", path.display()))),
    };
    let mut enabled = None;
    for (n, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            return Err(GeoError::new(format!(
                "{}:{}: expected key=value",
                path.display(),
                n + 1
            )));
        };
        if k.trim() != "enabled" {
            return Err(GeoError::new(format!(
                "{}:{}: unknown setting {}",
                path.display(),
                n + 1,
                k.trim()
            )));
        }
        enabled = Some(match v.trim() {
            "true" => true,
            "false" => false,
            other => {
                return Err(GeoError::new(format!(
                    "{}:{}: enabled must be true or false, got {other}",
                    path.display(),
                    n + 1
                )));
            }
        });
    }
    enabled
        .map(Some)
        .ok_or_else(|| GeoError::new(format!("{}: missing enabled=", path.display())))
}

/// Writes the switch. `false` is the same as the default: geo routing stays off.
pub fn save_geo_config(path: &Path, enabled: bool) -> Result<(), GeoError> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|e| GeoError::new(format!("{}: {e}", parent.display())))?;
        }
    }
    let body = format!(
        "# ÆTHER geo routing. Off leaves connections unchanged.\nenabled={}\n",
        if enabled { "true" } else { "false" }
    );
    fs::write(path, body).map_err(|e| GeoError::new(format!("{}: {e}", path.display())))
}

/// Applies a saved switch to this process. A missing file changes nothing.
pub fn apply_geo_config(path: &Path) -> Result<Option<bool>, GeoError> {
    if let Some(enabled) = load_geo_config(path)? {
        set_geo_routing(enabled);
        return Ok(Some(enabled));
    }
    Ok(None)
}

/// Parses the fetch lockfile. Rejects Loyalsoldier and sing-box URLs, and any non-https URL.
pub fn parse_geo_lock(text: &str) -> Result<Vec<GeoLockEntry>, GeoError> {
    let mut entries = Vec::new();
    let mut cur: Option<GeoLockEntry> = None;
    let finish =
        |cur: &mut Option<GeoLockEntry>, entries: &mut Vec<GeoLockEntry>| -> Result<(), GeoError> {
            if let Some(entry) = cur.take() {
                validate_entry(&entry)?;
                entries.push(entry);
            }
            Ok(())
        };
    for (n, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            return Err(GeoError::new(format!(
                "geo.lock:{}: expected key=value",
                n + 1
            )));
        };
        let (k, v) = (k.trim(), v.trim());
        if k == "name" {
            finish(&mut cur, &mut entries)?;
            cur = Some(GeoLockEntry {
                name: v.to_owned(),
                file: String::new(),
                tag: String::new(),
                url: String::new(),
                sha256: String::new(),
                sha256_url: String::new(),
            });
            continue;
        }
        let entry = cur
            .as_mut()
            .ok_or_else(|| GeoError::new(format!("geo.lock:{}: {k} before name", n + 1)))?;
        match k {
            "file" => entry.file = v.to_owned(),
            "tag" => entry.tag = v.to_owned(),
            "url" => entry.url = v.to_owned(),
            "sha256" => entry.sha256 = v.to_ascii_lowercase(),
            "sha256_url" => entry.sha256_url = v.to_owned(),
            other => {
                return Err(GeoError::new(format!(
                    "geo.lock:{}: unknown key {other}",
                    n + 1
                )))
            }
        }
    }
    finish(&mut cur, &mut entries)?;
    if entries.is_empty() {
        return Err(GeoError::new("geo.lock has no datasets"));
    }
    Ok(entries)
}

fn validate_entry(entry: &GeoLockEntry) -> Result<(), GeoError> {
    if entry.name.is_empty() || entry.file.is_empty() || entry.tag.is_empty() {
        return Err(GeoError::new(format!(
            "{}: name, file, and tag are required",
            entry.name
        )));
    }
    if entry.file.contains('/') || entry.file.contains("..") {
        return Err(GeoError::new(format!(
            "{}: file must be a bare name",
            entry.name
        )));
    }
    if !entry.sha256.chars().all(|c| c.is_ascii_hexdigit()) || entry.sha256.len() != 64 {
        return Err(GeoError::new(format!(
            "{}: sha256 must be 64 hex characters",
            entry.name
        )));
    }
    for url in [&entry.url, &entry.sha256_url] {
        if !url.starts_with("https://") {
            return Err(GeoError::new(format!("{}: URL must be https", entry.name)));
        }
        let lower = url.to_ascii_lowercase();
        if DISALLOWED_SOURCES.iter().any(|s| lower.contains(s)) {
            return Err(GeoError::new(format!(
                "{}: refusing {url}. Loyalsoldier and sing-box geo lists are GPL-3 and are not used",
                entry.name
            )));
        }
    }
    Ok(())
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// First 64-hex token in a `sha256sum` file.
pub fn parse_sha256sum(text: &str) -> Result<String, GeoError> {
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let token = line.split_whitespace().next().unwrap_or("");
        if token.len() == 64 && token.chars().all(|c| c.is_ascii_hexdigit()) {
            return Ok(token.to_ascii_lowercase());
        }
    }
    Err(GeoError::new("checksum file has no SHA-256"))
}

/// Accepts bytes only when they match both the official `.sha256sum` asset and the lockfile.
/// A mismatch is an error and the caller must not keep the download.
pub fn accept_download(
    bytes: &[u8],
    official_sha256sum: &str,
    lock_sha256: &str,
) -> Result<(), GeoError> {
    let official = parse_sha256sum(official_sha256sum)?;
    let lock = lock_sha256.trim().to_ascii_lowercase();
    if official != lock {
        return Err(GeoError::new(format!(
            "official checksum {official} does not match the lockfile ({lock}); not saving the dataset"
        )));
    }
    let got = sha256_hex(bytes);
    if got != official {
        return Err(GeoError::new(format!(
            "downloaded file SHA-256 {got} does not match the official checksum {official}; not saving the dataset"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::MutexGuard;

    const GEOIP_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/geoip.dat");
    const GEOSITE_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/geosite.dat");

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            clear_geo_routing_override();
        }
    }

    fn isolated() -> (MutexGuard<'static, ()>, Reset) {
        let guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        clear_geo_routing_override();
        (guard, Reset)
    }

    fn db() -> GeoDb {
        load_geo_lists(GEOIP_FIXTURE, GEOSITE_FIXTURE).unwrap()
    }

    #[test]
    fn flag_is_off_unless_explicitly_on() {
        assert!(!geo_flag_is_on(None));
        assert!(!geo_flag_is_on(Some("")));
        assert!(!geo_flag_is_on(Some("0")));
        assert!(!geo_flag_is_on(Some("false")));
        assert!(!geo_flag_is_on(Some("off")));
        assert!(!geo_flag_is_on(Some("no")));
        assert!(geo_flag_is_on(Some("1")));
        assert!(geo_flag_is_on(Some("true")));
        assert!(geo_flag_is_on(Some("YES")));
        assert!(geo_flag_is_on(Some("on")));
    }

    #[test]
    fn synthetic_lists_resolve_ip_and_domain() {
        let db = db();
        let ip = |s: &str| db.lookup_ip(s.parse().unwrap()).map(str::to_owned);
        assert_eq!(ip("1.2.3.4").as_deref(), Some("CA"));
        assert_eq!(ip("1.2.3.5").as_deref(), Some("US"));
        assert_eq!(ip("5.6.7.8").as_deref(), Some("DE"));
        assert_eq!(ip("10.1.1.1").as_deref(), Some("PRIVATE"));
        assert_eq!(ip("2001:db8::1").as_deref(), Some("JP"));
        assert_eq!(ip("8.8.8.8"), None);
        assert_eq!(
            ip("9.9.9.9"),
            None,
            "inverse_match is not a country assignment"
        );

        let cats = |h: &str| {
            db.lookup_domain(h)
                .into_iter()
                .map(str::to_owned)
                .collect::<Vec<_>>()
        };
        assert_eq!(cats("www.google.com"), vec!["GOOGLE".to_owned()]);
        assert_eq!(cats("GOOGLE.com"), vec!["GOOGLE".to_owned()]);
        assert_eq!(
            cats("news.google.com"),
            vec!["GOOGLE".to_owned(), "NEWS".to_owned()]
        );
        assert!(cats("google.com.evil").is_empty());
        assert_eq!(cats("ads.example"), vec!["ADS".to_owned()]);
        assert!(cats("a.ads.example").is_empty());
        assert_eq!(cats("cdn.tracker.test"), vec!["KEYWORD".to_owned()]);
        assert!(cats("nope").is_empty(), "regex rules are not applied");
    }

    #[test]
    fn truncated_list_is_rejected() {
        assert!(load_geo_lists(&[0x0a, 0x7f], GEOSITE_FIXTURE).is_err());
        assert!(load_geo_lists(GEOIP_FIXTURE, &[0x0a]).is_err());
    }

    #[test]
    fn describe_endpoint_is_unchanged_while_off_and_annotates_when_on() {
        let _hold = isolated();
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("geoip.dat"), GEOIP_FIXTURE).unwrap();
        fs::write(dir.path().join("geosite.dat"), GEOSITE_FIXTURE).unwrap();
        set_data_dir(Some(dir.path().to_owned()));

        assert!(!geo_routing_enabled());
        assert_eq!(describe_endpoint("1.2.3.5:7337".into()), "1.2.3.5:7337");
        assert_eq!(
            describe_endpoint("www.google.com:7337".into()),
            "www.google.com:7337"
        );

        set_geo_routing(true);
        assert!(geo_routing_enabled());
        assert_eq!(
            describe_endpoint("1.2.3.5:7337".into()),
            "1.2.3.5:7337 geo=US"
        );
        assert_eq!(describe_endpoint("1.2.3.4".into()), "1.2.3.4 geo=CA");
        assert_eq!(
            describe_endpoint("[2001:db8::1]:7337".into()),
            "[2001:db8::1]:7337 geo=JP"
        );
        assert_eq!(
            describe_endpoint("www.google.com:7337".into()),
            "www.google.com:7337 geo=GOOGLE"
        );
        assert_eq!(
            describe_endpoint("news.google.com".into()),
            "news.google.com geo=GOOGLE,NEWS"
        );
        assert_eq!(describe_endpoint("203.0.113.9:1".into()), "203.0.113.9:1");

        set_geo_routing(false);
        assert_eq!(describe_endpoint("1.2.3.5:7337".into()), "1.2.3.5:7337");
    }

    #[test]
    fn saved_switch_defaults_off_and_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("geo.conf");
        assert_eq!(load_geo_config(&path).unwrap(), None);
        save_geo_config(&path, false).unwrap();
        assert_eq!(load_geo_config(&path).unwrap(), Some(false));
        save_geo_config(&path, true).unwrap();
        assert_eq!(load_geo_config(&path).unwrap(), Some(true));
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.contains("enabled=true"));
        assert!(load_geo_config(&dir.path().join("nope.conf"))
            .unwrap()
            .is_none());
    }

    #[test]
    fn lockfile_matches_published_v2fly_digests_and_refuses_gpl_lists() {
        let text = include_str!("../geo.lock");
        let entries = parse_geo_lock(text).unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].name, "geoip");
        assert_eq!(entries[0].file, "geoip.dat");
        assert_eq!(entries[0].tag, "202611080027");
        assert_eq!(
            entries[0].sha256,
            "eedb24b3b9fcba27e06b92c0428dab90d9496083091ae4cf39e11387fd99d0e2"
        );
        assert!(entries[0].url.contains("/v2fly/geoip/"));
        assert_eq!(entries[1].name, "geosite");
        assert_eq!(entries[1].file, "geosite.dat");
        assert_eq!(entries[1].tag, "20261108011013");
        assert_eq!(
            entries[1].sha256,
            "dc4192dd1b644a38276ed06136d26e729fa674d25785adb38c87e040e92376b2"
        );
        assert!(entries[1].url.contains("/v2fly/domain-list-community/"));
        assert!(entries[1].url.ends_with("/dlc.dat"));

        let bad = "name=geoip\nfile=geoip.dat\ntag=x\nurl=https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/x/geoip.dat\nsha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nsha256_url=https://example.com/a.sha256sum\n";
        let err = parse_geo_lock(bad).unwrap_err().to_string();
        assert!(err.contains("GPL-3"), "{err}");
        let bad = bad.replace("Loyalsoldier/v2ray-rules-dat", "SagerNet/sing-box");
        assert!(parse_geo_lock(&bad)
            .unwrap_err()
            .to_string()
            .contains("GPL-3"));
    }

    #[test]
    fn download_must_match_official_checksum_and_lock() {
        let body = b"not-the-dataset";
        let sum = format!("{}\n", sha256_hex(body));
        accept_download(body, &format!("{sum}  geoip.dat"), &sha256_hex(body)).unwrap();
        let err = accept_download(b"tampered", &format!("{sum}  geoip.dat"), &sha256_hex(body))
            .unwrap_err()
            .to_string();
        assert!(err.contains("not saving"), "{err}");
        let err = accept_download(
            body,
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  geoip.dat\n",
            &sha256_hex(body),
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("does not match the lockfile"), "{err}");
        assert!(parse_sha256sum("abcd  no\n").is_err());
    }

    #[test]
    #[ignore = "loads the fetched v2fly datasets from INFINITUM_GEO_DIR"]
    fn real_v2fly_lists_resolve_known_samples() {
        let dir = std::env::var("INFINITUM_GEO_DIR").expect("INFINITUM_GEO_DIR");
        let db = load_geo_dir(Path::new(&dir)).unwrap();
        assert_eq!(db.lookup_ip("8.8.8.8".parse().unwrap()), Some("US"));
        let cats = db.lookup_domain("www.google.com");
        assert!(cats.iter().any(|c| *c == "GOOGLE"), "{cats:?}");
    }
}
