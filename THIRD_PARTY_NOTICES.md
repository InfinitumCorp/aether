# Third-party notices

ÆTHER MIL™ is proprietary software of Infinitum Corporation. It uses the open-source
components below as unmodified dependencies (fetched by Cargo or npm at build time); no
third-party source code is copied into this repository. None of these projects or their
authors endorse ÆTHER MIL™.

Checked on 2026-11-08 against the versions in `core/Cargo.lock`, `web/wasm/Cargo.lock` and
`web/bridge/package-lock.json`. None of the components below ships a `NOTICE` file; where a
licence requires its copyright notice to travel with binaries, the notice is reproduced here.

## Post-quantum handshake and device certificates (added 2026-11-08)

| Component | Version | Licence (option used) | Use |
|---|---|---|---|
| [ml-kem](https://github.com/RustCrypto/KEMs) | 0.2.3 | Apache-2.0 OR MIT (MIT) | ML-KEM-768 (FIPS 203) key encapsulation in the v2 handshake |
| [kem](https://github.com/RustCrypto/traits) | 0.3.0-pre.0 | Apache-2.0 OR MIT (MIT) | `Encapsulate` / `Decapsulate` traits used with ml-kem |
| [hybrid-array](https://github.com/RustCrypto/hybrid-array), [sha3](https://github.com/RustCrypto/hashes), [keccak](https://github.com/RustCrypto/sponges) | 0.2.3, 0.10.9, 0.1.6 | Apache-2.0 OR MIT (MIT) | ml-kem dependencies |
| [ed25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek) | 2.2.0 | BSD-3-Clause | Ed25519 signatures on device certificates |
| [ed25519](https://github.com/RustCrypto/signatures), [signature](https://github.com/RustCrypto/traits) | 2.2.3, 2.2.0 | Apache-2.0 OR MIT (MIT) | ed25519-dalek dependencies |

**cfssl** (Cloudflare, BSD-2-Clause, https://github.com/cloudflare/cfssl) was used as a *design
reference only*: the idea of a CA key, signing profiles with default expiries, serial numbers
and JSON output for `aether-ca`. No cfssl code is used or copied, and the certificate format
is ÆTHER's own (not X.509).

## Existing components

| Component | Version | Licence (option used) | Use |
|---|---|---|---|
| [snow](https://github.com/mcginty/snow) | 0.9.6 | Apache-2.0 OR MIT (MIT) | Noise protocol framework |
| [x25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek) | 2.0.1 | BSD-3-Clause | X25519 identity keys |
| [curve25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek) | 4.1.3 | BSD-3-Clause | Curve arithmetic for x25519-dalek and ed25519-dalek |
| [chacha20poly1305](https://github.com/RustCrypto/AEADs), [blake2](https://github.com/RustCrypto/hashes), [sha2](https://github.com/RustCrypto/hashes) | 0.10.1, 0.10.6, 0.10.9 | Apache-2.0 OR MIT (MIT) | AEAD and hashes |
| [zeroize](https://github.com/RustCrypto/utils) | 1.9.1 | Apache-2.0 OR MIT (MIT) | Wiping key material |
| [rand_core](https://github.com/rust-random/rand) | 0.6.4 | MIT OR Apache-2.0 (MIT) | OS randomness |
| [thiserror](https://github.com/dtolnay/thiserror) | 1.0.69 | MIT OR Apache-2.0 (MIT) | Error types |
| [uniffi](https://github.com/mozilla/uniffi-rs) | 0.28.3 | MPL-2.0 | Kotlin bindings for Android. Used unmodified; its source is available at the link. |
| [wasm-bindgen](https://github.com/rustwasm/wasm-bindgen) | 0.2.100 | MIT OR Apache-2.0 (MIT) | WebAssembly bindings for the browser client |
| [ws](https://github.com/websockets/ws) | 8.18.0 | MIT | WebSocket server in the local bridge |
| [ureq](https://github.com/algesten/ureq) | 2.12.1 | MIT OR Apache-2.0 (MIT) | HTTPS client in `aether-ca` (optional `registry` feature only; not in the apps) |
| [rustls](https://github.com/rustls/rustls) 0.23.46, [webpki-roots](https://github.com/rustls/webpki-roots) 0.26.11 / 1.0.11 | via ureq | Apache-2.0 OR ISC OR MIT (MIT); webpki-roots: CDLA-Permissive-2.0 | TLS for `aether-ca` (registry feature only) |
| [ring](https://github.com/briansmith/ring) 0.17.14, [rustls-webpki](https://github.com/rustls/webpki) 0.103.16, [untrusted](https://github.com/briansmith/untrusted) 0.9.0 | via rustls | ring: Apache-2.0 AND ISC; the others ISC | Crypto and certificate checks for rustls in `aether-ca` (registry feature only) |

## Geo datasets (added 2026-11-09)

Fetched on demand by `cargo run --bin fetch-geo` (lockfile `core/geo.lock`) into the gitignored
directory `data/geo/`. The binaries are not committed. Checksums were checked on 2026-11-09
against the official `.sha256sum` release assets. Loyalsoldier and sing-box geo lists are not
used: they are GPL-3.

| Dataset | Project | Tag | Asset | Licence | SHA-256 |
|---|---|---|---|---|---|
| `geoip.dat` | [v2fly/geoip](https://github.com/v2fly/geoip) | `202611080027` | `geoip.dat` | CC-BY-SA-4.0 | `eedb24b3b9fcba27e06b92c0428dab90d9496083091ae4cf39e11387fd99d0e2` |
| `geosite.dat` | [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community) | `20261108011013` | `dlc.dat` (saved as `geosite.dat`) | MIT | `dc4192dd1b644a38276ed06136d26e729fa674d25785adb38c87e040e92376b2` |

GeoIP data © the v2fly contributors, used unmodified under the Creative Commons
Attribution-ShareAlike 4.0 International licence
(<https://creativecommons.org/licenses/by-sa/4.0/>). Attribution: v2fly geoip,
<https://github.com/v2fly/geoip/releases/tag/202611080027>. An adaptation of this dataset must
stay under CC-BY-SA-4.0. ÆTHER does not modify the file.

domain-list-community © 2018-2019 V2Ray, MIT. The copyright notice and permission notice are
the MIT text below. Source: <https://github.com/v2fly/domain-list-community/releases/tag/20261108011013>.

Geo routing is off unless `INFINITUM_GEO_ENABLED` is `on` (or `1` / `true` / `yes`) or the
operator turns the settings switch on. While it is off, peer labels and connections are unchanged.

## Licence texts

### MIT (components marked MIT above)

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software
> and associated documentation files (the "Software"), to deal in the Software without
> restriction, including without limitation the rights to use, copy, modify, merge, publish,
> distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
> Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
> BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Copyright holders: the RustCrypto Developers (ml-kem, kem, hybrid-array, sha3, keccak, ed25519,
signature, chacha20poly1305, blake2, sha2, zeroize); Jake McGinty and contributors (snow); the Rand
Project Developers (rand_core); David Tolnay (thiserror); the wasm-bindgen Developers
(wasm-bindgen); Einar Otto Stangvik, Arnout Kazemier, Luigi Pinca and contributors (ws);
V2Ray (domain-list-community, the `geosite.dat` dataset).

### BSD-3-Clause (ed25519-dalek, x25519-dalek, curve25519-dalek)

```
ed25519-dalek:    Copyright (c) 2017-2019 isis agora lovecruft. All rights reserved.
x25519-dalek:     Copyright (c) 2017-2021 isis agora lovecruft. All rights reserved.
                  Copyright (c) 2019-2021 DebugSteven. All rights reserved.
curve25519-dalek: Copyright (c) 2016-2021 isis agora lovecruft. All rights reserved.
                  Copyright (c) 2016-2021 Henry de Valence. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### MPL-2.0 (uniffi)

uniffi is used unmodified under the Mozilla Public License 2.0
(https://mozilla.org/MPL/2.0/). Its source code is available at
https://github.com/mozilla/uniffi-rs.
