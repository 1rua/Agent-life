#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
INSTALL_ROOT=${INSTALL_ROOT:-/}

DRY_RUN=0
if [[ ${INSTALL_ROOT:-/} != / ]]; then
  DRY_RUN=1
elif [[ "$(id -u)" -ne 0 ]]; then
  echo 'SYSTEMD_INSTALL_BLOCKED root privileges are required' >&2
  exit 1
fi
if [[ ! -f "$REPOSITORY_DIR/bridge-runtime/dist/bridge-runtime/src/main.js" || ! -x "$REPOSITORY_DIR/bridge-runtime/ingress/open-android-intelligence-tsnet" ]]; then
  echo 'SYSTEMD_INSTALL_BLOCKED build artifacts missing; run verify-production.sh first' >&2
  exit 1
fi
if [[ ! -r ${OPEN_ANDROID_INTELLIGENCE_PAIRING_PUBLIC_KEY:-} ]]; then
  echo 'SYSTEMD_INSTALL_BLOCKED pairing public key is required' >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 0 ]]; then
  getent group open-android-intelligence >/dev/null || groupadd --system open-android-intelligence
  id -u open-android-intelligence-ingress >/dev/null 2>&1 || useradd --system --gid open-android-intelligence --home-dir /nonexistent --shell /usr/sbin/nologin open-android-intelligence-ingress
  id -u open-android-intelligence-runtime >/dev/null 2>&1 || useradd --system --gid open-android-intelligence --home-dir /nonexistent --shell /usr/sbin/nologin open-android-intelligence-runtime
fi

install -d -m 0755 \
  "$INSTALL_ROOT/opt/open-android-intelligence/bridge" \
  "$INSTALL_ROOT/opt/open-android-intelligence/ingress" \
  "$INSTALL_ROOT/etc/systemd/system" \
  "$INSTALL_ROOT/etc/open-android-intelligence/bridge" \
  "$INSTALL_ROOT/etc/open-android-intelligence/ingress"
if [[ "$DRY_RUN" -eq 0 ]]; then
  install -d -m 0750 -o open-android-intelligence-runtime -g open-android-intelligence "$INSTALL_ROOT/var/lib/open-android-intelligence-bridge"
  install -d -m 0750 -o open-android-intelligence-ingress -g open-android-intelligence "$INSTALL_ROOT/var/lib/open-android-intelligence-ingress"
  install -d -m 0750 -g open-android-intelligence "$INSTALL_ROOT/run/open-android-intelligence"
else
  install -d -m 0750 "$INSTALL_ROOT/var/lib/open-android-intelligence-bridge"
  install -d -m 0750 "$INSTALL_ROOT/var/lib/open-android-intelligence-ingress"
  install -d -m 0750 "$INSTALL_ROOT/run/open-android-intelligence"
fi

install -m 0644 "$SCRIPT_DIR/open-android-intelligence-bridge.service" "$INSTALL_ROOT/etc/systemd/system/"
install -m 0644 "$SCRIPT_DIR/open-android-intelligence-ingress.service" "$INSTALL_ROOT/etc/systemd/system/"
# Preserve the complete emitted JavaScript tree, not only the entry file.
rm -rf "$INSTALL_ROOT/opt/open-android-intelligence/bridge/dist"
cp -a "$REPOSITORY_DIR/bridge-runtime/dist" "$INSTALL_ROOT/opt/open-android-intelligence/bridge/dist"
if [[ "$DRY_RUN" -eq 0 ]]; then chown -R root:open-android-intelligence "$INSTALL_ROOT/opt/open-android-intelligence/bridge"; fi
find "$INSTALL_ROOT/opt/open-android-intelligence/bridge" -type d -exec chmod 0755 {} +
find "$INSTALL_ROOT/opt/open-android-intelligence/bridge" -type f -exec chmod 0644 {} +
install -m 0755 "$REPOSITORY_DIR/bridge-runtime/ingress/open-android-intelligence-tsnet" "$INSTALL_ROOT/opt/open-android-intelligence/ingress/open-android-intelligence-tsnet"
install -m 0444 "$OPEN_ANDROID_INTELLIGENCE_PAIRING_PUBLIC_KEY" "$INSTALL_ROOT/etc/open-android-intelligence/bridge/pairing-ticket-public.pem"
# Auth key is optional; without one tsnet prints the interactive login URL
# through its log stream and waits for the operator to authorize the node.
if [[ -n ${OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE:-} && -r ${OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE:-} ]]; then
  if [[ "$DRY_RUN" -eq 0 ]]; then
    install -m 0400 -o open-android-intelligence-ingress -g open-android-intelligence "$OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE" "$INSTALL_ROOT/etc/open-android-intelligence/ingress/tsnet-auth-key"
  else
    install -m 0400 "$OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE" "$INSTALL_ROOT/etc/open-android-intelligence/ingress/tsnet-auth-key"
  fi
else
  install -m 0640 /dev/null "$INSTALL_ROOT/etc/open-android-intelligence/ingress/tsnet-auth-key"
fi
printf 'OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME=%q\nOPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL=%q\n' \
  "${OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME:-open-android-intelligence-bridge}" \
  "${OPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL:-https://controlplane.tailscale.com}" > "$INSTALL_ROOT/etc/open-android-intelligence/ingress/ingress.env"
if [[ "$DRY_RUN" -eq 0 ]]; then chown root:open-android-intelligence "$INSTALL_ROOT/etc/open-android-intelligence/ingress/ingress.env"; fi
chmod 0640 "$INSTALL_ROOT/etc/open-android-intelligence/ingress/ingress.env"

if [[ "$DRY_RUN" -eq 1 ]]; then
  install -d -m 0755 "$INSTALL_ROOT/usr/bin"
  install -m 0755 /dev/stdin "$INSTALL_ROOT/usr/bin/node" <<'NODE_SH'
#!/bin/sh
exec /usr/bin/env node "$@"
NODE_SH
  for unit in sysinit.target basic.target multi-user.target network.target network-online.target sockets.target; do
    install -m 0644 /dev/stdin "$INSTALL_ROOT/etc/systemd/system/$unit" <<UNIT
[Unit]
Description=Open Android Intelligence install dry-run fixture
UNIT
  done
fi
systemd-analyze --root="$INSTALL_ROOT" verify \
  "$INSTALL_ROOT/etc/systemd/system/open-android-intelligence-bridge.service" \
  "$INSTALL_ROOT/etc/systemd/system/open-android-intelligence-ingress.service"
if [[ "$DRY_RUN" -eq 0 ]]; then
  systemctl daemon-reload
  echo 'SYSTEMD_INSTALL_READY run: systemctl enable --now open-android-intelligence-ingress.service open-android-intelligence-bridge.service'
else
  echo "SYSTEMD_INSTALL_DRY_RUN_PASS root=$INSTALL_ROOT"
fi
