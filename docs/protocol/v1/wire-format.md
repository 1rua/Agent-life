# Agent Life v1 canonical wire format

This document freezes the byte-level `agent-life-json-es256/1.0` profile. The
machine-readable authority is `protocol/profile/v1.json`, validated by
`urn:agent-life:protocol:v1:profile`. A different canonicalization, digest,
curve, signature encoding, domain, or bound requires a new profile/version.

## Canonical JSON bytes

Every v1 JSON wire value is the UTF-8 encoding of its RFC 8785 JSON
Canonicalization Scheme (JCS) representation. The complete received byte array
must equal the JCS UTF-8 bytes of the parsed value. A UTF-8 BOM, whitespace,
trailing newline, duplicate object key, alternate member order, `-0`, malformed
UTF-8, NaN/Infinity syntax, or lone UTF-16 surrogate is rejected rather than
normalized. Control-envelope wire bytes are limited to 262,144 bytes inclusive.

Every v1 semantic digest is encoded as canonical RFC 4648 base64url: alphabet
`A-Z a-z 0-9 - _`, no `=` padding, and no ignored or alternate characters. A
SHA-256 digest therefore has exactly 43 characters. Unless a field explicitly
names raw bytes as its preimage, a digest of a JSON value is:

```text
base64url_unpadded(SHA-256(JCS_UTF8(value)))
```

In particular, `payload_digest` is
`base64url_unpadded(SHA-256(JCS_UTF8(payload)))`.

## Signature preimage

For a registered signature domain `D` and signable JSON value `V`, the exact
preimage is:

```text
UTF8("agent-life/v1/" || D) || 0x00 || U32BE(len(JCS_UTF8(V))) || JCS_UTF8(V)
```

`U32BE` is an unsigned four-byte big-endian byte length; the NUL is one literal
zero byte. There is no separator after the length. The complete closed domain
set is:

```text
enrollment/app-to-bridge
enrollment/bridge-to-app
control/app-to-bridge
control/bridge-to-app
adapter/adapter-to-bridge
adapter/bridge-to-adapter
key-rotation/app-to-bridge
key-rotation/bridge-to-app
key-rotation/adapter-to-bridge
key-rotation/bridge-to-adapter
migration/bridge
approval/device
ticket/bridge
receipt/device
channel-pop/device
channel-pop/adapter
```

The message registry selects the message-class/direction domain; a wire member
does not select it. For a signed envelope, `V` is exactly the closed object
`{"header": header, "payload": payload}` after JCS canonicalization. The outer
`signature` member is excluded from `V`. The header's `payload_digest` still
covers exactly `JCS_UTF8(payload)`, so the signature covers both the payload and
its header-bound digest.

## ES256 and JWK rules

The only v1 signature algorithm is ECDSA over NIST P-256 with SHA-256 (`ES256`,
also known as `SHA256withECDSA`). A wire signature is exactly 64 raw IEEE P1363
bytes `r || s`, with each integer encoded as 32-byte unsigned big-endian, then
encoded as unpadded canonical base64url. Both integers must satisfy
`1 <= r,s < n`, where
`n = FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551`.
Signatures must be low-S (`s <= floor(n/2)`); high-S signatures are rejected.

A public signing JWK is a closed object with `kty="EC"`, `crv="P-256"`,
`alg="ES256"`, `use="sig"`, a non-empty `kid`, and canonical unpadded base64url
`x` and `y` coordinates that each decode to exactly 32 bytes and form a valid
P-256 point. Private test JWKs add a 32-byte canonical `d` whose derived public
point must equal `x,y`. A verifier accepts public JWKs only and rejects `d`.

The committed keys under `protocol/test-only/keys` are conspicuously insecure,
deterministic fixtures for tests and vector generation only. Their closed
keyring binds each approved ID to a device, Bridge-command, or adapter role and
to current/next generation paths. Production `Signer` and `Verifier` ports do
not load that keyring and do not accept private JWK material.

## Other frozen profile values

All counters, sizes, revisions, generations, offsets, and durations are
unprefixed decimal-u64 strings with no leading zeroes except `0`. Absolute time
uses UTC RFC 3339 with exactly three fractional millisecond digits and `Z`.
Wire-generated IDs use lowercase UUIDv4; server-assigned identity values remain
opaque IDs. The profile fixes 60 seconds of clock skew, a 1,024-slot replay
window, 900 seconds of key-rotation grace, a 900-second `WAIT_READ` ceiling, and
a 300-second channel-ticket lifetime.

Artifact interoperability bounds are 65,536 through 1,048,576 bytes per chunk
(262,144 default), four files per message, 26,214,400 bytes per file,
52,428,800 bytes per message, and 86,400 seconds before orphan cleanup. These
are wire/profile bounds, not an implementation of artifact storage.

This profile defines bytes and abstract signing/verification boundaries only.
It does not claim to implement Android Keystore, hardware-backed keys,
production key custody, Android behavior, or any network transport.
