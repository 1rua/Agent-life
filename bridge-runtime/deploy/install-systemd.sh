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
if [[ ! -f "$REPOSITORY_DIR/bridge-runtime/dist/bridge-runtime/src/main.js" || ! -x "$REPOSITORY_DIR/bridge-runtime/ingress/agent-life-tsnet" ]]; then
  echo 'SYSTEMD_INSTALL_BLOCKED build artifacts missing; run verify-production.sh first' >&2
  exit 1
fi
if [[ ! -r ${AGENT_LIFE_PAIRING_PUBLIC_KEY:-} ]]; then
  echo 'SYSTEMD_INSTALL_BLOCKED pairing public key is required' >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 0 ]]; then
  getent group agent-life >/dev/null || groupadd --system agent-life
  id -u agent-life-ingress >/dev/null 2>&1 || useradd --system --gid agent-life --home-dir /nonexistent --shell /usr/sbin/nologin agent-life-ingress
  id -u agent-life-runtime >/dev/null 2>&1 || useradd --system --gid agent-life --home-dir /nonexistent --shell /usr/sbin/nologin agent-life-runtime
fi

install -d -m 0755 \
  "$INSTALL_ROOT/opt/agent-life/bridge" \
  "$INSTALL_ROOT/opt/agent-life/ingress" \
  "$INSTALL_ROOT/etc/systemd/system" \
  "$INSTALL_ROOT/etc/agent-life/bridge" \
  "$INSTALL_ROOT/etc/agent-life/ingress"
if [[ "$DRY_RUN" -eq 0 ]]; then
  install -d -m 0750 -o agent-life-runtime -g agent-life "$INSTALL_ROOT/var/lib/agent-life-bridge"
  install -d -m 0750 -o agent-life-ingress -g agent-life "$INSTALL_ROOT/var/lib/agent-life-ingress"
  install -d -m 0750 -g agent-life "$INSTALL_ROOT/run/agent-life"
else
  install -d -m 0750 "$INSTALL_ROOT/var/lib/agent-life-bridge"
  install -d -m 0750 "$INSTALL_ROOT/var/lib/agent-life-ingress"
  install -d -m 0750 "$INSTALL_ROOT/run/agent-life"
fi

install -m 0644 "$SCRIPT_DIR/agent-life-bridge.service" "$INSTALL_ROOT/etc/systemd/system/"
install -m 0644 "$SCRIPT_DIR/agent-life-ingress.service" "$INSTALL_ROOT/etc/systemd/system/"
# Preserve the complete emitted JavaScript tree, not only the entry file.
rm -rf "$INSTALL_ROOT/opt/agent-life/bridge/dist"
cp -a "$REPOSITORY_DIR/bridge-runtime/dist" "$INSTALL_ROOT/opt/agent-life/bridge/dist"
if [[ "$DRY_RUN" -eq 0 ]]; then chown -R root:agent-life "$INSTALL_ROOT/opt/agent-life/bridge"; fi
find "$INSTALL_ROOT/opt/agent-life/bridge" -type d -exec chmod 0755 {} +
find "$INSTALL_ROOT/opt/agent-life/bridge" -type f -exec chmod 0644 {} +
install -m 0755 "$REPOSITORY_DIR/bridge-runtime/ingress/agent-life-tsnet" "$INSTALL_ROOT/opt/agent-life/ingress/agent-life-tsnet"
install -m 0444 "$AGENT_LIFE_PAIRING_PUBLIC_KEY" "$INSTALL_ROOT/etc/agent-life/bridge/pairing-ticket-public.pem"
# Auth key is optional; without one tsnet prints the interactive login URL
# through its log stream and waits for the operator to authorize the node.
if [[ -n ${AGENT_LIFE_TSNET_AUTHKEY_FILE:-} && -r ${AGENT_LIFE_TSNET_AUTHKEY_FILE:-} ]]; then
  if [[ "$DRY_RUN" -eq 0 ]]; then
    install -m 0400 -o agent-life-ingress -g agent-life "$AGENT_LIFE_TSNET_AUTHKEY_FILE" "$INSTALL_ROOT/etc/agent-life/ingress/tsnet-auth-key"
  else
    install -m 0400 "$AGENT_LIFE_TSNET_AUTHKEY_FILE" "$INSTALL_ROOT/etc/agent-life/ingress/tsnet-auth-key"
  fi
else
  install -m 0640 /dev/null "$INSTALL_ROOT/etc/agent-life/ingress/tsnet-auth-key"
fi
printf 'AGENT_LIFE_TSNET_HOSTNAME=%q\nAGENT_LIFE_TSNET_CONTROL_URL=%q\n' \
  "${AGENT_LIFE_TSNET_HOSTNAME:-agent-life-bridge}" \
  "${AGENT_LIFE_TSNET_CONTROL_URL:-https://controlplane.tailscale.com}" > "$INSTALL_ROOT/etc/agent-life/ingress/ingress.env"
if [[ "$DRY_RUN" -eq 0 ]]; then chown root:agent-life "$INSTALL_ROOT/etc/agent-life/ingress/ingress.env"; fi
chmod 0640 "$INSTALL_ROOT/etc/agent-life/ingress/ingress.env"

if [[ "$DRY_RUN" -eq 1 ]]; then
  install -d -m 0755 "$INSTALL_ROOT/usr/bin"
  install -m 0755 /dev/stdin "$INSTALL_ROOT/usr/bin/node" <<'NODE_SH'
#!/bin/sh
exec /usr/bin/env node "$@"
NODE_SH
  for unit in sysinit.target basic.target multi-user.target network.target network-online.target sockets.target; do
    install -m 0644 /dev/stdin "$INSTALL_ROOT/etc/systemd/system/$unit" <<UNIT
[Unit]
Description=Agent Life install dry-run fixture
UNIT
  done
fi
systemd-analyze --root="$INSTALL_ROOT" verify \
  "$INSTALL_ROOT/etc/systemd/system/agent-life-bridge.service" \
  "$INSTALL_ROOT/etc/systemd/system/agent-life-ingress.service"
if [[ "$DRY_RUN" -eq 0 ]]; then
  systemctl daemon-reload
  echo 'SYSTEMD_INSTALL_READY run: systemctl enable --now agent-life-ingress.service agent-life-bridge.service'
else
  echo "SYSTEMD_INSTALL_DRY_RUN_PASS root=$INSTALL_ROOT"
fi
