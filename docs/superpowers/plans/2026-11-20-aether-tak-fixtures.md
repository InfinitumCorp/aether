# TAK lab fixtures implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classify synthetic TAK XML and binary framing, and reject hostile zip archives, with unit tests and no network, no sockets, and no returned coordinates.

**Architecture:** A private `core/src/tak.rs` module parses one buffer at a time. XML uses `quick-xml` 0.37.5, already in the core. Binary framing is a `0xbf` header plus a protobuf-style varint. Zip inspection uses `zip` 2.3.0 only after its license file says MIT. Nothing is sent. `FramingOnly` and `StructuralOk` are not a claim that ATAK or iTAK will accept the bytes.

**Tech Stack:** `aether-core` (edition 2021), `quick-xml` 0.37.5 (existing), `zip` =2.3.0 (new, MIT, default features). No protobuf crate. No UniFFI.

## Global Constraints

- This cycle does not talk to a TAK Server, an ATAK device, or an iTAK device, and it does not claim that any client version is compatible.
- No UniFFI export. No socket types. No `std::net`.
- The production allowlist is empty. There is no allowlist type and no send function.
- `aether-lab-only` is a lab type. The gateway, when it exists, will not send it.
- Do not decode protobuf `TakMessage`.
- Do not invent a GeoChat schema or a data-package manifest.
- The functions never panic on untrusted bytes. They have no `log` crate call and no `println`. They do not call `encode_position`, `decode_position`, or the field codecs. They do not read the clock, the disk, or the environment.
- `Debug` on `TakClass` does not include input bytes. Do not `format!` an input slice.
- Single XML buffer and declared binary payload: 256 KiB (`262_144` bytes). Varint at most 5 bytes. XML start tags in one event: 64. Archive compressed size and uncompressed running total: 8 MiB (`8_388_608` bytes). Archive entries: 32. Entry name: 128 bytes, UTF-8, no NUL.
- A varint is an unsigned protobuf varint: groups of 7 bits, least significant group first. More than 5 bytes, a value above `u32::MAX`, or an overlong encoding (more than one byte and the last byte is `0x00`) is `BadVarint`.
- Declared payload length 0 is `LengthMismatch`.
- Tests call only the function named for that fixture. Do not cross-call mesh and streaming functions.
- If the zip crate's license is not MIT or Apache-2.0, do not keep the dependency. XML and binary classifiers still ship.
- No UI file, no Android manifest change, no `aether-ca` change, no browser change.
- A green `cargo test` does not prove that ATAK-CIV, iTAK, WinTAK, or any TAK Server build will parse a buffer.

---

## Current interfaces this plan uses

- `core/Cargo.toml` already depends on `quick-xml` 0.37.5. There is no `zip` dependency. `core/src/lib.rs` has private modules `mod nav; mod session; mod vault;` and no `mod tak`.
- `quick-xml` 0.37.5 `Error` variants used here: `Escape(EscapeError)` and `IllFormed(IllFormedError::MissingEndTag(_))`. Other reader errors are `NotXml`.
- `BytesStart::local_name()` and attribute `key.local_name()` return bytes. Do not unescape attribute values.
- `zip` 2.3.0 (`https://docs.rs/zip/2.3.0`) `ZipWriter::start_file`, `add_symlink`, `ZipFile::encrypted`, `ZipFile::is_symlink`, `ZipFile::name_raw`, `ZipFile::size`, `ZipFile::compressed_size`. Encryption fixtures use `zip::unstable::write::FileOptionsExt::with_deprecated_encryption`. That API is only for building a test archive. The inspector never decrypts and never uses ZipCrypto on a real package.
- `core/src/gpx.rs` is a separate XML parser. Do not change it and do not call it.

## File map

| File | Action |
|---|---|
| `core/src/tak.rs` | Create. Classifiers, archive inspector, tests |
| `core/src/lib.rs` | One line: `mod tak;` |
| `core/Cargo.toml` | `zip = "=2.3.0"` after the license check |
| `core/Cargo.lock` | Lockfile update from that add |
| `.github/workflows/test-tak.yml` | Create. `cargo test` only |

Leave untouched: Android, `web/`, `aether-ca`, Supabase, `docs/ARCHITECTURE.md`, `auto-deploy.yml`.

Shared-file note: cycle A's plan inserts `mod revocation;` after `mod nav;`. This plan inserts `mod tak;` after `mod session;`. Keep both lines if both plans have landed.

---

### Task 1: XML classifier

**Files:**
- Create: `core/src/tak.rs`
- Modify: `core/src/lib.rs` (one module line)
- Test: `core/src/tak.rs` tests

**Interfaces:**
- Consumes: `quick_xml::Reader` and `quick_xml::events::Event`.
- Produces: `pub(crate) fn classify_tak_xml(bytes: &[u8]) -> TakClass` with `TakClass::{Malformed { fault: TakFault }, RejectedPosition, StructuralOk}` and `TakFault` unit variants `Oversize`, `BadVarint`, `LengthMismatch`, `MissingMagic`, `Doctype`, `Entity`, `NotXml`, `Unclosed`, `TooManyTags`. `Display` phrases are fixed strings listed in step 3. No send function.

- [ ] **Step 1: Write the failing test**

Add `mod tak;` in `core/src/lib.rs` after `mod session;`.

Create `core/src/tak.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const MINIMAL: &str = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only"></event>"#;

    #[test]
    fn minimal_event_is_structural_and_position_markers_are_rejected() {
        assert!(matches!(classify_tak_xml(MINIMAL.as_bytes()), TakClass::StructuralOk));
        assert!(!format!("{:?}", classify_tak_xml(MINIMAL.as_bytes())).contains("aether-lab"));
        let point = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only"><point/></event>"#;
        assert!(matches!(classify_tak_xml(point.as_bytes()), TakClass::RejectedPosition));
        let lat = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only" lat=""></event>"#;
        assert!(matches!(classify_tak_xml(lat.as_bytes()), TakClass::RejectedPosition));
        let upper = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only"><Point/></event>"#;
        assert!(matches!(classify_tak_xml(upper.as_bytes()), TakClass::RejectedPosition));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml tak::tests::minimal_event_is_structural_and_position_markers_are_rejected -- --nocapture`

Expected: FAIL to compile. `classify_tak_xml` is not defined.

- [ ] **Step 3: Write minimal implementation**

Put this above the tests. Keep the test. `Display` phrases:

| Fault | Phrase |
|---|---|
| Oversize | `tak frame is too large` |
| BadVarint | `tak varint is invalid` |
| LengthMismatch | `tak frame length does not match` |
| MissingMagic | `tak frame magic is missing` |
| Doctype | `tak xml contains a doctype` |
| Entity | `tak xml contains an entity` |
| NotXml | `tak xml is not an event` |
| Unclosed | `tak xml event is unclosed` |
| TooManyTags | `tak xml has too many tags` |

```rust
use std::fmt;

use quick_xml::events::Event;
use quick_xml::Reader;

const XML_MAX: usize = 262_144;
const MAX_TAGS: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TakFault {
    Oversize,
    BadVarint,
    LengthMismatch,
    MissingMagic,
    Doctype,
    Entity,
    NotXml,
    Unclosed,
    TooManyTags,
}

impl fmt::Display for TakFault {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Oversize => "tak frame is too large",
            Self::BadVarint => "tak varint is invalid",
            Self::LengthMismatch => "tak frame length does not match",
            Self::MissingMagic => "tak frame magic is missing",
            Self::Doctype => "tak xml contains a doctype",
            Self::Entity => "tak xml contains an entity",
            Self::NotXml => "tak xml is not an event",
            Self::Unclosed => "tak xml event is unclosed",
            Self::TooManyTags => "tak xml has too many tags",
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TakClass {
    Malformed { fault: TakFault },
    RejectedPosition,
    StructuralOk,
    FramingOnly,
}

pub(crate) fn classify_tak_xml(bytes: &[u8]) -> TakClass {
    if bytes.len() > XML_MAX {
        return TakClass::Malformed { fault: TakFault::Oversize };
    }
    if contains_doctype(bytes) {
        return TakClass::Malformed { fault: TakFault::Doctype };
    }
    let mut reader = Reader::from_reader(bytes);
    reader.config_mut().trim_text(true);
    let mut tags = 0usize;
    let mut depth = 0i32;
    let mut saw_root = false;
    let mut closed = false;
    loop {
        match reader.read_event() {
            Ok(Event::Eof) => break,
            Ok(Event::Decl(_)) => {}
            Ok(Event::Comment(_)) | Ok(Event::PI(_)) | Ok(Event::DocType(_)) => {
                return TakClass::Malformed { fault: TakFault::NotXml };
            }
            Ok(Event::Text(t)) => {
                if closed && !t.as_ref().iter().all(|b| b.is_ascii_whitespace()) {
                    return TakClass::Malformed { fault: TakFault::NotXml };
                }
            }
            Ok(Event::CData(_)) => return TakClass::Malformed { fault: TakFault::NotXml },
            Ok(Event::Start(e)) => match on_tag(&e, true, &mut tags, &mut depth, &mut saw_root, &mut closed) {
                Ok(()) => {}
                Err(class) => return class,
            },
            Ok(Event::Empty(e)) => match on_tag(&e, false, &mut tags, &mut depth, &mut saw_root, &mut closed) {
                Ok(()) => {}
                Err(class) => return class,
            },
            Ok(Event::End(_)) => {
                depth -= 1;
                if depth == 0 {
                    closed = true;
                }
                if depth < 0 {
                    return TakClass::Malformed { fault: TakFault::NotXml };
                }
            }
            Err(quick_xml::Error::Escape(_)) => return TakClass::Malformed { fault: TakFault::Entity },
            Err(quick_xml::Error::IllFormed(quick_xml::errors::IllFormedError::MissingEndTag(_))) => {
                return TakClass::Malformed { fault: TakFault::Unclosed };
            }
            Err(_) => return TakClass::Malformed { fault: TakFault::NotXml },
        }
    }
    if saw_root && closed && depth == 0 {
        TakClass::StructuralOk
    } else if saw_root && !closed {
        TakClass::Malformed { fault: TakFault::Unclosed }
    } else {
        TakClass::Malformed { fault: TakFault::NotXml }
    }
}

fn on_tag(
    e: &quick_xml::events::BytesStart<'_>,
    is_start: bool,
    tags: &mut usize,
    depth: &mut i32,
    saw_root: &mut bool,
    closed: &mut bool,
) -> Result<(), TakClass> {
    *tags += 1;
    if *tags > MAX_TAGS {
        return Err(TakClass::Malformed { fault: TakFault::TooManyTags });
    }
    if position_marker(e)? {
        return Err(TakClass::RejectedPosition);
    }
    let name = e.local_name();
    if *depth == 0 {
        if *closed || name.as_ref() != b"event" {
            return Err(TakClass::Malformed { fault: TakFault::NotXml });
        }
        *saw_root = true;
        if !is_start {
            *closed = true;
        }
    }
    if is_start {
        *depth += 1;
    }
    Ok(())
}

fn contains_doctype(bytes: &[u8]) -> bool {
    bytes.windows(9).any(|w| w.eq_ignore_ascii_case(b"<!DOCTYPE"))
}

fn position_marker(e: &quick_xml::events::BytesStart<'_>) -> Result<bool, TakClass> {
    if e.local_name().as_ref().eq_ignore_ascii_case(b"point") {
        return Ok(true);
    }
    for attr in e.attributes() {
        let attr = attr.map_err(|_| TakClass::Malformed { fault: TakFault::NotXml })?;
        let key = attr.key.local_name();
        if key.as_ref().eq_ignore_ascii_case(b"lat") || key.as_ref().eq_ignore_ascii_case(b"lon") {
            return Ok(true);
        }
    }
    Ok(false)
}
```

Do not read `attr.value` into a `String`. Do not `format!` a tag, an attribute, or the input. `DocType` inside the reader is `NotXml`; the `<!DOCTYPE` byte scan is what returns `Doctype`, and it runs first.

Add tests in the same module, each asserting the class only:

- `&amp;` inside the minimal event is `StructuralOk`.
- `&aetherlab;` inside the event is `Malformed { fault: Entity }`.
- `<!DOCTYPE event [<!ENTITY x "y">]><event></event>` is `Doctype` (the pre-scan, so the entity is not expanded).
- `<?xml version="1.0"?><event version="2.0" type="aether-lab-only">` with no end tag is `Unclosed`.
- `<?xml version="1.0"?><note></note>` is `NotXml`.
- A `vec` of length `262_145` is `Oversize`.
- A comment `<!--x-->` before the event is `NotXml`.
- 65 start tags (`event` plus 64 child `a` elements) is `TooManyTags`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml tak:: -- --nocapture`

Expected: PASS. `cargo test --manifest-path core/Cargo.toml` still passes the existing suite.

- [ ] **Step 5: Commit**

```bash
git add core/src/tak.rs core/src/lib.rs
git commit -m "core: classify synthetic TAK xml fixtures"
```

---

### Task 2: Binary framing

**Files:**
- Modify: `core/src/tak.rs` (varint, `classify_tak_stream_frame`, `classify_tak_mesh_header`, tests)
- Test: `core/src/tak.rs`

**Interfaces:**
- Consumes: `TakClass` and `TakFault` from Task 1.
- Produces: `pub(crate) fn classify_tak_stream_frame(bytes: &[u8]) -> TakClass` and `pub(crate) fn classify_tak_mesh_header(bytes: &[u8]) -> TakClass`. Success with a non-empty payload whose length matches is `FramingOnly`. The payload bytes are not returned.

- [ ] **Step 1: Write the failing test**

```rust
#[test]
fn stream_frame_matches_length_and_rejects_bad_headers() {
    assert!(matches!(classify_tak_stream_frame(&[0xbf, 0x01, 0x00]), TakClass::FramingOnly));
    assert!(matches!(
        classify_tak_stream_frame(&[0xbf, 0x02, 0x00]),
        TakClass::Malformed { fault: TakFault::LengthMismatch }
    ));
    assert!(matches!(
        classify_tak_stream_frame(&[0x00]),
        TakClass::Malformed { fault: TakFault::MissingMagic }
    ));
    assert!(matches!(
        classify_tak_stream_frame(&[0xbf, 0x00]),
        TakClass::Malformed { fault: TakFault::LengthMismatch }
    ));
    assert!(matches!(
        classify_tak_stream_frame(&[0xbf, 0x80, 0x00, 0x00]),
        TakClass::Malformed { fault: TakFault::BadVarint }
    ));
    assert!(matches!(classify_tak_mesh_header(&[0xbf, 0x01, 0x11]), TakClass::FramingOnly));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml tak::tests::stream_frame_matches_length_and_rejects_bad_headers -- --nocapture`

Expected: FAIL to compile. `classify_tak_stream_frame` is not defined.

- [ ] **Step 3: Write minimal implementation**

```rust
const FRAME_MAX: usize = 262_144;

fn read_varint(bytes: &[u8]) -> Result<(u32, usize), TakFault> {
    if bytes.is_empty() {
        return Err(TakFault::BadVarint);
    }
    let mut value: u32 = 0;
    let mut shift = 0u32;
    for (i, byte) in bytes.iter().take(5).enumerate() {
        let bits = (byte & 0x7f) as u32;
        if shift == 28 && bits > 0x0f {
            return Err(TakFault::BadVarint);
        }
        value |= bits << shift;
        if byte & 0x80 == 0 {
            if i > 0 && *byte == 0 {
                return Err(TakFault::BadVarint);
            }
            return Ok((value, i + 1));
        }
        shift += 7;
    }
    Err(TakFault::BadVarint)
}

pub(crate) fn classify_tak_stream_frame(bytes: &[u8]) -> TakClass {
    let Some((&0xbf, rest)) = bytes.split_first() else {
        return TakClass::Malformed { fault: TakFault::MissingMagic };
    };
    let (len, width) = match read_varint(rest) {
        Ok(v) => v,
        Err(fault) => return TakClass::Malformed { fault },
    };
    if len == 0 {
        return TakClass::Malformed { fault: TakFault::LengthMismatch };
    }
    if len as usize > FRAME_MAX {
        return TakClass::Malformed { fault: TakFault::Oversize };
    }
    if rest.len() != width + len as usize {
        return TakClass::Malformed { fault: TakFault::LengthMismatch };
    }
    TakClass::FramingOnly
}

pub(crate) fn classify_tak_mesh_header(bytes: &[u8]) -> TakClass {
    let Some((&0xbf, rest)) = bytes.split_first() else {
        return TakClass::Malformed { fault: TakFault::MissingMagic };
    };
    let (_version, width) = match read_varint(rest) {
        Ok(v) => v,
        Err(fault) => return TakClass::Malformed { fault },
    };
    let payload = rest.len().saturating_sub(width);
    if payload == 0 {
        return TakClass::Malformed { fault: TakFault::LengthMismatch };
    }
    if payload > FRAME_MAX {
        return TakClass::Malformed { fault: TakFault::Oversize };
    }
    TakClass::FramingOnly
}
```

The version value is parsed and discarded. Do not compare it to 1. Do not inspect payload contents. Add a test that six continuation bytes after `0xbf` (`[0xbf, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00]`) is `BadVarint`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml tak:: -- --nocapture`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/tak.rs
git commit -m "core: classify synthetic TAK binary framing"
```

---

### Task 3: Archive inspector

**Files:**
- Modify: `core/Cargo.toml` and `core/Cargo.lock`
- Modify: `core/src/tak.rs`
- Test: `core/src/tak.rs`

**Interfaces:**
- Consumes: `zip` 2.3.0 `ZipArchive`, `ZipWriter`, `zip::write::FileOptions`, `ZipFile::encrypted`, `ZipFile::is_symlink`, `ZipFile::name_raw`, `ZipFile::size`, `ZipFile::compressed_size`.
- Produces: `pub(crate) fn inspect_tak_archive(bytes: &[u8]) -> ArchiveClass` where `ArchiveClass` is `Ok { entries: u16 }` or `Rejected { fault: ArchiveFault }` and `ArchiveFault` is `Oversize`, `TooManyEntries`, `Path`, `Link`, `EncryptedEntry`, `NestedArchive`, `BadZip`, `Expansion`. `entries` is a count. Names are not returned.

- [ ] **Step 1: Write the failing test**

Append tests that call `inspect_tak_archive`. They will not compile until the function exists. Build archives with the helper added in step 3; for this red step, call `inspect_tak_archive(b"not a zip")` and expect `BadZip`, plus `inspect_tak_archive(&vec![0u8; 8_388_609])` and expect `Oversize`.

```rust
#[test]
fn oversized_buffer_and_non_zip_are_rejected() {
    assert!(matches!(
        inspect_tak_archive(b"not a zip"),
        ArchiveClass::Rejected { fault: ArchiveFault::BadZip }
    ));
    let big = vec![0u8; 8_388_609];
    assert!(matches!(
        inspect_tak_archive(&big),
        ArchiveClass::Rejected { fault: ArchiveFault::Oversize }
    ));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml tak::tests::oversized_buffer_and_non_zip_are_rejected -- --nocapture`

Expected: FAIL to compile. `inspect_tak_archive` is not defined.

- [ ] **Step 3: Write minimal implementation**

Before adding the dependency, run:

```bash
cargo fetch --manifest-path core/Cargo.toml
```

Then add to `core/Cargo.toml` dependencies:

```toml
zip = "=2.3.0"
```

Run `cargo fetch --manifest-path core/Cargo.toml` again. Open the fetched `zip-2.3.0/Cargo.toml` and read the `license` field. Expected: `MIT`. If it is anything else, remove the dependency and skip the rest of this task. XML and binary tests from Tasks 1 and 2 must still pass. The implementation PR states that the archive inspector was omitted and why.

If the license is MIT, implement:

```rust
const ARCHIVE_MAX: u64 = 8_388_608;
const MAX_ENTRIES: usize = 32;
const MAX_NAME: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ArchiveFault {
    Oversize,
    TooManyEntries,
    Path,
    Link,
    EncryptedEntry,
    NestedArchive,
    BadZip,
    Expansion,
}

impl fmt::Display for ArchiveFault {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Oversize => "tak archive is too large",
            Self::TooManyEntries => "tak archive has too many entries",
            Self::Path => "tak archive path is rejected",
            Self::Link => "tak archive link is rejected",
            Self::EncryptedEntry => "tak archive entry is encrypted",
            Self::NestedArchive => "tak archive contains a nested archive",
            Self::BadZip => "tak archive is not a zip",
            Self::Expansion => "tak archive expands too far",
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ArchiveClass {
    Ok { entries: u16 },
    Rejected { fault: ArchiveFault },
}

pub(crate) fn inspect_tak_archive(bytes: &[u8]) -> ArchiveClass {
    if bytes.len() as u64 > ARCHIVE_MAX {
        return ArchiveClass::Rejected { fault: ArchiveFault::Oversize };
    }
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = match zip::ZipArchive::new(cursor) {
        Ok(a) => a,
        Err(_) => return ArchiveClass::Rejected { fault: ArchiveFault::BadZip },
    };
    if archive.len() > MAX_ENTRIES {
        return ArchiveClass::Rejected { fault: ArchiveFault::TooManyEntries };
    }
    let mut uncompressed: u64 = 0;
    let mut compressed: u64 = 0;
    for i in 0..archive.len() {
        let file = match archive.by_index(i) {
            Ok(f) => f,
            Err(_) => return ArchiveClass::Rejected { fault: ArchiveFault::BadZip },
        };
        if file.encrypted() {
            return ArchiveClass::Rejected { fault: ArchiveFault::EncryptedEntry };
        }
        if file.is_symlink() {
            return ArchiveClass::Rejected { fault: ArchiveFault::Link };
        }
        if let Some(fault) = reject_name(file.name_raw()) {
            return ArchiveClass::Rejected { fault };
        }
        uncompressed = uncompressed.saturating_add(file.size());
        compressed = compressed.saturating_add(file.compressed_size());
        if uncompressed > ARCHIVE_MAX {
            return ArchiveClass::Rejected { fault: ArchiveFault::Expansion };
        }
        if compressed > ARCHIVE_MAX {
            return ArchiveClass::Rejected { fault: ArchiveFault::Oversize };
        }
    }
    ArchiveClass::Ok { entries: archive.len() as u16 }
}

fn reject_name(name: &[u8]) -> Option<ArchiveFault> {
    if name.is_empty() || name.len() > MAX_NAME || name.contains(&0) || std::str::from_utf8(name).is_err() {
        return Some(ArchiveFault::Path);
    }
    if name.iter().any(|b| *b == b'\\') || name[0] == b'/' || name.windows(2).any(|w| w[1] == b':' && w[0].is_ascii_alphabetic()) {
        return Some(ArchiveFault::Path);
    }
    if name.split(|b| *b == b'/').any(|part| part == b"..") {
        return Some(ArchiveFault::Path);
    }
    if name.len() >= 4 && name[name.len() - 4..].eq_ignore_ascii_case(b".zip") {
        return Some(ArchiveFault::NestedArchive);
    }
    None
}
```

Do not call `Read` on the `ZipFile`. Do not call `enclosed_name` or `sanitized_name` (those strip `..`). The zip format has no hard-link type. `is_symlink()` is the link check. Do not invent a hard-link flag.

Test helper, used only under `#[cfg(test)]`:

```rust
fn zip_bytes(build: impl FnOnce(&mut zip::ZipWriter<std::io::Cursor<Vec<u8>>>) -> zip::result::ZipResult<()>) -> Vec<u8> {
    let mut cursor = std::io::Cursor::new(Vec::new());
    let mut writer = zip::ZipWriter::new(&mut cursor);
    build(&mut writer).unwrap();
    writer.finish().unwrap();
    cursor.into_inner()
}
```

Fixtures, each a separate assertion:

- `start_file("ok.txt", zip::write::FileOptions::<()>::default().compression_method(zip::CompressionMethod::Stored))`, write `b"a"`, finish: `Ok { entries: 1 }`.
- Entry name `certs/../escape`: `Path`.
- Entry name `/tmp/x`: `Path`.
- `add_symlink("link", "target", zip::write::FileOptions::<()>::default())`: `Link`.
- `start_file("secret.txt", zip::write::FileOptions::<()>::default().compression_method(zip::CompressionMethod::Stored).with_deprecated_encryption(b"lab"))` after `use zip::unstable::write::FileOptionsExt`: `EncryptedEntry`. Do not assert the password. Do not log it.
- Entry name `inner.zip`: `NestedArchive`.
- 33 stored empty files: `TooManyEntries`.
- One stored file whose `write_all` buffer is `8_388_609` zero bytes: `Expansion`. This allocation lives in the test process. Do not commit the bytes. The inspector must still return before reading the payload (`file.size()` exceeds the cap).

`zip::write::FileOptions::<()>::default()` with `compression_method(zip::CompressionMethod::Stored)`. Do not upgrade off `=2.3.0`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml tak:: -- --nocapture`

Expected: PASS. Then:

```bash
rg -n "std::net|uniffi|format!|println!|encode_position|decode_position" core/src/tak.rs
```

Expected: no matches. `format!("{:?}", TakClass::StructuralOk)` in the XML test is `format!` of the enum, not of an input slice. If `rg` matches that test line, narrow the search to non-test code:

```bash
rg -n "std::net|uniffi|println!|encode_position" core/src/tak.rs
```

Expected: no matches. A `format!` of `TakClass` in tests is allowed. A `format!` of `bytes` or `name` is not. Read the file and confirm none exist.

- [ ] **Step 5: Commit**

```bash
git add core/Cargo.toml core/Cargo.lock core/src/tak.rs
git commit -m "core: reject hostile zip fixtures in the TAK lab parser"
```

If the license check removed the dependency instead, commit only `core/src/tak.rs` if it has no zip code, and say in the commit message that `inspect_tak_archive` was omitted.

---

### Task 4: CI and the review gate

**Files:**
- Create: `.github/workflows/test-tak.yml`
- Test: local `cargo test` and `rg`

**Interfaces:**
- Consumes: `tak::` tests from Tasks 1–3.
- Produces: a workflow that runs those tests and does not deploy. The workflow file must not name an ATAK or iTAK version.

- [ ] **Step 1: Write the failing test**

Run: `test -f .github/workflows/test-tak.yml`

Expected: exit code 1.

- [ ] **Step 2: Run test to verify it fails**

The `test -f` in step 1 is the failing check. Do not edit `.github/workflows/auto-deploy.yml`.

- [ ] **Step 3: Write minimal implementation**

```yaml
name: test-tak
on:
  pull_request:
  push:
    branches: [main]
jobs:
  tak-fixtures:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo test --manifest-path core/Cargo.toml tak::
```

No secrets. No network address. No device.

- [ ] **Step 4: Run test to verify it passes**

```bash
cargo test --manifest-path core/Cargo.toml
rg -n "std::net|uniffi::" core/src/tak.rs core/src/lib.rs
git diff --name-only origin/main...HEAD
```

Expected: `cargo test` PASS. `rg` finds no `std::net` and no `uniffi` on `tak`. The diff must not list Android, `web/`, `aether-ca`, or `supabase/`. The PR text for the implementation must say that no ATAK or iTAK version was tested. `FramingOnly` and `StructuralOk` are lab results only.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/test-tak.yml
git commit -m "ci: test TAK lab fixtures without a server"
```

---

## Plan review gate

Stop if the module is public through UniFFI, if any function returns a latitude, a remark, or an entry name, if a socket type appears, or if the PR text says a client version is supported. `zip` 2.3.0 is a parser dependency, not a TAK client.
