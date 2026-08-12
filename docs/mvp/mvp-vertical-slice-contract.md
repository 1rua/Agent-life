# Agent Life MVP vertical-slice contract

Version: `v1` · protocol family: `1.0` · status: implementation baseline

The MVP uses closed JSON Schema 2020-12 documents under
`mvp-contract/schemas/v1/`. Unknown properties are rejected. The Android app
never accepts an endpoint from a model or an agent; it sends only through the
ticket-bound userspace Tailnet Bridge.

## Notification policy

`notification-policy.schema.json` has four fields: `mode` (`allowlist` or
`denylist`), Unicode-code-point-sorted unique `package_ids`, `field_access`
(`metadata` or `content`), and monotonic decimal `policy_revision`. A fresh
installation is an empty allowlist with metadata access at revision `0`.
Denied notifications are dropped before the collector's active map, auto-send
flow, or encrypted outbox.

## Notification records and API

`notification-record.schema.json` is a closed union of `upsert`,
`delete_tombstone`, and `loss_marker`. Metadata mode always carries
`content: null`; only an authorized content policy may include title/body.
Records contain source epoch, cursor, occurrence identity, and capture
revision, but never agent, workspace, session, or job identity.

`notification-api.schema.json` exposes the frozen tools
`mobile.notifications.query`, `.subscribe`, and `.unsubscribe`. Query mode is
`on_demand` or `auto_send`; limits are 1–100. Query, subscription, and
unsubscription requests all require the current `policy_revision`; query and
subscription may carry the same closed package allowlist and `metadata|content`
field selection. Responses explicitly distinguish `complete`, `waiting_device`, and
`failed`.

## SMS read vertical slice

The SMS capability is a read-only, inbox-only vertical slice. Android reads
only `Telephony.Sms.Inbox.CONTENT_URI`; it does not read MMS, sent, draft, or
outbox provider URIs and it does not send SMS. `READ_SMS` is requested only
from the app-local settings flow. The manifest must not declare `RECEIVE_SMS`
or `SEND_SMS`, and the scheduled job service is non-exported and protected by
`BIND_JOB_SERVICE`.

An authorized SMS scope releases the complete SMS body, including an empty
body, rather than a summary or a truncated preview. This content release is
conditional on a local user grant and the matching current policy revision; it
is not evidence that an Agent can grant, broaden, or mutate permission. SMS
metadata is closed to record ID, sender address, thread ID, message timestamp,
observed timestamp, read state, and nullable subscription ID.

History start and maximum-record settings are local, bounded by the closed
`SmsHistoryPolicy` contract (one through 10,000 records). Sync interval is the
closed `SmsSyncInterval` set: manual, 15 minutes, 30 minutes, or 60 minutes.
Periodic work is best-effort under Android `JobScheduler`, not an exact-time or
delivery guarantee. The app retains accepted event wire bytes in the encrypted
capability outbox until a verifier-approved Bridge acknowledgement; failures,
missing pairing, or policy revocation retain rather than silently drop work.

The Bridge admits only `mobile.sms.query`, `mobile.sms.subscribe`, and
`mobile.sms.unsubscribe`. Hermes and OpenClaw expose those same frozen
operations through the shared adapter contract; they do not receive arbitrary
capability, identity, MMS, attachment, endpoint, or permission-mutation
inputs.

Host checks and Kotlin/TypeScript tests establish source and contract behavior.
They do not establish Android SDK, emulator/device permission, provider-data,
native AAR, or deployed Bridge evidence. In particular, the current source
uses persisted periodic `JobScheduler` work while the permission allowlist
forbids `RECEIVE_BOOT_COMPLETED`; Android restart persistence normally needs
that permission. This unresolved persisted-JobScheduler versus
`RECEIVE_BOOT_COMPLETED` conflict must be resolved in a later reviewed design
before claiming reboot-resilient scheduling.

## Assistant chat

`assistant-chat.schema.json` keeps the MVP text path closed and bounded. It
also defines metadata-only image/file attachments (digest, media type, size,
display name) without accepting upload URLs or model-supplied invocation
context. The artifact transport remains the post-MVP M1.1 gate.

The deterministic fixtures and boundary tests are in
`mvp-contract/test/mvp-contract.test.ts`. Runtime Bridge value objects are not
wire payloads: `mvp-contract/src/wire-codec.ts` is the closed conversion seam
that maps camel-case records/attachments to the schema's snake-case fields and
rejects extra or unrepresentable fields before egress.
