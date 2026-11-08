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
    let cmd = args.first().ok_or("usage: aether-ca init|sign|verify|info [flags]")?;
    let mut flags = HashMap::new();
    let mut it = args[1..].iter();
    while let Some(k) = it.next() {
        let k = k.strip_prefix("--").ok_or_else(|| format!("unexpected argument {k:?}"))?;
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
            let ca = CertificateAuthority::generate();
            std::fs::write(&path, hex(&ca.export_secret())).map_err(|e| format!("{path}: {e}"))?;
            Ok(format!("{{\"ca_public_key\":{},\"ca_key_id\":{}}}", json_str(&hex(&ca.public_key())), json_str(&ca.key_id())))
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
            Ok(cert_json(&c, &format!("\"cert\":{},", json_str(&hex(&cert)))))
        }
        "verify" => {
            let c = verify_device_certificate(unhex(&need("cert")?)?, unhex(&need("ca")?)?, unhex(&need("device")?)?, now_ms())
                .map_err(|e| e.to_string())?;
            Ok(cert_json(&c, "\"valid\":true,"))
        }
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
