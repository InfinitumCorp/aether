//! Lab classifier for publicly documented TAK framing. No sockets, no UniFFI, no client
//! compatibility claim. `StructuralOk` and `FramingOnly` mean the bytes matched this module's
//! structural rules. They do not mean an ATAK or iTAK build will accept them.
#![allow(dead_code)]

use std::fmt;

use quick_xml::events::Event;
use quick_xml::Reader;

const XML_MAX: usize = 262_144;
const FRAME_MAX: usize = 262_144;
const MAX_TAGS: usize = 64;
const ARCHIVE_MAX: u64 = 8_388_608;
const MAX_ENTRIES: usize = 32;
const MAX_NAME: usize = 128;

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

pub(crate) fn classify_tak_xml(bytes: &[u8]) -> TakClass {
    if bytes.len() > XML_MAX {
        return TakClass::Malformed { fault: TakFault::Oversize };
    }
    if contains_doctype(bytes) {
        return TakClass::Malformed { fault: TakFault::Doctype };
    }
    if has_custom_entity(bytes) {
        return TakClass::Malformed { fault: TakFault::Entity };
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
            Ok(Event::Start(e)) => {
                if let Err(class) = on_tag(&e, true, &mut tags, &mut depth, &mut saw_root, &mut closed) {
                    return class;
                }
            }
            Ok(Event::Empty(e)) => {
                if let Err(class) = on_tag(&e, false, &mut tags, &mut depth, &mut saw_root, &mut closed) {
                    return class;
                }
            }
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

fn has_custom_entity(bytes: &[u8]) -> bool {
    let mut i = 0;
    while let Some(rel) = bytes[i..].iter().position(|b| *b == b'&') {
        i += rel + 1;
        let rest = &bytes[i..];
        let predefined = [b"amp;".as_slice(), b"lt;", b"gt;", b"quot;", b"apos;"];
        if predefined.iter().any(|p| rest.starts_with(p)) || rest.starts_with(b"#") {
            continue;
        }
        return true;
    }
    false
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

#[cfg(not(target_arch = "wasm32"))]
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
            Err(zip::result::ZipError::UnsupportedArchive(zip::result::ZipError::PASSWORD_REQUIRED))
            | Err(zip::result::ZipError::InvalidPassword) => {
                return ArchiveClass::Rejected { fault: ArchiveFault::EncryptedEntry };
            }
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

/// Operator-chosen TAK text. The returned XML is visible to the TAK server.
/// It never contains a point, a latitude, or a longitude. It is not a claim that
/// any ATAK or iTAK version will display it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TakText {
    pub server_visible: bool,
    pub xml: String,
}

pub(crate) fn prepare_tak_text(cot_type: &str, text: &str) -> Result<TakText, &'static str> {
    if cot_type.is_empty()
        || cot_type.len() > 64
        || !cot_type.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
    {
        return Err("tak type rejected");
    }
    if text.is_empty() || text.len() > 2000 || text.chars().any(|c| c.is_control()) {
        return Err("tak text rejected");
    }
    let xml = format!(
        "<?xml version=\"1.0\"?><event version=\"2.0\" type=\"{}\"><detail><remarks>{}</remarks></detail></event>",
        cot_type,
        xml_escape(text)
    );
    if xml.to_ascii_lowercase().contains("<point") || xml.contains(" lat=") || xml.contains(" lon=") {
        return Err("tak text rejected");
    }
    Ok(TakText { server_visible: true, xml })
}

fn xml_escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            _ => out.push(c),
        }
    }
    out
}

#[cfg(not(target_arch = "wasm32"))]
fn reject_name(name: &[u8]) -> Option<ArchiveFault> {
    if name.is_empty() || name.len() > MAX_NAME || name.contains(&0) || std::str::from_utf8(name).is_err() {
        return Some(ArchiveFault::Path);
    }
    if name.iter().any(|b| *b == b'\\')
        || name[0] == b'/'
        || name.windows(2).any(|w| w[1] == b':' && w[0].is_ascii_alphabetic())
    {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

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

    #[test]
    fn xml_faults_match_the_fixture_table() {
        let amp = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only">&amp;</event>"#;
        assert!(matches!(classify_tak_xml(amp.as_bytes()), TakClass::StructuralOk));
        let entity = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only">&aetherlab;</event>"#;
        let got = classify_tak_xml(entity.as_bytes());
        assert!(matches!(got, TakClass::Malformed { fault: TakFault::Entity }), "{got:?}");
        let doctype = r#"<!DOCTYPE event [<!ENTITY x "y">]><event></event>"#;
        assert!(matches!(classify_tak_xml(doctype.as_bytes()), TakClass::Malformed { fault: TakFault::Doctype }));
        let open = r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only">"#;
        assert!(matches!(classify_tak_xml(open.as_bytes()), TakClass::Malformed { fault: TakFault::Unclosed }));
        assert!(matches!(
            classify_tak_xml(br#"<?xml version="1.0"?><note></note>"#),
            TakClass::Malformed { fault: TakFault::NotXml }
        ));
        assert!(matches!(
            classify_tak_xml(&vec![b'x'; 262_145]),
            TakClass::Malformed { fault: TakFault::Oversize }
        ));
        assert!(matches!(
            classify_tak_xml(br#"<?xml version="1.0"?><!--x--><event version="2.0" type="aether-lab-only"></event>"#),
            TakClass::Malformed { fault: TakFault::NotXml }
        ));
        let mut many = String::from(r#"<?xml version="1.0"?><event version="2.0" type="aether-lab-only">"#);
        for _ in 0..64 {
            many.push_str("<a>");
        }
        for _ in 0..64 {
            many.push_str("</a>");
        }
        many.push_str("</event>");
        assert!(matches!(classify_tak_xml(many.as_bytes()), TakClass::Malformed { fault: TakFault::TooManyTags }));
    }

    #[test]
    fn stream_frame_matches_length_and_rejects_bad_headers() {
        assert!(matches!(classify_tak_stream_frame(&[0xbf, 0x01, 0x00]), TakClass::FramingOnly));
        assert!(matches!(
            classify_tak_stream_frame(&[0xbf, 0x02, 0x00]),
            TakClass::Malformed { fault: TakFault::LengthMismatch }
        ));
        assert!(matches!(classify_tak_stream_frame(&[0x00]), TakClass::Malformed { fault: TakFault::MissingMagic }));
        assert!(matches!(
            classify_tak_stream_frame(&[0xbf, 0x00]),
            TakClass::Malformed { fault: TakFault::LengthMismatch }
        ));
        assert!(matches!(
            classify_tak_stream_frame(&[0xbf, 0x80, 0x00, 0x00]),
            TakClass::Malformed { fault: TakFault::BadVarint }
        ));
        assert!(matches!(
            classify_tak_stream_frame(&[0xbf, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00]),
            TakClass::Malformed { fault: TakFault::BadVarint }
        ));
        assert!(matches!(classify_tak_mesh_header(&[0xbf, 0x01, 0x11]), TakClass::FramingOnly));
    }

    #[test]
    fn prepared_tak_text_is_server_visible_and_has_no_position() {
        let msg = prepare_tak_text("aether-lab-only", "hello <team> & co").unwrap();
        assert!(msg.server_visible);
        assert!(msg.xml.contains("type=\"aether-lab-only\""));
        assert!(msg.xml.contains("hello &lt;team&gt; &amp; co"));
        assert!(!msg.xml.to_ascii_lowercase().contains("<point"));
        assert!(!msg.xml.contains(" lat="));
        assert!(prepare_tak_text("", "hello").is_err());
        assert!(prepare_tak_text("aether-lab-only", "").is_err());
        assert!(prepare_tak_text("bad type", "hello").is_err());
    }

    #[cfg(not(target_arch = "wasm32"))]
    #[test]
    fn oversized_buffer_and_non_zip_are_rejected() {
        assert!(matches!(inspect_tak_archive(b"not a zip"), ArchiveClass::Rejected { fault: ArchiveFault::BadZip }));
        let big = vec![0u8; 8_388_609];
        assert!(matches!(inspect_tak_archive(&big), ArchiveClass::Rejected { fault: ArchiveFault::Oversize }));
    }

    #[cfg(not(target_arch = "wasm32"))]
    fn zip_bytes(build: impl FnOnce(&mut zip::ZipWriter<&mut std::io::Cursor<Vec<u8>>>) -> zip::result::ZipResult<()>) -> Vec<u8> {
        let mut cursor = std::io::Cursor::new(Vec::new());
        let mut writer = zip::ZipWriter::new(&mut cursor);
        build(&mut writer).unwrap();
        writer.finish().unwrap();
        cursor.into_inner()
    }

    #[cfg(not(target_arch = "wasm32"))]
    fn stored() -> zip::write::FileOptions<'static, ()> {
        zip::write::FileOptions::<()>::default().compression_method(zip::CompressionMethod::Stored)
    }

    #[cfg(not(target_arch = "wasm32"))]
    #[test]
    fn archive_fixtures_reject_hostile_entries() {
        use zip::unstable::write::FileOptionsExt;
        let ok = zip_bytes(|z| {
            z.start_file("ok.txt", stored())?;
            z.write_all(b"a")?;
            Ok(())
        });
        assert!(matches!(inspect_tak_archive(&ok), ArchiveClass::Ok { entries: 1 }));
        let escape = zip_bytes(|z| z.start_file("certs/../escape", stored()));
        assert!(matches!(inspect_tak_archive(&escape), ArchiveClass::Rejected { fault: ArchiveFault::Path }));
        let abs = zip_bytes(|z| z.start_file("/tmp/x", stored()));
        assert!(matches!(inspect_tak_archive(&abs), ArchiveClass::Rejected { fault: ArchiveFault::Path }));
        let link = zip_bytes(|z| z.add_symlink("link", "target", stored()));
        assert!(matches!(inspect_tak_archive(&link), ArchiveClass::Rejected { fault: ArchiveFault::Link }));
        let enc = zip_bytes(|z| {
            z.start_file("secret.txt", stored().with_deprecated_encryption(b"lab"))?;
            z.write_all(b"x")?;
            Ok(())
        });
        let got = inspect_tak_archive(&enc);
        assert!(matches!(got, ArchiveClass::Rejected { fault: ArchiveFault::EncryptedEntry }), "{got:?}");
        let nested = zip_bytes(|z| z.start_file("inner.zip", stored()));
        assert!(matches!(inspect_tak_archive(&nested), ArchiveClass::Rejected { fault: ArchiveFault::NestedArchive }));
        let many = zip_bytes(|z| {
            for i in 0..33 {
                z.start_file(format!("f{i}.txt"), stored())?;
            }
            Ok(())
        });
        assert!(matches!(inspect_tak_archive(&many), ArchiveClass::Rejected { fault: ArchiveFault::TooManyEntries }));
        let huge = zip_bytes(|z| {
            z.start_file("big.bin", zip::write::FileOptions::<()>::default())?;
            z.write_all(&vec![0u8; 8_388_609])?;
            Ok(())
        });
        assert!(matches!(inspect_tak_archive(&huge), ArchiveClass::Rejected { fault: ArchiveFault::Expansion }));
    }
}
