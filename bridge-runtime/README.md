# bridge-runtime local adapter and ingress seam (WP-06)

`src/file-backed-store.ts` is a deterministic, database-neutral development
adapter for the `DurableBridgeStore` port in `bridge-contract`. It uses only
Node's `fs/promises` API and publishes immutable JSON generations with
temporary-file-plus-rename writes. `manifest.json` is the publication pointer;
`recover()` validates the pointer and state generations, removes incomplete
temporary/corrupt/orphan files, and can rebuild a missing or invalid manifest
from the newest valid generation.

`src/durable-state-repositories.ts` persists pairing tickets/bindings,
notification records/positions, subscription bindings/events and ACK state.
Each related transition uses one `DurableBridgeStore` transaction and reopen
validation rejects malformed or incomplete cross-namespace state.
`src/durable-operation-dispatcher.ts` persists operation claims/results and can
atomically bind an authenticated replay association to a new claim. Persisted results use a closed JSON
representation with an explicit bigint tag (so notification cursors survive a
reopen); unsupported values fail closed instead of being lossy-serialized.
Reopening the local dispatcher reclaims pending claims left by a simulated
process crash.

`src/production-ports.ts` and `createFencedDurableBridgeComposition` add a
production-shaped boundary that rejects anything except connected external
SQLite, ticket-verifier and multi-process lease ports. All repository and
operation transactions then pass through the current fencing token. This is a
source port, not an implementation: the local adapter has no multi-process
lease or worker-fencing protocol. `src/backup-restore-drill.ts` verifies an
external adapter's isolated restore by comparing selected durable namespaces;
the checked-in execution is deterministic fake evidence, not a live drill.

The adapter remains intentionally local-only. `src/ingress.ts` adds the
production-shaped authorization/lifecycle seam and `src/health.ts` adds
framework-neutral `/health/live` and `/health/ready` handlers, but neither
opens a socket. Ingress stays `pending` and never calls its listener while the
controller's `MVP-DEP-TSNET` row is pending; once locked, only an injected
userspace listener with the ticket-bound fingerprint and port can bind. The
pairing, fingerprint, pairing-generation, connection-generation and replay
checks are deterministic seams for the eventual signed P0a adapter, not a
claim of real network authentication.

`deploy/` contains systemd and Docker Compose templates. They intentionally
have no public port mapping or `ListenStream`; production operators must fill
the immutable tsnet lock and adapter image/digest before enabling the service.
The `durability: "durable"` marker means only that this adapter satisfies the
reviewed transaction-port shape and its local crash-recovery test seam; it is
not evidence of production readiness.

## Local verification

From the repository root (the checked-in lock is still pending, so the
workspace's existing toolchain is used):

```bash
./bridge-runtime/node_modules/.bin/vitest --root . run bridge-runtime/test bridge-contract/test
npm --prefix bridge-runtime run typecheck
```

The package manifest records the expected `@types/node`, TypeScript and Vitest
versions; it does not claim that those dependencies are locked or installed in
the release environment.

`src/migration-runner.ts` supplies the source-level versioned migration
contract: steps are immutable, contiguous one-version transitions and each
step must be committed by the external adapter together with its schema
version. `bridge-contract/src/persistence.ts` fixes the SQLite adapter port
for transactions, migrations, backup/restore, and crash recovery and closes
the pairing, authorization, notification cursor, subscription, operation
claim/replay-association, and assistant-metadata namespaces. This repository
intentionally has no SQLite driver, secret-store verifier or lease coordinator.
An adapter therefore remains
`status: "external-driver-required"` until the controller locks and deploys a
real driver; these source contracts and tests are not production database
readiness evidence.
