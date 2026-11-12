# ÆTHER MIL™

Hardened field communications and mesh networking by Infinitum Corporation, built only for
authorized military and defense organizations. ÆTHER MIL™ is the communications layer of
ODIN°™: secure voice, text and data for every unit. There is no commercial edition, and it
sits outside the token economy.

This repository holds the first build of **ÆTHER Mobile MIL™**: a shared Rust security core
and a hardened Android application carrying ÆTHER Talk™ messaging over a local ÆTHER Mesh™
link, with ÆTHER Vault™ encrypted storage. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the design and the status of all ten systems.

```
supabase/   Backend migrations (schema `aether` in the shared Infinitum Corp project; RLS on every table)
core/       Rust security core (aether-core): identity, Noise sessions, envelopes, encrypted vault
android/    ÆTHER Mobile MIL™ — Kotlin + Jetpack Compose; builds the core with cargo-ndk, binds it with UniFFI
web/        ÆTHER MIL™ Web — the same core compiled to WebAssembly, a browser client and a local bridge
docs/       Architecture, protocol, threat model, system status
```

## ÆTHER MIL™ Web

The browser client runs the same Rust core, compiled to WebAssembly (`web/wasm`, wasm-bindgen), so its
post-quantum handshake (with v1 fallback), envelopes, Key Drop and burner-number checks are identical to the Android app's.
Browsers can't open raw TCP connections, so the page reaches ÆTHER nodes through a local bridge
(`web/bridge/bridge.mjs`, Node). The bridge listens on 127.0.0.1 only, accepts only allow-listed page
origins, and dials only private-network or loopback addresses. It forwards encrypted frames and never
sees plaintext.

- Live at https://infinitumaether.app (Vercel project `aether-mil-web`; `www.` redirects to the apex,
  and https://aether-mil-web.vercel.app still serves the same build). Config is `web/app/vercel.json`; deploy with
  `cd web/app && vercel deploy --prod`. Vercel Authentication protects every deployment, production included
  (`ssoProtection: all`): only members of the Vercel team can open it. Chrome asks once to allow local network access so the page can reach the
  bridge on 127.0.0.1; allow it.
- Also deployed on Cloudflare Workers (preview, behind Cloudflare Access): https://aether-mil-web.infinitumcorp.workers.dev
  (`web/wrangler.jsonc`; deploy with `cd web && npx wrangler deploy`). The product domain is
  on Vercel; the earlier Pages project https://aether-mil.pages.dev still serves the same build.
- Run locally:

```bash
cd web/bridge && npm install && node bridge.mjs          # ws://127.0.0.1:7340
python -m http.server 8737 --directory web/app           # http://127.0.0.1:8737
```

- Rebuild the WebAssembly core (needs `rustup target add wasm32-unknown-unknown` and `cargo install wasm-bindgen-cli --version 0.2.100`):

```bash
cd web/wasm && cargo build --release --target wasm32-unknown-unknown
wasm-bindgen --target web --out-dir ../app/pkg --no-typescript target/wasm32-unknown-unknown/release/aether_web.wasm
```

- Deploy: `npx wrangler pages deploy web/app --project-name aether-mil --branch main`. Pushes to `main` that touch `web/app` deploy automatically through `.github/workflows/deploy-web.yml` once the repo has the `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` secrets.
- Reach a phone over USB without the LAN: `adb forward tcp:7341 tcp:7337`, then connect to `127.0.0.1:7341`.

Limits: the browser identity is kept in the browser's local storage, not a hardware keystore; the browser
can dial nodes but can't accept inbound connections; it has no burner numbers of its own.

## What works today

- **Hardware-backed identity.** An X25519 key generated on the device and stored in ÆTHER Vault™,
  whose key is wrapped by the Android Keystore (StrongBox when present, TEE otherwise).
- **ÆTHER Mesh™ (single hop).** Peer discovery on the local network (mDNS / DNS-SD, `_aether._tcp`),
  or connect by `host:port`.
- **ÆTHER Talk™ (text).** End-to-end encrypted 1:1 messaging over mutually authenticated,
  forward-secret, hybrid post-quantum sessions: Noise XX with X25519 plus an ML-KEM-768 secret
  mixed into the key schedule (`Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s`, protocol v2). Falls back
  to classical v1 for older peers, never for a contact already seen with v2. The chat header
  shows "post-quantum" or "classical".
- **Device certificates.** `aether-ca` (in `core/`) runs an organization CA: Ed25519-signed
  certificates over device keys with cfssl-style profiles and serials. Each side sends its
  certificate in the encrypted handshake hello; the chat header shows "certified LABEL", and
  with "require" on (Diagnostics on Android, About in the browser) uncertified peers are hung up
  on before this identity is revealed. Burner numbers never send one. See
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#protocol-v2-hybrid-post-quantum-v1-fallback).
- **Safety numbers** to detect a man in the middle, with a "verified" mark per contact.
- **Key Drop.** In a conversation, drop your own or a teammate's identity key under a codename
  (e.g. `NIGHTJAR-27`; a catchy one is suggested). It travels end-to-end encrypted; the recipient
  pins it with one tap and reconnects from the card. A pinned contact is accepted only if the
  peer proves possession of that exact key; anyone else at the address is refused.
- **Burner numbers.** Disposable ÆTHER mesh numbers (`ÆN 733 482 9173`), each with its own key,
  so teammates reach you without seeing your identity or call sign. Create, rotate and burn in one
  tap; assign numbers to a team or mission; burn every number on a device remotely with its burn
  code. Add-on bundles of 1, 2 or 5 numbers, renewed monthly or yearly. See [docs/BURNER_NUMBERS.md](docs/BURNER_NUMBERS.md).
- **Device hardening.** No backups or device transfer of app data; `FLAG_SECURE` in release builds.
- **Diagnostics** with an on-device self-test of the security core.
- **ÆTHER Maps™ core** (AlpineQuest-style offline navigation): coordinates in decimal, DMS, DDM, UTM and
  MGRS (format and parse), WGS 84 distance and bearing, waypoint projection, path length and area, track
  statistics with elevation profile, MBTiles tile math, GPX import/export, KML export, and positions or
  markers shared end-to-end encrypted over ÆTHER Talk™.
- **ÆTHER Field™ core** (from HRVST): capture → contact-detail redaction → sealed packet, and tasking
  (post, claim, submit, accept, reject, release, cancel) between devices. Protected places (hospital,
  school, residence) stay on the device and can't be tasked. No market or credits.
- **ÆTHER eidetic MIL™ core** (from eidetic): checkpoint, access, personnel-recovery and casualty
  identification only, against enrolled and consented rosters; every match goes to a reviewer who did
  not run the search; every step is in a hash-chained audit log that never holds biometric data.

Verified on a TECNO KM4 (Android 15, arm64): all 5 on-device checks pass with TEE-backed keys.

**Staying reachable in the background.** A foreground service keeps the node listening when another app is in
front. TECNO / Infinix phones (HiOS, XOS) freeze background apps even so: in Phone Master → App launch
management, set ÆTHER MIL to manage manually and allow it to run in the background. Managed military
devices should allow-list the app through MDM.

## Prerequisites (Windows)

| Tool | Version used | Notes |
|---|---|---|
| JDK | 21 | Temurin |
| Rust | stable, `x86_64-pc-windows-gnu` host | `rustup target add aarch64-linux-android x86_64-linux-android` |
| MinGW-w64 | WinLibs GCC 16 in `~/tools/winlibs` | Supplies `dlltool`/`as` for the GNU host (no Visual Studio needed). Override with `AETHER_MINGW_BIN`. |
| cargo-ndk | 4.x | `cargo install cargo-ndk --locked` |
| Android SDK | platform 36, build-tools 36.0.0, NDK 28.2.13676358 | |

Google Maven is fetched from Google's CDN (`redirector.gvt1.com`) because `dl.google.com`
returns 404 on the development network; see `android/settings.gradle.kts`.

## Build

```bash
cd core && cargo test          # Rust unit tests (host)
cd core && cargo run --bin aether-ca -- init --key ca.key   # organization CA (keep ca.key offline)
# With the Supabase registry (AETHER_SUPABASE_SERVICE_KEY set in your shell, never committed):
cd core && cargo run --features registry --bin aether-ca -- sign --key ca.key --device <key hex> --label NAME --register
cd android && ./gradlew assembleDebug
```

Gradle runs `cargoNdkBuild` (Rust → `libaether_core.so` for arm64-v8a and x86_64),
`cargoHostBuild` and `uniffiBindgen` (Kotlin bindings) before every build.

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Trying a conversation

Two devices on the same network find each other under **ÆTHER Mesh™**; tap one to connect.

Start the test emulator with `tools/emulator.sh` (emulator 35.2.10; the SDK's 37.x build crashes on
this development PC's Ivy Bridge CPU). Diagnostics on the emulator pass 5/5 with software-backed keys.

A phone and an emulator on one PC: forward the emulator's port, then connect from the phone to
the PC's address.

```bash
adb -s emulator-5554 forward tcp:7337 tcp:7337
```

From the emulator, the phone is reachable directly at its LAN address (shown under **Listening**).

## Third-party software

Open-source components and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
