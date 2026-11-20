# ÆTHER implementation plans — 2026-11-20

Written-spec approval covers the architecture record and cycle A and cycle B only. These plans are the execution documents for those two cycles. They do not authorize a merge or a deploy. Do not start either plan until the owner asks for execution.

| Plan | Spec | Depends on |
|---|---|---|
| [Cycle A — foundation](2026-11-20-aether-talk-foundation.md) | [foundation spec](../specs/2026-11-20-aether-talk-foundation-design.md) | None. Do not wait for cycle B. |
| [Cycle B — TAK fixtures](2026-11-20-aether-tak-fixtures.md) | [fixtures spec](../specs/2026-11-20-aether-tak-fixtures-design.md) | None. Do not wait for cycle A. |

The only file both plans edit is `core/src/lib.rs`, and each adds one different `mod` line. Execute them on separate branches if they run at the same time, then keep both lines.

## Not planned

These need a new written spec before any implementation plan. This index does not schedule them.

| Cycle | What it is | Why it has no plan |
|---|---|---|
| C | Async 1:1, groups, receipts, edits, reactions, local search, disappearing timers | MLS pin and license re-check are not approved as a dependency yet |
| D | Encrypted attachments and voice notes | No focused spec |
| E | A second device receives new messages | No focused spec. History sync stays forbidden until a backup decision |
| F | 1:1 voice and video | No focused spec. Group calls and screen sharing are a further spec |
| G | TAK enrollment and a disclosed text send | Cycle B fixtures are not a client compatibility claim. The owner has not named a server build or ATAK/iTAK builds |
| H | Marker publish | Not approved. Own-position streaming stays off |

No plan here changes Noise, ML-KEM, the envelope version, backups, UI styling, domains, or Android permissions. No plan opens a socket or publishes a location.
