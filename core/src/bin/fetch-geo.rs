//! Download the locked v2fly geo datasets into `data/geo/`.
//!
//! ```text
//! cargo run --bin fetch-geo
//! cargo run --bin fetch-geo -- --lock core/geo.lock --out data/geo
//! ```
//!
//! Each asset is checked against its official `.sha256sum` file and against `geo.lock`.
//! A mismatch deletes the partial download and exits with an error. The `.dat` files are
//! gitignored. Loyalsoldier and sing-box URLs are rejected by the lock parser (GPL-3).

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::{Command, ExitCode};

use aether_core::geo::{accept_download, parse_geo_lock};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("fetch-geo: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let mut lock = manifest.join("geo.lock");
    let mut out = manifest.join("../data/geo");
    let mut args = env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--lock" => lock = PathBuf::from(args.next().ok_or("--lock needs a path")?),
            "--out" => out = PathBuf::from(args.next().ok_or("--out needs a path")?),
            "-h" | "--help" => {
                println!("usage: fetch-geo [--lock geo.lock] [--out data/geo]");
                return Ok(());
            }
            other => return Err(format!("unknown argument {other}")),
        }
    }
    let text = fs::read_to_string(&lock).map_err(|e| format!("{}: {e}", lock.display()))?;
    let entries = parse_geo_lock(&text).map_err(|e| e.to_string())?;
    fs::create_dir_all(&out).map_err(|e| format!("{}: {e}", out.display()))?;
    for entry in &entries {
        eprintln!("fetch-geo: {} tag {}", entry.name, entry.tag);
        let sum = curl(&entry.sha256_url)?;
        let sum_text =
            String::from_utf8(sum).map_err(|_| format!("{} checksum is not utf-8", entry.name))?;
        let bytes = curl(&entry.url)?;
        accept_download(&bytes, &sum_text, &entry.sha256).map_err(|e| e.to_string())?;
        let dest = out.join(&entry.file);
        let tmp = out.join(format!(".{}.partial", entry.file));
        if let Err(e) = fs::write(&tmp, &bytes) {
            let _ = fs::remove_file(&tmp);
            return Err(format!("{}: {e}", tmp.display()));
        }
        if let Err(e) = fs::rename(&tmp, &dest) {
            let _ = fs::remove_file(&tmp);
            return Err(format!("{}: {e}", dest.display()));
        }
        println!("{} {} {}", entry.file, entry.tag, entry.sha256);
    }
    Ok(())
}

fn curl(url: &str) -> Result<Vec<u8>, String> {
    let out = Command::new("curl")
        .args(["-fsSL", "--retry", "3", "-A", "aether-fetch-geo", url])
        .output()
        .map_err(|e| format!("curl is required to fetch geo datasets: {e}"))?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        return Err(format!("download failed for {url}: {}", err.trim()));
    }
    Ok(out.stdout)
}
