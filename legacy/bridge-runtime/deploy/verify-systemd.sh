#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
STAGING_DIR=${STAGING_DIR:-$(mktemp -d /tmp/agent-life-systemd-verify.XXXXXX)}

install -d -m 0755 \
  "$STAGING_DIR/usr/bin" \
  "$STAGING_DIR/etc/systemd/system" \
  "$STAGING_DIR/opt/agent-life/bridge" \
  "$STAGING_DIR/opt/agent-life/ingress" \
  "$STAGING_DIR/etc/agent-life/bridge" \
  "$STAGING_DIR/etc/agent-life/ingress" \
  "$STAGING_DIR/var/lib/agent-life-bridge" \
  "$STAGING_DIR/var/lib/agent-life-ingress" \
  "$STAGING_DIR/run/agent-life"

install -m 0644 "$SCRIPT_DIR/agent-life-bridge.service" "$STAGING_DIR/etc/systemd/system/"
install -m 0644 "$SCRIPT_DIR/agent-life-ingress.service" "$STAGING_DIR/etc/systemd/system/"
# Supply minimal special targets so --root verification does not depend on a
# complete host OS image. These files are verifier fixtures only.
for unit in sysinit.target basic.target multi-user.target network.target network-online.target sockets.target; do
  install -m 0644 /dev/stdin "$STAGING_DIR/etc/systemd/system/$unit" <<UNIT
[Unit]
Description=Agent Life systemd verifier fixture
UNIT
done
install -m 0755 "$REPOSITORY_DIR/bridge-runtime/dist/bridge-runtime/src/main.js" "$STAGING_DIR/opt/agent-life/bridge/dist-main.js"
INGRESS_BINARY=${INGRESS_BINARY:-$REPOSITORY_DIR/bridge-runtime/ingress/agent-life-tsnet}
install -m 0755 "$INGRESS_BINARY" "$STAGING_DIR/opt/agent-life/ingress/agent-life-tsnet"
# systemd-analyze verifies ExecStart paths, while the Node runtime itself is an
# interpreted script. Put a tiny interpreter-compatible executable at /usr/bin/node.
install -m 0755 /dev/stdin "$STAGING_DIR/usr/bin/node" <<'NODE_SH'
#!/bin/sh
exec /usr/bin/env node "$@"
NODE_SH
printf 'staging\n' > "$STAGING_DIR/etc/agent-life/bridge/pairing-ticket-public.pem"
printf 'AGENT_LIFE_TSNET_HOSTNAME=staging\nAGENT_LIFE_TSNET_CONTROL_URL=https://staging.invalid\n' > "$STAGING_DIR/etc/agent-life/ingress/ingress.env"

systemd-analyze --root="$STAGING_DIR" verify \
  "$STAGING_DIR/etc/systemd/system/agent-life-bridge.service" \
  "$STAGING_DIR/etc/systemd/system/agent-life-ingress.service"
printf 'SYSTEMD_TEMPLATE_VERIFY_PASS root=%s\n' "$STAGING_DIR"
