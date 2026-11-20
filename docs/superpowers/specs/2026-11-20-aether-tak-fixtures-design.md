# Cycle B — TAK parsing lab fixtures

> **Status:** Plan-ready only after the owner reviews the written spec set dated 2026-11-20.
>
> **Parent:** [2026-11-20-aether-secure-messaging-tak-design.md](2026-11-20-aether-secure-messaging-tak-design.md)
>
> **This cycle is one implementation plan.** It is independent of cycle A. It does not talk to a TAK Server, an ATAK device, or an iTAK device, and it does not claim that any client version is compatible.

## Goal

Add a pure classifier for the publicly documented TAK framings, and a pure archive inspector for hostile zip files. Both are unit-tested with synthetic fixtures. The production allowlist is empty, so nothing this cycle parses is sendable.

## Public rules implemented

From the ATAK-CIV `takproto` notes and `protocol.txt`, and only those structural rules:

- An XML stream message is one document that ends at the first `</event>` terminator described by that note. This cycle classifies a single buffer, not a live stream.
- A binary message begins with the byte `0xbf`.
- After that byte, a streaming-style length is a varint. The following payload is exactly that many bytes.
- A mesh-style header is `0xbf`, a version varint, then a payload. Cycle B detects this shape only when the test names the buffer as mesh. The classifier does not guess mesh versus streaming for untagged bytes, because both start with `0xbf` and a varint, and guessing would be an overclaim.

Payload version 1 is a protobuf `TakMessage` in the public notes. This cycle does not decode protobuf. A binary payload is `FramingOnly` when the header is well formed, and `Malformed` otherwise. Protobuf waits on a license review and a later spec.

The GeoChat detail schema and the official data-package manifest are not in the public sources this design relies on. They are not invented here. Community zip layouts are not encoded as a template.

## API

New module `core/src/tak.rs`, exported as plain functions. No UniFFI export in this cycle, so Android and the browser cannot call it. No socket types. No `std::net`.

```text
classify_tak_xml(bytes) -> TakClass
classify_tak_stream_frame(bytes) -> TakClass
classify_tak_mesh_header(bytes) -> TakClass
inspect_tak_archive(bytes) -> ArchiveClass
```

`TakClass` is one of:

| Class | Meaning |
|---|---|
| `Malformed { fault }` | The buffer is not the framing under test |
| `RejectedPosition` | A well-formed XML event contains a `point` element, or any attribute whose local name is `lat` or `lon`. Match is ASCII case-insensitive |
| `StructuralOk` | A well-formed XML event with none of those. There is no allowlist API and no send function |
| `FramingOnly` | Binary header parsed, declared payload length is at least 1, and the buffer length matches. Payload not interpreted |

`fault` is a closed enum: `Oversize`, `BadVarint`, `LengthMismatch`, `MissingMagic`, `Doctype`, `Entity`, `NotXml`, `Unclosed`, `TooManyTags`. `Display` for every fault is a fixed phrase. `Debug` on `TakClass` does not include input bytes.

The XML classifier does not return attributes, remarks, call signs, latitudes, or longitudes. A `point` tag, or a `lat` or `lon` attribute, forces `RejectedPosition` even when the value is empty, so the API cannot be used to pull a coordinate out. `StructuralOk` means the buffer met the structural rules in this spec. It does not mean the event satisfies the CoT standard. Missing `uid`, `time`, or `type` still returns `StructuralOk` when the root is `event` and no position marker is present.

`ArchiveClass` is `Ok { entries }` or `Rejected { fault }` with `fault` in `Oversize`, `TooManyEntries`, `Path`, `Link`, `EncryptedEntry`, `NestedArchive`, `BadZip`, `Expansion`. `entries` is the count of accepted names, not the names, so a crafted name cannot reach a log through `Debug`.

## Limits

These are engineering defaults for the lab parser. A later gateway spec may lower them. It may not raise them without a new review.

| Limit | Value |
|---|---|
| Single XML buffer | 256 KiB |
| Declared binary payload | 256 KiB |
| Varint | At most 5 bytes. A varint that would exceed `u32` is `BadVarint` |
| XML start tags in one event | 64 |
| Archive compressed size | 8 MiB |
| Archive uncompressed size, running total | 8 MiB |
| Archive entries | 32 |
| Single entry name | 128 bytes, UTF-8, no NUL |

XML processing:

- Reject the buffer if it contains `<!DOCTYPE` at any ASCII case, before building a document.
- Use `quick-xml`, already a core dependency. Nothing is fetched. Predefined entities `amp`, `lt`, `gt`, `quot`, and `apos` may decode. An error that `quick-xml` reports as an entity error maps to `Entity`, including the fixture `&aetherlab;`. Other reader errors map to `NotXml`, except end-of-input inside the root, which is `Unclosed`.
- An XML declaration and whitespace around the root are accepted. A comment or a processing instruction is `NotXml`.
- No XInclude, no external schema, no network.
- The root element's local name is `event`, case-sensitive. Any other root is `NotXml`.
- After a complete `event` element, trailing non-whitespace is `NotXml`.

Binary processing:

- A varint is an unsigned protobuf varint: groups of 7 bits, least significant group first. More than 5 bytes, a value above `u32::MAX`, or an overlong encoding (more than one byte and the last byte is `0x00`) is `BadVarint`.
- Byte 0 is `0xbf` or the result is `MissingMagic`.
- Streaming: the varint is the payload length and must be at least 1. The buffer is exactly `1 + varint_width + length`. Otherwise `LengthMismatch`.
- Mesh, only on `classify_tak_mesh_header`: the varint is a version. Version byte-length must be at least 1 and the remaining length must be from 1 byte through 256 KiB. The version value is not interpreted. `FramingOnly` on success.
- Tests call only the function named for that fixture. Cross-calling the mesh and streaming functions is out of scope and untested. `FramingOnly` means the header matched. It does not mean a TAK client will accept the payload.
- Declared payload length 0 is `LengthMismatch`.

Archive processing:

- Reject `..`, an absolute path, a drive prefix, a backslash, and an empty name (`Path`).
- Reject a symlink or hard-link entry (`Link`).
- Reject the encrypted-entry flag (`EncryptedEntry`). Zip-crypto is not implemented.
- Reject a nested `.zip` name (`NestedArchive`).
- Count compressed and uncompressed bytes against the caps. The inspector does not extract to disk. It discards entry names after the checks.
- The implementation PR proposes one crates.io zip crate under Apache-2.0 or MIT, with no telemetry. If that check fails, `inspect_tak_archive` is omitted and no zip dependency is added. XML and binary classifiers still ship. Tests build zip fixtures with that crate only when it is added. There is no hand-written zip writer.

## Fixtures

Synthetic only. No capture from a live server. No real certificate. No customer hostname. No coordinate except where a test must prove `RejectedPosition`, and that test asserts the class, not the number.

| Fixture | Expected |
|---|---|
| Exact buffer `<?xml version="1.0"?><event version="2.0" type="aether-lab-only"></event>` | `StructuralOk` |
| Same document with a `point` tag, or with a `lat` attribute and no `point` tag | `RejectedPosition` |
| `type="aether-lab-only"` | `StructuralOk` |
| Buffer of 256 KiB + 1 | `Oversize` |
| `<!DOCTYPE event [...]>` | `Doctype` |
| Text `&aetherlab;` with no DOCTYPE | `Entity` |
| Text `&amp;` inside an otherwise minimal event | `StructuralOk` |
| Truncated `<event>` with no end tag | `Unclosed` |
| `0xbf` plus a varint length and that many payload bytes | `FramingOnly` on the streaming function |
| Length varint that does not match the buffer | `LengthMismatch` |
| Leading byte `0x00` | `MissingMagic` |
| Zip with entry `certs/../escape` | `Path` |
| Zip with entry `/tmp/x` | `Path` |
| Zip with a symlink entry | `Link` |
| Zip with the encrypted-entry flag | `EncryptedEntry` |
| Zip with entry name `inner.zip` | `NestedArchive` |
| Zip with 33 entries | `TooManyEntries` |
| Zip whose uncompressed sizes sum past 8 MiB | `Expansion` |
| Search of the module | No allowlist type, no send function, no UniFFI attribute |

`aether-lab-only` is a lab type. The gateway, when it exists, will not send it. Using a real ATAK type here would pretend this cycle had a schema it does not have.

## Error handling and privacy

- The functions never panic on untrusted bytes. A `quick-xml` error becomes `Malformed` with fault `NotXml`, except end-of-input inside the root, which is `Unclosed`. A zip-library error becomes `Rejected` with fault `BadZip`.
- They allocate within the caps and then stop.
- They have no `log` crate call and no `println`.
- They do not call `encode_position`, `decode_position`, or the field codecs.
- They do not read the clock, the disk, or the environment.

## What this does not prove

A green `cargo test` proves the classifier matches this spec. It does not prove that ATAK-CIV, iTAK, WinTAK, or any TAK Server build will parse, display, or forward a buffer. Those builds are not named, not installed, and not contacted.

Device testing belongs to cycle G, after the owner names the server build and the ATAK and iTAK builds in writing. Until then the support matrix is empty.

## Acceptance tests

1. The fixture table is a `cargo test` module in `core/src/tak.rs`.
2. `cargo test` in `core/` passes.
3. A review check shows no `std::net`, no UniFFI attribute, no new UI file, no manifest permission, and no fixture file larger than the caps. When a zip crate is added, tests build archives with that crate. When it is not added, the zip rows are absent and `inspect_tak_archive` does not exist.
4. `rg` over `core/src/tak.rs` shows no `format!` of input slices and no `Debug` impl that includes bytes.

## Implementation boundaries

A later plan adds `core/src/tak.rs` and `mod tak;` in `core/src/lib.rs`. The module stays private. Tests are `#[cfg(test)]` inside the module. It may add one zip dependency if the license check passes.

It does not edit Android, the browser, `aether-ca`, Supabase, or the bridge.

## Done when

The XML and binary fixture tests pass, position coordinates cannot be returned, archive tests pass when a zip crate was accepted, and the PR description states that no ATAK or iTAK version was tested.
