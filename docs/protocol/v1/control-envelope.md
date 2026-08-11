# Signed control envelopes and security fencing

Protocol v1 control traffic uses one canonical signed container:

```text
{ header, payload, signature }
```

The wire value is strict RFC 8785 JSON encoded as UTF-8. The signature is canonical
unpadded Base64url IEEE-P1363 ES256, and covers the Task 3 domain-separated preimage
of `{header,payload}`. `payload_digest` is the Base64url SHA-256 digest of the exact
canonical payload. The full-envelope digest used by replay and rotation journals is
the Base64url SHA-256 digest of the complete canonical signed wire bytes, including
the signature.

## Closed message families

The public schema catalog contains one named message, header, and envelope leaf for
each of the nine Task 5 types. Paired-device headers add `device_id`,
`pairing_generation`, and `connection_generation`; adapter headers add
`adapter_credential_id` and `adapter_credential_generation`. Every named leaf fixes
its message type, payload schema, direction, signature domain, and signer role.
Internal family shells have no public `$id` and are used only for the initial
trusted-ingress parse. There is no generic control-message fallback or generic ACK.

Pings carry a decoded 32-byte challenge. A response uses the opposite-direction
ping and echoes it. Presence accepts only `online` and `idle`; offline state is
inferred when the authoritative connection is fenced or closed. Rotation proposals
carry the old key ID, an exact P-256 public JWK, its RFC 7638-style `{crv,kty,x,y}`
thumbprint, a challenge, and a rotation ID. ACKs echo the transcript and bind
`proposal_digest` to the complete signed proposal. Adapter rotation additionally
advances the credential generation by exactly one.

## Admission order

Ingress samples the trusted wall clock once and applies this fixed precedence:

1. envelope byte limit;
2. strict canonical JSON, trusted family shape, and signature encoding;
3. one immutable registry row selected by `message_type`;
4. the row's exact schema, direction, domain, and signer-role tuple;
5. the complete named envelope/header/payload leaf;
6. server-loaded key lifecycle and ES256 verification, then payload-digest integrity;
7. timestamp relation, future skew, expiry, and the 60/300-second lifetime limit;
8. current device connection or adapter credential generation;
9. equality with the independently loaded credential and principal binding;
10. the injected authorization/revision gate;
11. store-owned atomic lease recheck and replay admission.

Wire identities are equality claims only. HTTP fields, Tailnet metadata, transport
profiles, payload authority fields, and caller-selected schemas or keys never select
authority. The key record authorized by lifecycle is the same record whose
`publicJwk` verifies the signature.

## Replay and recovery

Replay spaces are isolated by credential, pairing or adapter generation, direction,
and key ID. They deliberately omit connection generation and transport, so HTTPS and
WSS and authenticated reconnects share one 1,024-sequence bitmap window. Bit zero is
the highest sequence and bit 1,023 is the oldest admissible reordered sequence.

The branch-specific admission backend owns the linearizable message-ID lookup,
lease recheck, replay-window update, capacity check, and insertion. An accepted row
is committed as `pending` together with its immutable raw wire and mandatory closed
recovery intent. Ordinary ingress sees a duplicate pending row only as
`REPLAY_REJECTED`; only a branded reconciler may reconstruct the opaque accepted
frame and continue a protocol journal. Finalized exact duplicates return defensive
receipt bytes, while a message-ID conflict with different wire integrity fails.

Task 5 reserves 16,384 receipt bytes per pending ordinary claim and caps each replay
space at 4,096 retained rows and 67,108,864 retained bytes. Retention charges raw
wire bytes, canonical persisted-intent metadata bytes, and the pending reservation
or finalized receipt. Safe compaction never removes pending/current-window or
referenced evidence and never relaxes retention under pressure.

## Connection and adapter fences

Device connection allocation is a durable fetch-increment keyed by credential and
pairing generation. The opaque lease binds the concrete connection, transport
profile, generation, and fence revision. Stage 8 checks the current snapshot; the
same physical admission backend rechecks it before replay mutation at stage 11.
Older and unallocated future generations are fenced. Lost welcomes consume their
generation, and decimal-u64 exhaustion never wraps.

Adapter admission uses the corresponding opaque credential lease over the committed
credential/principal snapshot. Rotation of the authoritative adapter generation and
adapter replay admission serialize in one backend: whichever commits first either
admits one recoverable intent or fences the old lease with zero replay mutation.

## Outbound and key rotation

Outbound preparation derives sequence, replay space, registry tuple, direction,
domain, role, and active key inside a durable transaction. It signs and immediately
self-verifies with that same active `KeyRecord.publicJwk`, revalidates the full closed
wire, and retains an immutable byte copy before exposing anything. Ordinary traffic
returns an opaque `SendableOutboundEnvelope`. Rotation preparation returns only a
`PreparedRotationHandle`; the matching device or adapter rotation journal must
commit before those retained bytes become sendable.

Rotation transitions are branch-specific and atomic with lease recheck, replay
finalization, ring mutation, journal, activation outbox, and retained outbound bytes.
After ACK activation only the new key signs. The old key is verify-only for envelopes
issued no later than activation and for less than 900 seconds, then becomes archived
historical evidence. Revocation disables live use without deleting retained public
evidence. Lost device or Bridge keys require re-pairing; lost adapter keys require a
server-side credential reissue and generation advance.
