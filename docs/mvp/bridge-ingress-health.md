# Bridge ingress and health deployment seam

Status: source-level seam only; `MVP-DEP-TSNET` and `MVP-DEP-BRIDGE` remain
pending until the controller records immutable versions, checksums, license
review and executable verification commands.

`bridge-runtime/src/ingress.ts` exposes a narrow userspace-bound listener port.
It deliberately has no host, URL, route, proxy or generic dial/listen argument.
When the tsnet dependency state is `pending`, `BridgeIngress.start()` returns
`status=pending` with `MVP-DEP-TSNET_PENDING` and never calls the listener. A
locked deployment must inject a reviewed userspace adapter; the source package
does not claim to open a real Tailscale socket.

Before dispatching a control frame, the seam verifies the paired Bridge
fingerprint, pairing generation, current connection generation and replay key.
The in-memory replay and generation implementations are deterministic test
adapters. A production implementation must delegate signature, durable replay
and key rotation decisions to the accepted P0a ports and persist the admission
atomically with the operation claim.

`bridge-runtime/src/health.ts` supplies framework-neutral `GET` routes:

- `/health/live` reports process liveness only;
- `/health/ready` returns `503` until every registered dependency check passes.

The systemd and Compose templates under `bridge-runtime/deploy/` keep the
listener private: there is no host-port mapping or systemd `ListenStream`, and
both templates require an explicit tsnet lock. The Compose image digest is a
placeholder until the controller locks it. Physical Tailscale, database,
migration, backup/restore and Android end-to-end evidence are still release
gates.
