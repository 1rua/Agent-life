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

## Assistant chat

`assistant-chat.schema.json` keeps the assistant request path closed and
bounded for text plus user-recorded AAC/M4A audio. Image, file, and audio
attachments are metadata-only references to opaque, committed Bridge-issued
`artifact_id` values; no Base64, URI, path, URL, provider handle, or
model-supplied invocation context crosses the chat wire. Audio is
`audio/mp4`, at most 10 MiB and 120 seconds, and remains an artifact rather
than inline message data. The phone does not transcribe it; the paired agent
receives the committed artifact for its own processing.

Bridge accepts an attachment only after the current session, pairing,
connection-generation, policy, digest, media type, size, and (for audio)
`duration_ms` fences match a committed artifact record. Requests also retain
the existing four-attachment limit, 25 MiB limit for each image/file, and
50 MiB aggregate limit, plus current zero-retention evidence and authorization
checks. Ordered assistant replies use `delta`, `complete`, and `failed` events
with positive monotonically contiguous sequences; replay returns only events
after its requested sequence boundary through the Bridge event-store boundary.

This backend slice deliberately does not add an Android recorder, microphone
permission, TTS, Compose/UI, animation, transcription, or provider transport.
The future assistant surface can consume the SDK-independent Android audio
and reply-event value objects after the artifact and Bridge commitments are
available.

The deterministic fixtures and boundary tests are in
`mvp-contract/test/mvp-contract.test.ts`. Runtime Bridge value objects are not
wire payloads: `mvp-contract/src/wire-codec.ts` is the closed conversion seam
that maps camel-case records/attachments to the schema's snake-case fields and
rejects extra or unrepresentable fields before egress.
