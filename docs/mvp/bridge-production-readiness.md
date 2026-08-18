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
| Docker image config/build | PASS（deploy-bridge + deploy-ingress） |
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
real SQLite drill: schema 1, 11/11 namespaces, digest sha256:5f4ddabcb1903efecc8ec265309c77b53eeaa928403d4180c03d6fa4267a610c
go mod verify: all modules verified
go test ./...: PASS
systemd-analyze root template verification: PASS
static deployment checks: PASS
systemd installer disposable-root dry run: PASS
```

The production aggregate command now passes every local check, Compose
config, and both `docker compose build --pull` images:

```text
BRIDGE_PRODUCTION_VERIFY_PASS
```

Image evidence: `docs/mvp/evidence/bridge/2026-08-18-docker-build.json`
- `docker.io/library/deploy-bridge:latest`
  manifest list `sha256:d6a1c184bd54fc5e83c8154655c8936a910382247c01a995857d09e905b393c8`
- `docker.io/library/deploy-ingress:latest`
  manifest list `sha256:f0d5424fbe887bc15037ada61f5ee29bc95d041b270962d6fdf34027201c39c0`

The physical Tailnet and Android/Bridge E2E blockers remain; no such pass is
claimed from image build success alone.

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
- a running Docker daemon or a host with the systemd users/directories provisioned.

The template intentionally does not include pairing private keys or Android
device credentials.

## Not production evidence

- `FileBackedBridgeStore` and fake connected-port tests.
- `productionClaim: "source-seam-only"` composition results.
- Empty or unexecuted Docker/systemd tasks.
- Physical P0t matrix gaps or Android smoke output.
