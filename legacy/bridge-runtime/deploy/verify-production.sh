#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
NODE_LAUNCHER="$ROOT_DIR/tools/run-node24"
GO_BIN=${GO_BIN:-$(command -v go || true)}

if [[ ! -x "$NODE_LAUNCHER" ]]; then
  echo 'BRIDGE_PRODUCTION_VERIFY_BLOCKED fixed Node 24 launcher missing' >&2
  exit 1
fi
if [[ -z "$GO_BIN" ]]; then
  echo 'BRIDGE_PRODUCTION_VERIFY_BLOCKED Go toolchain missing' >&2
  exit 1
fi

"$NODE_LAUNCHER" node -e 'if(process.versions.node!=="24.18.0"||process.versions.sqlite!=="3.53.1")process.exit(1)'
"$NODE_LAUNCHER" npx --no-install vitest --root "$ROOT_DIR" run bridge-contract/test bridge-runtime/test
"$NODE_LAUNCHER" npm --prefix "$ROOT_DIR/bridge-runtime" run typecheck
"$NODE_LAUNCHER" npm --prefix "$ROOT_DIR/bridge-runtime" run build

EVIDENCE_PATH=${BRIDGE_DRILL_EVIDENCE:-$ROOT_DIR/docs/mvp/evidence/bridge/latest-production-drill.json}
"$NODE_LAUNCHER" npm --prefix "$ROOT_DIR/bridge-runtime" run drill -- --output "$EVIDENCE_PATH"

(
  cd "$ROOT_DIR/bridge-runtime/ingress"
  export GOCACHE=${GOCACHE:-/tmp/open-android-intelligence-go-build-cache}
  mkdir -p "$GOCACHE"
  GOPROXY=${GOPROXY:-off} "$GO_BIN" mod verify
  GOPROXY=${GOPROXY:-off} "$GO_BIN" test ./...
  INGRESS_BINARY=$(mktemp /tmp/open-android-intelligence-tsnet-verify.XXXXXX)
  trap 'rm -f "$INGRESS_BINARY"' EXIT
  CGO_ENABLED=0 GOPROXY=${GOPROXY:-off} "$GO_BIN" build -trimpath -ldflags='-s -w' -o "$INGRESS_BINARY" ./cmd/open-android-intelligence-tsnet
  INGRESS_BINARY="$INGRESS_BINARY" "$ROOT_DIR/bridge-runtime/deploy/verify-systemd.sh"
)

"$ROOT_DIR/bridge-runtime/deploy/verify-deployment-static.sh"

# Exercise the installer itself against a disposable root without mutating host
# users, credentials, units, or service state.
DRY_ROOT=$(mktemp -d /tmp/open-android-intelligence-install-verify.XXXXXX)
DRY_INPUT=$(mktemp -d /tmp/open-android-intelligence-install-input.XXXXXX)
trap 'rm -rf "$DRY_ROOT" "$DRY_INPUT"' EXIT
printf '%s\n' verify-public-key > "$DRY_INPUT/public.pem"
INSTALL_ROOT="$DRY_ROOT" \
  OPEN_ANDROID_INTELLIGENCE_PAIRING_PUBLIC_KEY="$DRY_INPUT/public.pem" \
  "$ROOT_DIR/bridge-runtime/deploy/install-systemd.sh"

export DOCKER_TEST_HOSTNAME=${OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME:-open-android-intelligence-verify}
export DOCKER_TEST_CONTROL_URL=${OPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL:-https://control.invalid}

if ! command -v docker >/dev/null 2>&1; then
  echo 'BRIDGE_PRODUCTION_VERIFY_BLOCKED Docker CLI/daemon unavailable; image config/build not executed' >&2
  exit 1
fi
OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME="$DOCKER_TEST_HOSTNAME" \
  OPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL="$DOCKER_TEST_CONTROL_URL" \
  docker compose -f "$ROOT_DIR/bridge-runtime/deploy/docker-compose.yml" config --quiet
OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME="$DOCKER_TEST_HOSTNAME" \
  OPEN_ANDROID_INTELLIGENCE_TSNET_CONTROL_URL="$DOCKER_TEST_CONTROL_URL" \
  docker compose -f "$ROOT_DIR/bridge-runtime/deploy/docker-compose.yml" build --pull
echo 'BRIDGE_PRODUCTION_VERIFY_PASS'
