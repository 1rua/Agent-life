#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
STAGING_DIR=${STAGING_DIR:-$(mktemp -d /tmp/open-android-intelligence-systemd-verify.XXXXXX)}

install -d -m 0755 \
  "$STAGING_DIR/usr/bin" \
  "$STAGING_DIR/etc/systemd/system" \
  "$STAGING_DIR/opt/open-android-intelligence/bridge" \
  "$STAGING_DIR/opt/open-android-intelligence/ingress" \
  "$STAGING_DIR/etc/open-android-intelligence/bridge" \
  "$STAGING_DIR/etc/open-android-intelligence/ingress" \
  "$STAGING_DIR/var/lib/open-android-intelligence-bridge" \
  "$STAGING_DIR/var/lib/open-android-intelligence-ingress" \
  "$STAGING_DIR/run/open-android-intelligence"

install -m 0644 "$SCRIPT_DIR/open-android-intelligence-bridge.service" "$STAGING_DIR/etc/systemd/system/"
install -m 0644 "$SCRIPT_DIR/open-android-intelligence-ingress.service" "$STAGING_DIR/etc/systemd/system/"
# Supply minimal special targets so --root verification does not depend on a
# complete host OS image. These files are verifier fixtures only.
for unit in sysinit.target basic.target multi-user.target network.target network-online.target sockets.target; do
  install -m 0644 /dev/stdin "$STAGING_DIR/etc/systemd/system/$unit" <<UNIT
[Unit]
Description=Open Android Intelligence systemd verifier fixture
UNIT
done
install -m 0755 "$REPOSITORY_DIR/bridge-runtime/dist/bridge-runtime/src/main.js" "$STAGING_DIR/opt/open-android-intelligence/bridge/dist-main.js"
INGRESS_BINARY=${INGRESS_BINARY:-$REPOSITORY_DIR/bridge-runtime/ingress/open-android-intelligence-tsnet}
install -m 0755 "$INGRESS_BINARY" "$STAGING_DIR/opt/open-android-intelligence/ingress/open-android-intelligence-tsnet"
# systemd-analyze verifies ExecStart paths, while the Node runtime itself is an
# interpreted script. Put a tiny interpreter-compatible executable at /usr/bin/node.
install -m 0755 /dev/stdin "$STAGING_DIR/usr/bin/node" <<'NODE_SH'
#!/bin/sh
exec /usr/bin/env node "$@"
NODE_SH
printf 'staging\n' > "$STAGING_DIR/etc/open-android-intelligence/bridge/pairing-ticket-public.pem"
printf 'OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME=staging\nOPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL=https://staging.invalid\n' > "$STAGING_DIR/etc/open-android-intelligence/ingress/ingress.env"

systemd-analyze --root="$STAGING_DIR" verify \
  "$STAGING_DIR/etc/systemd/system/open-android-intelligence-bridge.service" \
  "$STAGING_DIR/etc/systemd/system/open-android-intelligence-ingress.service"
printf 'SYSTEMD_TEMPLATE_VERIFY_PASS root=%s\n' "$STAGING_DIR"
