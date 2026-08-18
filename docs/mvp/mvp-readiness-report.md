# MVP readiness report

This report separates the deterministic source/contract slice from the
production gate. A source artifact marked `PASS` means that the handoff file
and its host/static test seam are present; it does **not** mean that an APK,
userspace Tailnet AAR, durable Bridge or physical device run exists.

## Reproducible commands

Run from the repository root:

```bash
e2e/mvp/run-readiness.sh --sdk-free
e2e/mvp/run-readiness.sh --release
```

`--sdk-free` runs the current contract/integration/runtime/artifact smoke (16 Vitest files / 98
tests), the Android host-static gate (48 tests), strict TypeScript boundary
checking, and the WP-00..WP-10 artifact audit. It exits zero only when those
deterministic checks pass. It reports pending production blockers and ends
with `SDK_FREE_GATE_PASS (production gate remains separate)`.

`--release` runs the same audit in fail-closed mode. It additionally requires
all seven dependency-lock rows, an ADB-connected API-34+ reference device, the
locked Java/Gradle toolchain, the real Bridge runtime and the Tailscale AAR,
plus accepted P0t and reviewed protocol gates. The accepted product choices
are recorded in [p0a-gate-decisions.md](p0a-gate-decisions.md); Task 9's
bounded pre-replay authority gate is reviewed, while vectors, durable
cursor/ACK storage and deployed routing remain separate blockers. Any missing prerequisite emits
`RELEASE_READINESS_BLOCKED` and `RELEASE_GATE_BLOCKED` with a reason; no fake
or SDK-free result is promoted to a release pass.

## WP-00..WP-10 audit

| Packet | SDK-free source artifact | Production gate | Current interpretation |
| --- | --- | --- | --- |
| WP-00 | PASS | PENDING | Lock document, validator and test exist; all seven rows are still pending. |
| WP-01 | PASS | PENDING | Closed schemas, wire codec and fixtures exist. |
| WP-02 | PASS | PENDING | Two-module/no-VPN source gate exists; locked Android build has not run. |
| WP-03 | PASS | PENDING | Policy/collector/outbox plus lifecycle/runtime outbox composition, closed capability/control-port and typed provider-contract sources and host/static seams exist; Android providers/tests need the toolchain/device. |
| WP-04 | PASS | PASS (SDK-free) | Fake paired transport and cross-layer trace tests run in the host smoke. |
| WP-05 | PASS | PENDING | Userspace transport seam exists; no locked AAR or physical P0t evidence. |
| WP-06 | PASS | PARTIAL PASS | The locked single-host Node SQLite/lease/Ed25519/tsnet runtime has 21 focused files (127 tests), a real 11-namespace backup/restore drill, Go sidecar build/test, and verified systemd/static deployment templates. Docker image build and physical Tailnet/E2E remain blocked. |
| WP-07 | PASS | PENDING | Hermes/OpenClaw adapter/manifest/skill seams exist; official release/profile locks are pending. |
| WP-08 | PASS | PENDING | Isolated holder source plus shared bounded text/opaque-grant hand-off and a default-deny main-APK gate exist; Android/model lock and connected tests are pending. |
| WP-09 | PASS | PENDING | SDK-free harness exists; physical Android/Bridge/plugin E2E cannot run yet. |
| WP-10 | PASS | PENDING | Source-only artifact ticket/PoP/commit contract plus Android granted-selection/encrypted-copy/delete seam exists; picker/SAF, crypto, object store, scanner and physical interruption evidence remain pending. |

The machine-readable source list and blocker logic live in
`mvp-contract/tools/mvp-readiness.ts`; the shell wrapper intentionally keeps
test execution and the release gate as separate observable steps.

## Protocol gate snapshot

The companion protocol worktree currently reports **32 test files / 334 tests
and typecheck GREEN**. This is a deterministic reference-contract gate, not
production evidence. Task 9's bounded pre-replay authority gate is GREEN with
the full mismatch, precedence and allow-path matrix; fixed cross-language
vectors, production cursor/ACK durability and deployed routing remain pending.

## Synchronized P0a decisions

The main-checkout record in [p0a-gate-decisions.md](p0a-gate-decisions.md)
is the readiness audit's source for the user's confirmed product choices:

- Task 7: permanent tombstones/bounded polling (D1-A), exact logical quota
  `9,663,676,416` bytes (D2), ACK retention `max(operation_expiry,
  bridge_ack_at + 30 days)` (D3), and terminal `result_unknown` without
  automatic retry (D4).
- Task 9: `device_event` lifetime `24h`, `event_ack` lifetime `5min`, and
  replay policy `task5_default`.

These values remove the stale “owner decision pending” interpretation. They do
not remove the Task 9 technical preflight blocker or any dependency, device,
P0t, AAR, or production-Bridge requirement.

## Current blockers

- `npm run mvp:lock:check` fails closed because all seven controller rows are
  `pending`.
- Local JDK 17, Android SDK platform/build-tools and `adb` are present, but the
  locked Gradle 8.9 distribution was not downloaded, the NDK install is only a
  stub, and no reference device is connected; `apps/android/gradlew --no-daemon
  check` therefore cannot establish a build or P0t result.
- The single-host Bridge storage/runtime stack is implemented and locally
  verified: Node SQLite, same-DB lease fencing, Ed25519 verifier, Unix-socket
  runtime, Go tsnet sidecar build, systemd template verification, and a real
  backup/restore/recover drill. The deployment image is not yet built because
  this host has no Docker CLI/daemon, and the sidecar has not joined a real
  Tailnet or completed physical Android/Bridge E2E.
- The Android capability/control ports are source-only contracts. SMS, calls,
  contacts, clipboard, location, Health Connect, sensors, calendar, alarms,
  Accessibility and MediaProjection adapters still require their own
  permission/device implementation packets; no source-only port is advertised
  as a working provider.
- P0t evidence remains source-level/SKIPPED.
- Task 7 D1–D4 are accepted as product/security choices, and Task 9's
  `device_event`/`event_ack` lifetimes (`24h`/`5min`) plus `task5_default`
  replay policy are accepted. Task 9's reference contract is covered by the
  32-file/334-test protocol snapshot, but fixed vectors, production
  cursor/ACK durability and deployed routing remain pending and release-
  blocking.

These are explicit fail-closed states, not test failures hidden by the
SDK-free report. The current implementation is therefore suitable for
contract/static iteration only and is not production-ready.

## WP-06 production-stack evidence

`bridge-runtime/` retains the deterministic local `fs/promises` development
adapter, but production no longer relies on it. The current locked stack uses
Node `24.18.0` built-in SQLite `3.53.1`, same-database lease fencing, a local
read-only Ed25519 pairing verifier, a Unix-socket Node runtime, and a Go
tsnet `v1.98.10` sidecar.

Current evidence:

```text
bridge-contract + bridge-runtime: 21 files / 127 tests passed
bridge-runtime strict TypeScript/build: passed
real SQLite backup/restore/recover: schema 1, 11/11 namespaces,
  sha256:9194163dc437e5aa9954818ca39ac65deade7f7879035bef69075010c545382f
Go ingress module verify/test/build: passed
systemd-analyze template verification: passed
static Docker/Compose checks: passed
```

The aggregate `./bridge-runtime/deploy/verify-production.sh` fails closed on
this host with `BRIDGE_PRODUCTION_VERIFY_BLOCKED Docker CLI/daemon unavailable`.
No Docker image build, physical Tailnet enrollment, or Android E2E is claimed.
The file-backed adapter and fake port tests remain development evidence only.
