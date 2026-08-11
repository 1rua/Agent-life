# Task 9 — device events and acknowledgements

`device_event` is an app-to-Bridge signed control envelope. Its source epoch,
cursor, occurrence and capture revision are authenticated facts of the paired
device; no Agent, workspace, session or job identity is accepted on the wire.
The Bridge resolves delivery from an authenticated server-side subscription
key `(tenant, human, device, source_capability)`.

`event_ack` is a Bridge-to-app signed control envelope containing only the
source epoch, source capability and highest contiguous cursor. The Bridge must
persist an accepted event before producing its ACK. App queue deletion occurs
only after the matching ACK is durably received. Cursor buffering, gap/resync,
collector behavior and production database durability remain outside P0a.

## Lifetime and replay

Both messages use the existing `task5_default` replay class and namespace.
`device_event` expires no later than 86,400 seconds after issuance;
`event_ack` expires no later than 300 seconds. Retention is the existing
Task-5 rule, `max(expires_at, admitted_at + 86,400 seconds)`. No caller may
select a replay policy or bypass replay admission.

The three event branches are mutually exclusive:

- `upsert`: record key/revision and record are present; loss is `null`.
- `delete_tombstone`: record key/revision are present; record and loss are `null`.
- `loss_marker`: key/revision/record are `null`; loss contains a closed cursor
  range and reason.

Task 9 ships reference validation and deterministic ports only. It does not
claim a device collector, durable cursor store, production subscription store,
or production event router.
