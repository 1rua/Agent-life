# Bridge persistence and migration readiness

Status: **single-host Node SQLite production stack PASS; Docker image build PASS; physical Tailnet/E2E BLOCKED**.

`bridge-contract/src/persistence.ts` now locks the adapter driver to
`node:sqlite@24.18.0/sqlite@3.53.1`. `bridge-runtime/src/node-sqlite-adapter.ts`
implements atomic `BEGIN IMMEDIATE` transactions, closed namespace/value
validation, schema v1, monotonic same-database leases, SHA-256 backup,
isolated restore, and SQLite quick/foreign-key recovery checks.

`DurableBridgeStateRepositories` continues to persist pairing, notification
cursor, subscription, operation claim and replay association transitions through
that transaction boundary. Reopen validation still rejects malformed or
inconsistent cross-namespace state. `DurableOperationDispatcher` continues to
commit replay association and operation state before external action.

`LocalPairingTicketVerifier` verifies an Ed25519 signed closed envelope from a
read-only local public-key file. It rejects unsafe paths/permissions and
non-canonical payloads. Ticket consumption and pairing fencing remain durable
database transitions rather than verifier-local state.

The real drill in `src/real-backup-restore-drill.ts` creates two isolated
SQLite databases, writes all 11 closed namespaces, performs a real backup and
restore, executes recovery, compares canonical content, and removes temporary
artifacts. Evidence:
[2026-08-18 Node SQLite drill](evidence/bridge/2026-08-18-node-sqlite.json),
digest `sha256:dbde3f8b1f8f1073580eee8090077b1cc60b1ea53cad8a9c03f6ac066126d0b8`.

Focused current evidence:

```text
bridge-contract + bridge-runtime: 21 test files / 127 tests passed
bridge-runtime strict TypeScript: passed
Go ingress module verify + test: passed
systemd template verification: passed
static deployment template verification: passed
real SQLite backup/restore/recover drill: passed
```

Docker Compose config/build has now run successfully and produced
`deploy-bridge` and `deploy-ingress` images (evidence
`evidence/bridge/2026-08-18-docker-build.json`). Remaining blockers are the Go
sidecar joining a real Tailnet and physical Android/Bridge E2E. A file-backed
development store, fake adapter, or source-only ingress seam must not be
reported as this production stack.
