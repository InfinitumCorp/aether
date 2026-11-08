//! `aether-ca`: issue and check ÆTHER device certificates (modelled on cfssl's CLI).
//!
//! ```text
//! aether-ca init   --key ca.key                                  # new CA; prints its public key
//! aether-ca sign   --key ca.key --device <64 hex> --label NAME
//!                  [--profile node|relay|operator] [--expiry 8760h] # prints the certificate
//! aether-ca verify --ca <64 hex> --device <64 hex> --cert <hex>  # checks a certificate now
//! aether-ca info   --cert <hex>                                  # decodes without verifying
//! ```
//!
//! With the `registry` feature (`cargo run --features registry --bin aether-ca -- ...`) the
//! tool also keeps the Supabase certificate registry (schema `aether`) up to date:
//!
//! ```text
//! aether-ca init        --key ca.key --register --label "Org CA"    # also records the CA
//! aether-ca register-ca --key ca.key --label "Org CA"               # records an existing CA
//! aether-ca sign        ... --register                              # records cert + enrols device
//! aether-ca revoke      --serial <32 hex> [--reason TEXT]
//! aether-ca status      --serial <32 hex>                           # valid|revoked|expired|unknown
//! aether-ca verify      ... --online                                # also fails if revoked
//! ```
//!
//! Registry access reads `AETHER_SUPABASE_URL` (default: the Infinitum Corp project) and
//! `AETHER_SUPABASE_SERVICE_KEY` (the service-role or `sb_secret_` key) from the environment.
//! Never pass the key on the command line or commit it.
//!
//! Output is one JSON object on stdout, like cfssl. The CA key file holds the 32-byte secret
//! seed in hex; keep it offline.

use std::collections::HashMap;
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use aether_core::{decode_device_certificate, verify_device_certificate, CertProfile, CertificateAuthority, DeviceCertificate};

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex(s: &str) -> Result<Vec<u8>, String> {
    let s = s.trim();
    if s.len() % 2 != 0 || !s.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err("expected hex".into());
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.to_string())).collect()
}

fn json_str(s: &str) -> String {
    let mut o = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// cfssl-style durations: `8760h`, `90d`, `30m`.
fn parse_expiry(s: &str) -> Result<u64, String> {
    let (num, unit) = s.split_at(s.len().saturating_sub(1));
    let n: u64 = num.parse().map_err(|_| format!("bad expiry {s:?}"))?;
    let ms = match unit {
        "h" => 3_600_000,
        "d" => 86_400_000,
        "m" => 60_000,
        _ => return Err(format!("bad expiry {s:?} (use h, d or m)")),
    };
    n.checked_mul(ms).filter(|&v| v > 0).ok_or_else(|| format!("bad expiry {s:?}"))
}

mod registry {
    #[cfg(feature = "registry")]
    pub const DEFAULT_URL: &str = "https://ivqiskeziohbbtkcvlun.supabase.co";

    /// Calls `POST /rest/v1/rpc/<function>` with a JSON body; returns the raw JSON response.
    #[cfg(feature = "registry")]
    pub fn rpc(function: &str, body: &str) -> Result<String, String> {
        let url = std::env::var("AETHER_SUPABASE_URL").unwrap_or_else(|_| DEFAULT_URL.to_owned());
        let key = std::env::var("AETHER_SUPABASE_SERVICE_KEY")
            .map_err(|_| "set AETHER_SUPABASE_SERVICE_KEY (service-role key) to use the registry".to_owned())?;
        let mut req = ureq::post(&format!("{}/rest/v1/rpc/{function}", url.trim_end_matches('/')))
            .set("apikey", &key)
            .set("Content-Type", "application/json");
        if key.starts_with("eyJ") {
            // Legacy JWT keys also go in Authorization; new sb_secret_ keys must not.
            req = req.set("Authorization", &format!("Bearer {key}"));
        }
        match req.send_string(body) {
            Ok(r) => r.into_string().map_err(|e| e.to_string()),
            Err(ureq::Error::Status(code, r)) => {
                let text = r.into_string().unwrap_or_default();
                Err(format!("registry refused {function} (HTTP {code}): {}", text.chars().take(300).collect::<String>()))
            }
            Err(e) => Err(format!("registry unreachable: {e}")),
        }
    }

    #[cfg(not(feature = "registry"))]
    pub fn rpc(_function: &str, _body: &str) -> Result<String, String> {
        Err("built without the registry feature; run with `cargo run --features registry --bin aether-ca -- ...`".into())
    }
}

fn register_ca(ca: &CertificateAuthority, label: &str) -> Result<String, String> {
    let body = format!("{{\"p_public_key_hex\":{},\"p_label\":{}}}", json_str(&hex(&ca.public_key())), json_str(label));
    let id = registry::rpc("aether_register_ca", &body)?;
    if id.trim().trim_matches('"') != ca.key_id() {
        return Err(format!("registry returned key id {id}, expected {}", ca.key_id()));
    }
    Ok(id.trim().to_owned())
}

fn record_certificate(c: &DeviceCertificate, cert: &[u8]) -> Result<String, String> {
    let body = format!(
        "{{\"p_serial\":{},\"p_ca_key_id\":{},\"p_device_key_hex\":{},\"p_label\":{},\"p_profile\":{},\"p_not_before_ms\":{},\"p_not_after_ms\":{},\"p_certificate_hex\":{}}}",
        json_str(&c.serial),
        json_str(&c.ca_key_id),
        json_str(&hex(&c.device_public_key)),
        json_str(&c.label),
        json_str(c.profile.name()),
        c.not_before_ms,
        c.not_after_ms,
        json_str(&hex(cert)),
    );
    registry::rpc("aether_record_certificate", &body).map(|s| s.trim().to_owned())
}

fn check_serial(s: &str) -> Result<(), String> {
    if s.len() == 32 && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) {
        Ok(())
    } else {
        Err("serial must be 32 lowercase hex characters".into())
    }
}

/// The registry's status for `serial`: (`status`, raw JSON row).
fn registry_status(serial: &str) -> Result<(String, String), String> {
    check_serial(serial)?;
    let raw = registry::rpc("aether_certificate_status", &format!("{{\"p_serial\":{}}}", json_str(serial)))?;
    let status = ["valid", "revoked", "expired", "unknown"]
        .into_iter()
        .find(|s| json_string_field(&raw, "status").as_deref() == Some(*s))
        .ok_or_else(|| format!("unexpected registry reply: {raw}"))?;
    let row = raw.trim().trim_start_matches('[').trim_end_matches(']').to_owned();
    Ok((status.to_owned(), row))
}

/// Value of the first `"name": "string"` member in a JSON text, tolerating any whitespace.
/// A `"name"` inside another string value is escaped (`\"name\"`) and therefore skipped.
fn json_string_field(raw: &str, name: &str) -> Option<String> {
    let pat = format!("\"{name}\"");
    let mut from = 0;
    while let Some(i) = raw[from..].find(&pat).map(|i| i + from) {
        from = i + pat.len();
        if raw[..i].ends_with('\\') {
            continue;
        }
        let rest = raw[from..].trim_start().strip_prefix(':')?.trim_start().strip_prefix('"')?;
        return rest.find('"').map(|end| rest[..end].to_owned());
    }
    None
}

fn cert_json(c: &DeviceCertificate, extra: &str) -> String {
    format!(
        "{{{extra}\"serial\":{},\"device_public_key\":{},\"label\":{},\"profile\":{},\"not_before_ms\":{},\"not_after_ms\":{},\"ca_key_id\":{}}}",
        json_str(&c.serial),
        json_str(&hex(&c.device_public_key)),
        json_str(&c.label),
        json_str(c.profile.name()),
        c.not_before_ms,
        c.not_after_ms,
        json_str(&c.ca_key_id),
    )
}

fn run(args: &[String]) -> Result<String, String> {
    let cmd = args.first().ok_or("usage: aether-ca init|sign|verify|info|register-ca|revoke|status [flags]")?;
    let mut flags = HashMap::new();
    let mut it = args[1..].iter();
    while let Some(k) = it.next() {
        let k = k.strip_prefix("--").ok_or_else(|| format!("unexpected argument {k:?}"))?;
        if matches!(k, "register" | "online") {
            flags.insert(k.to_owned(), String::new());
            continue;
        }
        let v = it.next().ok_or_else(|| format!("--{k} needs a value"))?;
        flags.insert(k.to_owned(), v.clone());
    }
    let need = |k: &str| flags.get(k).cloned().ok_or_else(|| format!("missing --{k}"));
    let load_ca = |path: &str| -> Result<std::sync::Arc<CertificateAuthority>, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
        CertificateAuthority::from_secret(unhex(&text)?).map_err(|e| e.to_string())
    };

    match cmd.as_str() {
        "init" => {
            let path = need("key")?;
            if std::path::Path::new(&path).exists() {
                return Err(format!("{path} already exists; refusing to overwrite a CA key"));
            }
            let register = flags.contains_key("register");
            let label = if register { Some(need("label")?) } else { None };
            let ca = CertificateAuthority::generate();
            std::fs::write(&path, hex(&ca.export_secret())).map_err(|e| format!("{path}: {e}"))?;
            let registered = match label {
                Some(l) => format!(",\"registered\":{}", register_ca(&ca, &l).map(|_| "true".to_owned()).unwrap_or_else(|e| json_str(&e))),
                None => String::new(),
            };
            Ok(format!("{{\"ca_public_key\":{},\"ca_key_id\":{}{registered}}}", json_str(&hex(&ca.public_key())), json_str(&ca.key_id())))
        }
        "register-ca" => {
            let ca = load_ca(&need("key")?)?;
            let id = register_ca(&ca, &need("label")?)?;
            Ok(format!("{{\"registered\":true,\"ca_key_id\":{id}}}"))
        }
        "sign" => {
            let ca = load_ca(&need("key")?)?;
            let device = unhex(&need("device")?)?;
            let profile = match flags.get("profile") {
                Some(p) => CertProfile::parse(p).ok_or_else(|| format!("unknown profile {p:?}"))?,
                None => CertProfile::Node,
            };
            let life = match flags.get("expiry") {
                Some(e) => parse_expiry(e)?,
                None => profile.default_expiry_hours() * 3_600_000,
            };
            let nb = now_ms();
            let cert = ca.issue(device, need("label")?, profile, nb, nb + life).map_err(|e| e.to_string())?;
            let c = decode_device_certificate(cert.clone()).map_err(|e| e.to_string())?;
            let mut extra = format!("\"cert\":{},", json_str(&hex(&cert)));
            if flags.contains_key("register") {
                // The certificate is printed even if recording fails, so it is never lost.
                match record_certificate(&c, &cert) {
                    Ok(device_id) => extra.push_str(&format!("\"registered\":true,\"device_id\":{device_id},")),
                    Err(e) => extra.push_str(&format!("\"registered\":false,\"registry_error\":{},", json_str(&e))),
                }
            }
            Ok(cert_json(&c, &extra))
        }
        "verify" => {
            let c = verify_device_certificate(unhex(&need("cert")?)?, unhex(&need("ca")?)?, unhex(&need("device")?)?, now_ms())
                .map_err(|e| e.to_string())?;
            if flags.contains_key("online") {
                let (status, _) = registry_status(&c.serial)?;
                if status != "valid" {
                    return Err(format!("certificate {} is {status} in the registry", c.serial));
                }
            }
            Ok(cert_json(&c, "\"valid\":true,"))
        }
        "revoke" => {
            let serial = need("serial")?;
            check_serial(&serial)?;
            let reason = flags.get("reason").map(|r| json_str(r)).unwrap_or_else(|| "null".into());
            let body = format!("{{\"p_serial\":{},\"p_reason\":{reason}}}", json_str(&serial));
            let done = registry::rpc("aether_revoke_certificate", &body)?;
            if done.trim() != "true" {
                return Err(format!("{serial} is unknown or already revoked"));
            }
            Ok(format!("{{\"revoked\":true,\"serial\":{}}}", json_str(&serial)))
        }
        "status" => Ok(registry_status(&need("serial")?)?.1),
        "info" => {
            let c = decode_device_certificate(unhex(&need("cert")?)?).map_err(|e| e.to_string())?;
            Ok(cert_json(&c, ""))
        }
        other => Err(format!("unknown command {other:?}")),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(out) => {
            println!("{out}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("aether-ca: {e}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::json_string_field;

    #[test]
    fn reads_status_from_compact_and_spaced_json() {
        assert_eq!(json_string_field(r#"[{"status":"valid","label":"A"}]"#, "status").as_deref(), Some("valid"));
        assert_eq!(json_string_field("[{\"status\" : \"revoked\", \"label\": \"A\"}]", "status").as_deref(), Some("revoked"));
    }

    #[test]
    fn ignores_escaped_name_inside_another_value() {
        let raw = r#"[{"label":"x \"status\":\"valid\"","status":"revoked"}]"#;
        assert_eq!(json_string_field(raw, "status").as_deref(), Some("revoked"));
    }

    #[test]
    fn missing_or_non_string_is_none() {
        assert_eq!(json_string_field(r#"{"label":"A"}"#, "status"), None);
        assert_eq!(json_string_field(r#"{"status":null}"#, "status"), None);
    }
}
