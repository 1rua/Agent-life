# Bridge persistence and migration readiness

Status: **durable source composition PASS; external database/secret/lease dependencies PENDING**.

The repository now exposes a closed `DurableBridgeStore` namespace set covering
pairing tickets/bindings, authorization grants/revisions, notification records
and cursors, subscription bindings/events, operation claims/replay
associations, and assistant metadata. `bridge-contract/src/persistence.ts` defines the external SQLite
adapter port with atomic transactions, contiguous schema migrations,
backup/restore, and crash recovery. `bridge-runtime/src/migration-runner.ts`
validates and runs versioned migration steps and verifies that the adapter
published each target version.

`bridge-runtime/src/durable-state-repositories.ts` persists verified pairing
acceptance (ticket consumption plus binding), notification record plus cursor,
subscription binding/event publication, unsubscribe cleanup, and ACK state in
single transactions. Reopen validation rejects malformed records, orphan
events, cursor references without records, and consumed tickets without a
compatible binding. `DurableOperationDispatcher.executeWithReplay` commits a
replay association with its operation claim before the external action.

`createFencedDurableBridgeComposition` accepts only connected external SQLite,
ticket-verifier, and lease-coordinator ports. Every state transaction is routed
through an adapter-issued monotonic fencing token. The lease port requires
lease validation and the state callback to occur in the same database
transaction; there is deliberately no local implementation. The returned
`productionClaim` remains `source-seam-only`.

`runBridgeBackupRestoreDrill` is an executable verification seam for an
offline/lease-exclusive drill. It compares selected source and isolated restore
namespaces and verifies schema version, backup path, optional digest, restore,
and recovery reports. Tests execute it deterministically against fakes; no live
production backup has been run.

This is a source-level contract and deterministic test evidence only. There is
no SQLite driver, database file, secret store, production lease coordinator,
migration deployment, backup schedule, or live restore drill checked into this repository. The adapter
must remain `status: "external-driver-required"` until the controller locks the
runtime/driver, immutable version or digest, deployment image and executable
backup/restore/recovery commands. A local file adapter or test fake must not be
reported as production database readiness.

Focused evidence:

```text
bridge-contract/test/persistence-contract.test.ts: 3 tests passed
bridge-runtime/test/migration-runner.test.ts: 5 tests passed
bridge-runtime/test/durable-state-repositories.test.ts: 6 tests passed
bridge-runtime/test/production-composition.test.ts: 3 tests passed
bridge-runtime/test/backup-restore-drill.test.ts: 1 test passed
bridge-contract + bridge-runtime focused suites: 69 tests passed
strict TypeScript boundary check: passed
```

The production gate remains blocked until `MVP-DEP-BRIDGE` records the actual
SQLite driver, secret store, lease coordinator and deployment evidence, and a
live migration/backup/recovery command is executed in the Bridge environment.
