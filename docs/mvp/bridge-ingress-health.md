# Bridge ingress and health deployment

Status: **authenticated tsnet sidecar source/build/test PASS; physical Tailnet enrollment and Docker image build BLOCKED**.

The production ingress is now a Go sidecar under `bridge-runtime/ingress/`. It
imports locked `tailscale.com v1.98.10`, starts an embedded userspace tsnet
node, listens only on its private tailnet TLS port, authenticates every request
with LocalClient `WhoIs`, hashes the peer node key, and proxies to Node over a
Unix socket. It removes inbound forwarding headers and overwrites the peer
fingerprint header only after successful `WhoIs`.

The Node runtime listens only on `/run/agent-life/runtime.sock`. Health routes
are unauthenticated and expose only liveness/readiness; control routes require
a single valid sidecar fingerprint. Pairing ticket signature verification and
peer-binding comparison happen before the ticket is consumed in SQLite.

The old `src/ingress.ts` process-local replay/generation classes remain
development seams. They are not used by `src/main.ts` as proof of durable
connection generation or replay admission. Durable operation replay association
remains a separate transaction boundary; full production control protocol and
Task 9 routing APIs are not claimed by this slice.

Docker and systemd templates publish no host socket. The current machine has
no Docker CLI/daemon, so Compose config/build is an explicit blocker rather
than a pass. The sidecar also has not connected to a real control server or
completed DIRECT/DERP/offline device evidence; those remain P0t/E2E gates.
