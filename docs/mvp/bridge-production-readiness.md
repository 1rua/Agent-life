# Bridge production readiness（2026-08-18）

## 结论

| 项目 | 状态 |
| --- | --- |
| Node/SQLite driver lock | PASS |
| SQLite transactions/migration/recovery | PASS |
| same-DB multi-connection lease fencing | PASS |
| Ed25519 local pairing verifier | PASS |
| runtime Unix socket + health/pairing smoke | PASS |
| real backup/restore/recover drill | PASS |
| Go tsnet v1.98.10 sidecar build/test | PASS |
| systemd template verification | PASS |
| Docker Compose static template check | PASS |
| Docker image config/build | BLOCKED（本机无 Docker CLI/daemon） |
| real Tailnet enrollment/traffic | BLOCKED |
| Android/Bridge physical E2E | BLOCKED |

## 已实现的生产边界

- Node `24.18.0` + built-in SQLite `3.53.1`; no third-party SQLite npm driver.
- SQLite schema v1 with closed namespaces and bigint-safe state encoding.
- `BEGIN IMMEDIATE` transactions, rollback checks, and two-connection lease
  takeover/fencing tests.
- Ed25519 signed pairing ticket envelope; only the public verifier key is
  mounted read-only.
- Node runtime binds a Unix socket and checks the authenticated tsnet peer
  fingerprint before consuming a pairing ticket.
- Go sidecar is built from `tailscale.com v1.98.10` and authenticates with
  `WhoIs` before proxying.
- No Docker host port, systemd socket activation, Funnel, or public wildcard
  listener is declared.
- Exact base-image digests are fixed in both Dockerfiles.

## Verification evidence

Executed on 2026-08-18:

```text
bridge-contract + bridge-runtime Vitest: 21 files / 127 tests PASS
bridge-runtime typecheck: PASS
bridge-runtime build: PASS
real SQLite drill: schema 1, 11/11 namespaces, digest sha256:9194163dc437e5aa9954818ca39ac65deade7f7879035bef69075010c545382f
go mod verify: all modules verified
go test ./...: PASS
systemd-analyze root template verification: PASS
static deployment checks: PASS
systemd installer disposable-root dry run: PASS
```

The production aggregate command reaches all local checks and then fails
closed:

```text
BRIDGE_PRODUCTION_VERIFY_BLOCKED Docker CLI/daemon unavailable; image config/build not executed
```

This is intentional. No image-build or physical Tailnet pass is claimed.

## Deployment template

- `bridge-runtime/deploy/Dockerfile`
- `bridge-runtime/deploy/Dockerfile.ingress`
- `bridge-runtime/deploy/docker-compose.yml`
- `bridge-runtime/deploy/agent-life-bridge.service`
- `bridge-runtime/deploy/agent-life-ingress.service`
- `bridge-runtime/deploy/verify-production.sh`
- `bridge-runtime/deploy/install-systemd.sh`

Operators must provide:

- `/var/lib/agent-life-bridge` state volume;
- read-only `pairing-ticket-public.pem`;
- tsnet state directory and one-time auth key credential;
- hostname and HTTPS control URL;
- Docker daemon or a host with the systemd users/directories provisioned.

The template intentionally does not include pairing private keys or Android
device credentials.

## Not production evidence

- `FileBackedBridgeStore` and fake connected-port tests.
- `productionClaim: "source-seam-only"` composition results.
- Empty or unexecuted Docker/systemd tasks.
- Physical P0t matrix gaps or Android smoke output.
