# Protocol v1 operations and security-ledger contract

This document is the Task 7 reference contract for operation submission,
execution claims, receipts and replay-ledger accounting. It is a protocol
oracle, not a claim that a durable Bridge database or Android adapter has been
implemented. The product decision record is
`.superpowers/sdd/2026-08-08-p0a-protocol-security-model/task-7-product-decisions.md`.

## Approved product decisions

- D1 = A: compacted rows become permanent six-key tombstones. Every security
  message uses the same `ReplaySpace`; a full space returns
  `SECURITY_LEDGER_FULL` before operation registration or execution. Clients
  use bounded polling/long-poll and an authenticated key rotation creates a
  new space. Tombstones are retained read-only in the old space.
- D2 = accepted: the logical per-space security quota is exactly
  `16,384 × (262,144 + 262,144 + 65,536) = 9,663,676,416` bytes. The counter
  uses `bigint`; equality is admitted and one byte above a bound is rejected.
  Physical database/allocator overhead is not part of this logical quota.
- D3 = accepted: a security row is not eligible for default compaction until
  a verified signed Bridge ACK exists and
  `retention_until = max(operation_expires_at, bridge_ack_at + 30 days)`.
  The boundary is inclusive (`now == retention_until` admits compaction), and
  ACK/compaction rewrites are atomic and idempotent.
- D4 = accepted: a durable execution claim without a trustworthy result
  recovers as terminal `result_unknown` and never auto-reexecutes. Only a verified late receipt or external-state evidence may append a
  reconciliation record; it cannot rewrite the original terminal outcome.

## Operation state and side-effect rule

An operation has one stable operation ID and one authoritative parameters
digest. The only terminal outcomes are `succeeded`, `failed`, `denied`,
`cancelled`, `expired` and `result_unknown`. A side effect follows the durable
sequence `verify → claim CAS → one invocation → durable result → signed
receipt → repeated delivery until signed Bridge ACK`. A network retry or a
crash after a durable claim never invokes the side effect a second time.

`result_unknown` is deliberately conservative: recovery cannot infer that an
external call did not start from a test fault hook. Reconciliation is
append-only and records verified evidence without changing the first terminal
state.

## Security-ledger accounting

The security policy owns 16,384 rows per `ReplaySpace`, a 262,144-byte inbound
raw-wire ceiling, a 262,144-byte receipt reservation, a 65,536-byte persisted
recovery-intent metadata ceiling and a 2,048-byte tombstone ceiling:

```text
N = 16,384
I = 262,144
R = 262,144
M = 65,536
T = 2,048

max security charge = N × (I + R + M)
                   = 16,384 × (262,144 + 262,144 + 65,536)
                   = 9,663,676,416 bytes
```

The charged bytes are state-dependent and contain no unlisted overhead:

- pending/abandoned: `raw_wire_length + R + intent_metadata_length`;
- finalized: `raw_wire_length + receipt_length + intent_metadata_length`;
- compacted: exact UTF-8 length of the closed JCS tombstone projection.

The compacted projection is exactly
`{ envelope_digest, message_id, message_type, sequence, space, status }` with
`status: "compacted"`. It consumes one of the same 16,384 rows forever and
must be at most `T` bytes; compaction replaces the active charge atomically and
does not release a row. An exact duplicate after compaction is non-executing
`REPLAY_REJECTED/COMPACTED_DUPLICATE`; the same message ID with a different
full-envelope digest is `INTEGRITY_FAILED/MESSAGE_ID_CONFLICT`.

Security and ordinary replay classes share one sequence window and one global
message-ID/full-envelope-digest conflict index. Their counters and policy
limits are independent: no class may borrow rows, bytes or receipt
reservation from the other class.

## ACK, compaction and restart invariants

The Bridge persists a verified ACK before signing it. The ACK transaction is
idempotent for the same receipt and rejects a conflicting receipt or a clock
rollback without changing the first accepted ACK. Compaction at the exact
retention boundary is allowed; one millisecond before is rejected. A restart
recomputes rows, tombstones, sequence windows, conflict indexes and exact
state-dependent charges from durable rows and fails closed on any counter,
metadata, policy or index mismatch.

The checked-in vectors at
`protocol/test-only/replay/v1/compaction-recovery-vectors.json` are immutable
cross-language oracles. Tests reconstruct complete RFC 8785 JCS UTF-8 bytes
from each `persisted_projection` and compare them to the standard padded
Base64 field and canonical decimal byte length; expected fields are not
trusted as input.

## Bounded polling and implementation boundary

`operation_get`, `operation_wait` and `operation_snapshot` are security
messages and therefore consume the same permanent row budget. Clients must
use bounded polling/long-polling and surface `SECURITY_LEDGER_FULL` so an
operator can perform an authenticated key rotation. This reference contract
does not implement a database, scheduler, key-rotation runbook or Android
collector; those are subsequent implementation/review gates.
