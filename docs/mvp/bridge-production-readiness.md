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
| real Tailnet enrollment / container run | PASS（交互式登录完成，ingress 在线） |
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
real SQLite drill: schema 1, 11/11 namespaces, digest sha256:dbde3f8b1f8f1073580eee8090077b1cc60b1ea53cad8a9c03f6ac066126d0b8
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
  manifest list `sha256:0a066e05a34d72440644ec6bb3b5140924b49bf823b8b55f327266764e24bd95`
- `docker.io/library/deploy-ingress:latest`
  manifest list `sha256:fdfe81c1211e19652260b6180b0b2044fb53d00c4487caaf18a9bd37d165dc5c`

The physical Tailnet and Android/Bridge E2E blockers remain; no such pass is
claimed from image build success alone.

## Deployment template

- `bridge-runtime/deploy/Dockerfile`
- `bridge-runtime/deploy/Dockerfile.ingress`
- `bridge-runtime/deploy/docker-compose.yml`
- `bridge-runtime/deploy/open-android-intelligence-bridge.service`
- `bridge-runtime/deploy/open-android-intelligence-ingress.service`
- `bridge-runtime/deploy/verify-production.sh`
- `bridge-runtime/deploy/install-systemd.sh`

Operators must provide:

- `/var/lib/open-android-intelligence-bridge` state volume;
- read-only `pairing-ticket-public.pem`;
- tsnet state directory（auth key 可选，不提供则通过日志里的官方登录网址交互式登录）;
- hostname（默认 `open-android-intelligence-bridge`）和 HTTPS control URL（默认官方 `https://controlplane.tailscale.com`）；
- a running Docker daemon or a host with the systemd users/directories provisioned.

The template intentionally does not include pairing private keys or Android
device credentials.

## Not production evidence

- `FileBackedBridgeStore` and fake connected-port tests.
- `productionClaim: "source-seam-only"` composition results.
- Empty or unexecuted Docker/systemd tasks.
- Physical P0t matrix gaps or Android smoke output.
