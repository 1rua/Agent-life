# MVP smoke evidence

Status: SDK-free contract smoke **PASS**; production/device gate **PENDING**.

## Reproducible command

```text
e2e/mvp/run-smoke.sh --sdk-free
```

The current run currently exercises 16 Vitest files / 98 tests and 48 Android host-static
tests. It also runs the strict TypeScript boundary check for Bridge and
Hermes/OpenClaw adapters. The smoke output ends with `SDK_FREE_PASS`.

The companion protocol worktree currently reports **32 test files / 310 tests
and typecheck GREEN**. This is reference-contract evidence only. Task 9 still
blocks release because fixed cross-language vectors, production cursor/ACK
durability and shared pre-replay integration are pending.

The Vitest set includes the M1.1 artifact-ticket contract: selected-media
limits, digest/PoP binding, interrupted-upload replacement, commit-gated local
deletion and orphan reclamation. It does not upload bytes or substitute for
the locked object-store/scanner and physical Android flow.

The Android static gate also checks the notification listener runtime and the
assistant-holder attachment hand-off. The listener creates/stops a scoped
runtime, and only a pre-injected `NotificationOutbox` receives policy-accepted
auto-send records; the default factory has no outbox and denies authorization.
For the assistant-holder, explicit Photo Picker/SAF plus read-grant input is
required, synthetic grants are rejected, and an absent grant issuer emits no
attachment selection. The shared text/opaque-grant handoff is bounded to four
attachments/50,000 text characters, and the main APK's default gate denies
until a local user setting enables it.

For the packet-level audit, run `e2e/mvp/run-readiness.sh --sdk-free`. It
repeats this smoke and reports concrete WP-00..WP-10 source artifacts plus
each production blocker; the final `SDK_FREE_GATE_PASS` is not a release pass.

`e2e/mvp/run-smoke.sh --release` is a separate release gate. It requires all
dependency rows, an `adb`-connected reference device and the locked Gradle
toolchain; it exits non-zero immediately when any prerequisite is absent.

## Deliberate fail-closed gates

The same run reports `LOCK_GATE_PENDING`: all seven controller dependency rows
in `docs/mvp/mvp-dependency-lock.md` are explicitly pending, so real Android,
userspace Tailnet, Bridge runtime, provider and artifact work cannot be called
production-ready. It reports `ANDROID_QA_SKIPPED` because this environment has
no `adb`, Java, Gradle, Kotlin compiler or physical/emulated device.

This document is content-free and does not claim APK installation, a Tailscale
AAR, a durable database, or a Hermes/OpenClaw production gateway. The
The Bridge package now exposes a database/network-neutral `DurableBridgeStore`
transaction port, a versioned SQLite migration/backup/restore port, and a
userspace-ingress plus health/readiness seam. The local operation-claim
dispatcher remains wired through an explicit composition root. These are
source/test contracts only: pairing, notification and subscription stores
remain process-local, no SQLite driver or authenticated tsnet adapter is
locked, and the local adapter is not evidence of production persistence. Those
claims require filling the lock rows, wiring crash-recoverable adapters and
rerunning the physical gate described in `docs/mvp/p0t-mvp-evidence.md`.
