# Burner numbers

Disposable numbers that let an operator call and text without exposing their real identity.
The record format is shared with FABRIC, so both products describe numbers the same way.
ÆTHER issues numbers on its own mesh (`network: "aether-mesh"`), and FABRIC issues carrier
numbers (`network: "pstn"`).

## In ÆTHER MIL™

- **What a number is.** Each number has its own X25519 identity key. Its 10-digit number is
  derived from that key, so a number authenticates itself: the session opens only if the peer
  proves the key behind the number. There's no registry to trust or to seize.
- **What peers see.** Someone talking to a number sees only that number and its label. They
  never see the operator's identity key, fingerprint or call sign.
- **Create, rotate, burn.** Each is one tap. Rotating burns the number and issues a new one with
  the same label and assignment. Burning deletes the number's own Android Keystore key and its
  vault, which holds the secret key and every conversation held under that number. Both are gone
  together, and the ciphertext left on flash can't be decrypted.
- **Assignment.** A number can be personal, or assigned to a named team or mission.
- **Bundles.** Numbers come as add-ons in bundles of 1, 2 or 5 slots, renewed monthly or yearly.
  Only bundles that haven't expired count, and you can't create a number beyond those slots.
  Numbers you already have keep working when a bundle lapses. Organizations provision and renew
  bundles; debug builds have test buttons for both.
- **Remote burn.** Each device can show a burn code once. Anyone holding it can burn every number
  on that device from any live ÆTHER session.
- **Dialing.** A number is found on the local network by its mDNS advert (`AETHER-N<10 digits>`)
  or at an address you give. The dialer checks the answering key after Noise message 2 and hangs
  up before message 3 if it doesn't match. A wrong peer therefore never learns the dialer's
  number key or label.
- **Voice.** Numbers carry encrypted text today. Calls arrive with ÆTHER Talk™ voice.

### Limits

- **A number runs on the same device and IP as the real identity.** Someone watching the local
  network can link a number to the real identity by IP address. Hiding that link needs relayed
  routing over ÆTHER Mesh™ / ÆTHER Node™, which isn't built yet.
- **Remote burn needs a live session with the device.** Without a connection, a lost device keeps
  its numbers until one is made.

## Shared record (v1)

```json
{
  "v": 1,
  "id": "base64url, 128 random bits, no padding",
  "network": "aether-mesh" | "pstn",
  "number": "see below",
  "label": "1-32 characters",
  "scope": { "kind": "personal" | "team" | "mission", "name": "string or null" },
  "state": "active" | "rotating" | "burned",
  "createdAt": 1762400000000,
  "burnedAt": null,
  "rotatedFrom": "id or null",
  "bundleId": "id or null"
}
```

- `number` for `pstn` is E.164, such as `+15551234567`.
- `number` for `aether-mesh` is exactly 10 ASCII digits:
  `u64_be(SHA-256("AETHER-NUMBER-v1" ‖ public_key)[0..8]) mod 10^10`, zero-padded. It's displayed
  as `ÆN 733 482 9173`. Reject anything else, and reject a peer whose key doesn't derive to the
  number. The reference is `burner_number` / `burner_number_matches` in `core/src/burner.rs`.
- `burnedAt` is set exactly when `state` is `burned`. ÆTHER destroys a burned record along with
  its key, so `burned` only appears in transit or in FABRIC's account history. `rotating` is the
  short window while a replacement is being issued.
- Timestamps are epoch milliseconds.
- The full record stays client-side, in the encrypted vault. A relay needs only number → handle.

Bundle (entitlement):

```json
{
  "v": 1,
  "bundleId": "base64url id",
  "size": 1 | 2 | 5,
  "period": "month" | "year",
  "grantedAt": 1762400000000,
  "expiresAt": 1765000000000
}
```

Renewing moves `expiresAt` on by one period, counted from the old expiry, or from now if the
bundle has already lapsed. FABRIC's relay keeps `{ id, handle, size, period, expiresAt }`; the two
map as `bundleId` = `id`. A number record's `bundleId` means the same in both.
FABRIC rotates in a single relay transaction and never writes `rotating` itself, but it accepts
records in that state.

Remote burn:

```json
{ "v": 1, "action": "burnAll", "issuedAt": 1762400000000 }
```

## Remote-burn authorisation

| | ÆTHER (`aether-mesh`) | FABRIC (`pstn`, has a relay) |
|---|---|---|
| Secret | 128-bit random code, Crockford base32 (26 characters, grouped by 5) | Same code format |
| Stored as | `SHA-256("AETHER-BURN-v1" ‖ code bytes)`. A slow KDF adds nothing here, because the code is random and 128-bit: guessing it means 2^127 tries on average. | A slow KDF (PBKDF2-600k / scrypt / Argon2) with a per-account salt, if codes could ever be user-chosen or shorter |
| Transport | Only inside a mutually authenticated Noise session, with no relay in the path | Signed with the account signing key, so a relay alone can't trigger it |
| Rate limit | A link is dropped after 3 wrong codes | Rate-limited per account |
