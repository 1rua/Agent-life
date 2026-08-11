# P0a product/security gate decisions

Status: **product decisions recorded; implementation and production gates remain
separate**.

Recorded: **2026-08-11 UTC** from the user's confirmation in the Agent Life
MVP discussion. This file records product/security choices only; it does not
approve a protocol implementation, dependency lock, Android build, P0t device
run, Tailscale AAR, or production Bridge deployment.

## Task 7 — operation security ledger

| Decision | Accepted value | User-facing meaning |
| --- | --- | --- |
| D1 | **A — permanent tombstones and bounded polling** | Keep a small permanent replay marker for each compacted operation. Polling is bounded; key rotation is the explicit capacity recovery path. |
| D2 | **Exact maximum logical charge: 9,663,676,416 bytes** | The security ledger has a fixed logical quota. At the limit, a new operation is rejected rather than silently evicting security history. |
| D3 | **`max(operation_expiry, bridge_ack_at + 30 days)`** | After a verified Bridge ACK, the operation remains replay-addressable for at least 30 days, and never past the operation expiry rule. |
| D4 | **`result_unknown`; no automatic retry** | If recovery cannot prove whether a side effect happened, the operation is not run again automatically. Later reconciliation may add evidence without rewriting the terminal outcome. |

These choices accept the finite-per-key replay behavior and the explicit
`result_unknown` recovery behavior. They do not waive the protocol's required
schema, vector, implementation, or production evidence gates.

## Task 9 — device-event product literals

| Decision | Accepted value | User-facing meaning |
| --- | --- | --- |
| Event lifetime | **`device_event`: 24 hours** | An undelivered device event is not accepted indefinitely. |
| ACK lifetime | **`event_ack`: 5 minutes** | An event acknowledgement has a short validity window and cannot be replayed as a long-lived command. |
| Replay policy | **`task5_default`** | Device events and ACKs use the existing default replay protections; they do not create a caller-selected or second replay policy. |

The Task 9 **product literals are accepted**. The checked-in Task 9 event
contract is a reference contract only. Its fixed cross-language vectors,
production cursor/ACK durability, and shared pre-replay integration remain
pending, so the Task 9 technical readiness preflight is still a release
blocker.

## Machine-readable acceptance lines

The readiness audit consumes only the following exact lines. Keep them in sync
with the tables above; changing a value is a new product/security decision and
must be reviewed as such.

```text
decision_id=TASK7-D1 status=accepted value=A
decision_id=TASK7-D2 status=accepted value=9663676416
decision_id=TASK7-D3 status=accepted value=max(operation_expiry,bridge_ack_at+2592000s)
decision_id=TASK7-D4 status=accepted value=result_unknown_no_auto_retry
decision_id=TASK9-event-lifetime status=accepted value=device_event=86400s,event_ack=300s
decision_id=TASK9-replay-policy status=accepted value=task5_default
decision_id=TASK9-technical-gate status=pending value=reference_contract_only
```
