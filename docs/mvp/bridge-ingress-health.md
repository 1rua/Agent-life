# Bridge ingress and health deployment

Status: **authenticated tsnet sidecar source/build/image PASS; 交互式登录与容器运行 PASS；Android/Bridge 物理 E2E BLOCKED**.

The production ingress is now a Go sidecar under `bridge-runtime/ingress/`. It
imports locked `tailscale.com v1.98.10`, starts an embedded userspace tsnet
node (hostname 默认 `open-android-intelligence-bridge`，control 默认官方
`https://controlplane.tailscale.com`，auth key 可选、不提供则打印官方登录网址
交互式登录), listens only on its private tailnet TLS port, authenticates every request
with LocalClient `WhoIs`, hashes the peer node key, and proxies to Node over a
Unix socket. It removes inbound forwarding headers and overwrites the peer
fingerprint header only after successful `WhoIs`.

The Node runtime listens only on `/run/open-android-intelligence/runtime.sock`. Health routes
are unauthenticated and expose only liveness/readiness; control routes require
a single valid sidecar fingerprint. Pairing ticket signature verification and
peer-binding comparison happen before the ticket is consumed in SQLite.

The old `src/ingress.ts` process-local replay/generation classes remain
development seams. They are not used by `src/main.ts` as proof of durable
connection generation or replay admission. Durable operation replay association
remains a separate transaction boundary; full production control protocol and
Task 9 routing APIs are not claimed by this slice.

Docker and systemd templates publish no host socket. Compose config/build has
been executed and produced the locked base-image `deploy-ingress` image
(evidence `evidence/bridge/2026-08-18-docker-build.json`). The sidecar has now connected to the official control server with an
interactive login (hostname `open-android-intelligence-bridge`), and the Docker deployment
states are `deploy-bridge: Up (healthy)` / `deploy-ingress: Up`. DIRECT/DERP
and offline device matrices remain P0t/E2E gates.
