# Later cycles after the 2026-11-20 foundation

Cycle A and cycle B are implemented on this branch. This note is the short design and plan for what was safe to add next, and what stopped.

## Delivered now

- **Offline 1:1 queue (part of cycle C).** `core/src/outbox.rs` keeps at most 100 texts per peer and drops the oldest. Android `AetherNode.send` records the text and queues it when the peer has no live session, then sends those texts when the session reaches online. The queue is in memory. A process restart drops unsent items even though the chat log already shows them. That limit is stated here so it is not mistaken for durable delivery.
- **Delivery receipts (part of cycle C).** On a text receive, Android sends `MessageKind.RECEIPT` whose body is the decimal timestamp. The browser still discards kind 2. Nothing new is drawn.
- **Operator TAK text builder (part of cycle G, no socket).** `prepare_tak_text` returns XML only after the caller supplies a type string limited to ASCII letters, digits, `.`, `_`, and `-`. The struct field `server_visible` is always true. The XML has no `point`, `lat`, or `lon`. It is not tested against ATAK or iTAK, and it is not wired to a button, because a new control needs a UI spec and this build does not change layout, color, or type.

## Stopped

| Cycle | Stopped because |
|---|---|
| C groups | A group cipher would be new cryptography. MLS stays out until its pinned crate, license, and the March 2026 audit are re-checked in their own change. Pairwise fan-out is not shipped, so the product does not pretend to have group end-to-end encryption. |
| D attachments and voice notes | Needs a new envelope kind, a picker, and the wasm bundle moved together. No picker exists that reuses the current composer without a new control. |
| E multi-device | Needs an account relay and history rules. History sync stays forbidden. The no-backup policy is unchanged. |
| F calls | Needs a media stack and, for a forwarder, a disclosure product. No live media server is available here. |
| G live TAK connection | Needs a TAK Server, certificates, and named ATAK and iTAK builds. This environment must not open that connection. |
| H marker publish | Not approved. Own-position streaming stays off. |

Device handshake code still does not read a revocation snapshot. Cycle A's plan keeps that wiring for a later Diagnostics switch so wire behavior of certified peers does not change in this build.
