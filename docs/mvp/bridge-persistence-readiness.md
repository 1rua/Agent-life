# Bridge persistence and migration readiness

Status: **source contract PASS; actual database dependency PENDING**.

The repository now exposes a closed `DurableBridgeStore` namespace set covering
pairing tickets/bindings, authorization grants/revisions, notification records
and cursors, subscription bindings/events, operation claims, and assistant
metadata. `bridge-contract/src/persistence.ts` defines the external SQLite
adapter port with atomic transactions, contiguous schema migrations,
backup/restore, and crash recovery. `bridge-runtime/src/migration-runner.ts`
validates and runs versioned migration steps and verifies that the adapter
published each target version.

This is a source-level contract and deterministic test evidence only. There is
no SQLite driver, database file, migration deployment, backup schedule,
multi-process lock, or restore drill checked into this repository. The adapter
must remain `status: "external-driver-required"` until the controller locks the
runtime/driver, immutable version or digest, deployment image and executable
backup/restore/recovery commands. A local file adapter or test fake must not be
reported as production database readiness.

Focused evidence:

```text
bridge-contract/test/persistence-contract.test.ts: 3 tests passed
bridge-runtime/test/migration-runner.test.ts: 4 tests passed
bridge-contract + bridge-runtime focused suites: 55 tests passed
strict TypeScript boundary check: passed
```

The production gate remains blocked until `MVP-DEP-BRIDGE` records the actual
SQLite driver and deployment evidence, and a live migration/backup/recovery
command is executed in the Bridge environment.
