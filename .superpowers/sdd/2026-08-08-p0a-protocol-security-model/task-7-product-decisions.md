# Task 7 product/security decision checklist

Status: `APPROVED_USER_CONFIRMED`

This checklist is the approval companion to `task-7-amendment-draft.md` (now the canonical-ready amendment). It records the user's product/security choices and keeps runtime implementation status separate. The approved design is `docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md`; the technical findings are in `task-7-readiness-report.md`.

## Recorded approval

The product owner confirmed the following choices in the implementation
session.  The confirmation is recorded as a product decision; no personal
identity was supplied, so the approver field intentionally says
`user-confirmed-in-session` rather than inventing a name or security review
ticket.  This record approves the reference contract only.  Runtime durable
backend integration remains pending its own implementation and review.

```text
decision_id: TASK7-D1
selected_option_or_acceptance: A
approver_role: product owner (user)
approver: user-confirmed-in-session
date_utc: 2026-08-11
rationale: retain permanent tombstones and use bounded polling/long-poll; rotate the authenticated key when a ReplaySpace is exhausted.
follow_up_amendment: none

decision_id: TASK7-D2
selected_option_or_acceptance: ACCEPT
approver_role: product owner (user)
approver: user-confirmed-in-session
date_utc: 2026-08-11
rationale: use the exact bigint logical quota 16384 × (262144 + 262144 + 65536) = 9663676416 bytes; tombstones replace the active charge and never free a row.
follow_up_amendment: none

decision_id: TASK7-D3
selected_option_or_acceptance: ACCEPT
approver_role: product owner (user)
approver: user-confirmed-in-session
date_utc: 2026-08-11
rationale: retain a security row through max(operation expiry, signed Bridge ACK time plus 30 days); ACK and compaction are atomic and idempotent.
follow_up_amendment: none

decision_id: TASK7-D4
selected_option_or_acceptance: ACCEPT
approver_role: product owner (user)
approver: user-confirmed-in-session
date_utc: 2026-08-11
rationale: recover every durable claim without a trustworthy result as result_unknown and never auto-reexecute; only verified reconciliation may append evidence.
follow_up_amendment: none

implementation_status: reference artifacts approved; runtime backend integration pending
```

## Blocking decisions

### D1 — Permanent tombstones and polling exhaustion

The baseline replay model has one namespace per `ReplaySpace`, all ten Task 7 security message types in that namespace, 16,384 rows, and one permanent tombstone per compacted message. `operation_get`, `operation_wait` and `operation_snapshot` therefore consume permanent rows. At 16,384 rows the old space returns `SECURITY_LEDGER_FULL` before operation registration/execution; only authenticated key rotation (a changed `keyId`, hence a new `ReplaySpace`) creates capacity. Old tombstones remain retained/read-only.

| Option | What changes | Consequence | Decision |
|---|---|---|---|
| A — Accept baseline | Keep permanent tombstones and all polling rows; clients use bounded polling/long-poll and rotation runbooks. | Strongest replay/dedupe evidence; finite per-key lifetime and explicit exhaustion. | **Selected** |
| B — Polling checkpoint/coalescing | Move/coalesce polling into a separately specified bounded cursor/checkpoint class. | Better liveness; changes shared sequence/conflict/replay proof and needs a new amendment. | **Pending design approval** |
| C — Finite tombstone retention | Release tombstones after a defined horizon. | Recovers capacity; old duplicates can reappear unless a new epoch/nonce proof is added. | **Pending design approval** |
| D — Automatic key rotation | Rotate keys near exhaustion. | Requires authenticated lifecycle, fencing, durable failure semantics; does not delete old tombstones and cannot be implicit. | **Pending design approval** |

The user selected A. B/C/D remain unselected alternatives and would require a separate protocol amendment.

### D2 — Quota formula

Confirm this exact logical accounting invariant:

```text
N = 16,384 rows per ReplaySpace
I = 262,144 inbound raw-wire bytes per security row
R = 262,144 receipt reservation bytes per pending/abandoned row
M = 65,536 intent-metadata bytes per row
T = 2,048 compacted-tombstone bytes per row

max security charge = N × (I + R + M)
                   = 9,663,676,416 bytes
```

Pending/abandoned charge: `raw_wire + R + intent_metadata`.

Finalized charge: `raw_wire + receipt + intent_metadata`.

Compacted charge: exact JCS UTF-8 length of the six-key tombstone projection, `<= T`; it replaces the active row charge and does not release the row. `bigint` arithmetic, canonical decimal-u64 persisted counters, equality admitted, strict greater-than rejected, no partition borrowing, and no physical storage overhead are required. Any alternative formula is a protocol change.

**Decision:** `ACCEPTED_USER_CONFIRMED`.

### D3 — Compaction/ACK retention

Confirm: no compaction before verified signed Bridge ACK; `retention_until = max(operation_expires_at, bridge_ack_at + 2,592,000 seconds)`; just-before rejects, equality admits, one millisecond after admits; ACK and compaction rewrites are atomic/idempotent; duplicate-after-tombstone is non-executing `REPLAY_REJECTED/COMPACTED_DUPLICATE`; changed digest is `INTEGRITY_FAILED/MESSAGE_ID_CONFLICT`.

**Decision:** `ACCEPTED_USER_CONFIRMED`.

### D4 — Recovery/result-unknown

Confirm: every durable claim without a trustworthy result becomes terminal `result_unknown` after restart, never auto-reexecutes, and only accepts verified late receipt/external-state reconciliation. ACK loss, outbox replay and compaction recovery preserve the first trustworthy result and are idempotent. Test fault hooks are not production evidence.

**Decision:** `ACCEPTED_USER_CONFIRMED`.

## Required checked-in vectors

`protocol/test-only/replay/v1/compaction-recovery-vectors.json` MUST be immutable and closed. Every vector contains semantic input, expected decision/state, exact persisted projection, standard padded Base64 of complete JCS bytes, and canonical decimal-u64 byte lengths. Tests reconstruct and byte-compare bytes; they never trust expected digest/length fields.

Required coverage:

- Row/component boundaries: 16,383/16,384/16,385 rows; 262,143/262,144/262,145 inbound and receipt bytes; 65,535/65,536/65,537 intent metadata bytes.
- Total boundaries: 9,663,676,415/9,663,676,416/9,663,676,417 logical bytes using sparse counters (never allocate 9.6 GiB).
- Tombstone boundaries: 2,047/2,048/2,049 bytes.
- Retention: before/equal/after operation expiry and before/equal/after `bridge_ack_at + 30 days`.
- ACK: first, exact retry, conflicting receipt, clock rollback, and every ACK crash cut.
- Compaction: before replacement; after tombstone/index but before counter; after counter but before payload deletion; after deletion; restart must yield complete pre- or post-transaction state only.
- Recovery: active/finalized/abandoned/tombstone rows, stale counters, metadata-length mismatch, policy-registry mismatch, conflict-index mismatch, exact duplicate after tombstone, changed digest after tombstone, and duplicate after restart.
- Execution crash cuts: before replay commit, replay/register split, claim/effect/result split, result/finalization split, and ACK delivery loss; all restart to one operation, at most one invocation, first result and stable receipts.

## Decision record

```text
decision_id: TASK7-D1 | TASK7-D2 | TASK7-D3 | TASK7-D4
selected_option_or_acceptance: <A/B/C/D or ACCEPT/REJECT>
approver_role: <product + security owner>
approver: <name/ID>
date_utc: <timestamp>
rationale: <operational and security rationale>
follow_up_amendment: <none or exact document/commit>
```

The D1–D4 choices are recorded above. This does not claim that a durable production backend has been implemented or reviewed; runtime integration remains a separate gate.
