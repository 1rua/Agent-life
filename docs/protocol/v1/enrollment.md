# open-android-intelligence v1 Enrollment

This document freezes the P0a enrollment contract. It defines application-level device enrollment only. Tailscale node registration, auth-key issuance, trust-credential storage, node lifecycle, policy attestation, and orphan cleanup are not implemented or modeled by Task 4.

## Trust and ownership

The Bridge issues a random, opaque, single-use enrollment ticket from an already authenticated human session. Its lifetime is at most five minutes from issuance; a longer lifetime is invalid. The server-side ticket record is the sole authority for tenant, human principal, Agent instance, enrollment scope ceiling, challenge, Bridge identity, Bridge command key, Bridge nonce, and expiry. An App response cannot submit or override ownership, `device_id`, or `pairing_generation`.

Tickets carry 32 random bytes as canonical unpadded base64url. Lookup uses `b64u(SHA-256(base64url_decode(ticket)))`; plaintext tickets are neither identifiers nor audit fields. The store atomically enforces both the at-most-five-minute expiry and single use in the same compare-and-set operation: an active record at or beyond its expiry transitions to terminal expired and cannot win consumption. Unknown, expired, and already-consumed tickets all produce `AUTH_FAILED`. A winning consume remains consumed even if later schema, signature, digest, expiry, binding, version, or principal-limit validation rejects the request.

Re-pairing creates a new non-reusable device ID and advances the Bridge-held pairing generation before the old binding is revoked. Reinstallation creates a new installation identity and device record. Client-provided names, keys, or identifiers cannot reclaim a revoked record.

## Wire families

Enrollment uses its own closed signed union and never reuses a paired control header:

- `enrollment_challenge`, `enrollment_complete`, and `enrollment_error` travel Bridge to App under `enrollment/bridge-to-app`.
- `enrollment_response` travels App to Bridge under `enrollment/app-to-bridge`.
- Enrollment headers contain no sequence, paired identity, connection generation, Agent session, operation, transport-mode claim, or signature-domain field.
- Every envelope is exactly `{ header, payload, signature }`. The locked message registry binds type, payload schema, direction, and signature domain.

Ticket, challenge, client nonce, and Bridge nonce are exactly 32 bytes. Public JWKs are closed P-256 ES256 signing keys. A JWK thumbprint is `b64u(SHA-256(JCS_UTF8({crv,kty,x,y})))` with no other JWK members included.

## Admission order

Public admission accepts raw wire bytes only. There is no public already-parsed path and no caller-supplied schema or message registry.

Every response intake samples wall time and monotonic time once, then invokes IP admission exactly once. The retained IP decision cannot mask size or canonical-parse failure. After a canonical parse, a denied IP decision returns `RATE_LIMITED` before ticket lookup, full schema validation, or signature work.

An IP-allowed intake performs the only pre-full-schema exception: it extracts the ticket, enforces canonical 32-byte decoding, derives its digest, and atomically consumes a known active record. A winning consume is followed by exactly one principal admission call. The retained principal decision is exposed only after this fixed order succeeds:

1. closed envelope and payload schema;
2. internally locked message-registry tuple;
3. device key binding and ES256 signature;
4. constant-time payload-digest comparison;
5. message expiry;
6. ticket, challenge, Bridge key/fingerprint, and version binding.

Thus validation errors take precedence over principal rate limiting. Rejected requests create no device binding and invoke no external system.

Bridge-to-App enrollment admission follows size, canonical parse/schema, locked registry tuple, trusted signature, digest, expiry, and ticket/transcript binding order. A challenge key is trusted only after its projected thumbprint matches the separately QR-pinned Bridge fingerprint and its `kid` matches the signed header. Complete and error messages resolve only the expected pinned Bridge command key through the verifier; a payload key cannot verify the message that carries it. A valid signature under the same Bridge key cannot cross a ticket or pending transcript.

## Rate windows

IP and human-principal windows are independent. Each retains at most the admitted timestamps within the preceding 600,000 monotonic milliseconds. Fewer than five retained timestamps admits and appends once. Otherwise admission appends nothing and returns the ceiling of the remaining oldest-timestamp delay in canonical decimal seconds. Denied calls never extend the horizon. Wall-clock changes do not affect rate windows; monotonic changes do not expire tickets or messages.

## Pairing confirmation

Both sides independently canonicalize:

```text
{ ticket_digest, bridge_fingerprint, challenge, client_nonce,
  bridge_nonce, device_jwk_thumbprint, selected_protocol }
```

They hash the `open-android-intelligence/v1/pairing-short-code` NUL-terminated, four-byte-big-endian-length-prefixed JCS preimage with SHA-256, take the first 50 bits, and render ten uppercase Crockford Base32 characters as `XXXXX-XXXXX`. The alphabet excludes `I`, `L`, `O`, and `U`. Enrollment completes only after the user confirms that both displays match.

## Public errors

Enrollment exposes only `MESSAGE_TOO_LARGE`, `SCHEMA_INVALID`, `AUTH_FAILED`, `INTEGRITY_FAILED`, `MESSAGE_EXPIRED`, `AUTH_BINDING_MISMATCH`, `RATE_LIMITED`, and `VERSION_UNSUPPORTED`. Parser details and ticket disposition remain internal. `RATE_LIMITED` alone carries canonical decimal `retry_after_seconds`; every other error is exactly `{ code }`.
