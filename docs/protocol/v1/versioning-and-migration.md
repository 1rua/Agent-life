# Agent Life v1 Versioning and Migration

## Canonical versions

Protocol versions are canonical `major.minor` decimal strings. Each component is in `0..18446744073709551615`, has no sign and has no leading zero except the value `0`. Comparison is numeric by major and then minor component; lexical ordering is never used.

The production registry is a statically imported, validated, recursively frozen artifact. Callers cannot register versions or substitute a registry-shaped object. It contains negotiable `1.0` and non-negotiable `0.9`; the latter is reserved for the Task 7 conformance fixture and is never selected in Task 4.

## Connection negotiation

Only `connect_hello` and `connect_welcome` are canonical message names. Their raw wire admission occurs before negotiation and binds the closed payload schema, locked message registry, expected signer role and key, payload digest, expiry, device ID, and pairing generation.

The client offer digest is:

```text
b64u(SHA-256(JCS_UTF8(connect_hello.payload)))
```

It covers version ordering, client nonce, last manifest generation, and last event cursor. Selection is the numerically highest exact intersection between the offered versions and negotiable production entries. A welcome repeats the client nonce and selects that same highest version. Offer-digest mismatch is `INTEGRITY_FAILED`, nonce mismatch is `AUTH_BINDING_MISMATCH`, and an absent, invalid, non-negotiable, or downgraded selection is `VERSION_UNSUPPORTED`.

Welcome signature admission uses only the already-pinned Bridge command key. `command_key_set.current` must use the welcome header key ID, `next` is null or has a distinct key ID, and neither key can be used to bootstrap verification of the welcome that carries it. Successful negotiation returns the selected version, both nonces, Bridge time, command key set, connection generation, and retained client-offer digest without mutating state.

## Change rules

- Existing schema files and signatures are immutable. An old schema is never relaxed in place.
- Patch changes cannot alter accepted wire bytes or state semantics.
- Additive negotiated messages require a new minor line.
- Canonicalization, signature, identity, authorization revision, state-machine, or replay changes require a new major line.
- Unknown or unregistered message and schema IDs fail closed; payload resemblance is never a fallback.

## Migration and rollback invariants

Migration creates a new record plus a signed migration receipt. It retains the original schema ID, digest, signature, and record rather than rewriting old signed bytes. Rollback never lowers pairing generation, authorization epoch, scope revision, grant revision, revocation state, or tombstone state.

Task 4 defines these invariants only. It does not migrate operations because the v1 operation contract does not yet exist. Task 7 alone owns the executable `0.9` fixture and migration reducer.
