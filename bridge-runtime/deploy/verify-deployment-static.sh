#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
for file in Dockerfile Dockerfile.ingress docker-compose.yml agent-life-bridge.service agent-life-ingress.service; do
  test -f "$SCRIPT_DIR/$file" || { echo "DEPLOYMENT_TEMPLATE_MISSING $file" >&2; exit 1; }
done

require() {
  local needle=$1
  grep -Fq "$needle" "$2" || { echo "DEPLOYMENT_TEMPLATE_ASSERT_FAILED $2 $needle" >&2; exit 1; }
}
forbid() {
  local pattern=$1
  shift
  local file
  for file in "$@"; do
    if grep -Eq "$pattern" "$file"; then
      echo "DEPLOYMENT_TEMPLATE_FORBIDDEN $file pattern=$pattern" >&2
      exit 1
    fi
  done
}

require 'node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d' "$SCRIPT_DIR/Dockerfile"
require 'golang:1.26.5-bookworm@sha256:53eeac89074db483fdf0ab3be1df32bf6e47562263d2d0d6baa7f26acb4957dd' "$SCRIPT_DIR/Dockerfile.ingress"
require 'tailscale.com v1.98.10' "$SCRIPT_DIR/../ingress/go.mod"
require 'CGO_ENABLED=0' "$SCRIPT_DIR/Dockerfile.ingress"
require 'network_mode: "service:ingress"' "$SCRIPT_DIR/docker-compose.yml"
require ':ro' "$SCRIPT_DIR/docker-compose.yml"
forbid '^[[:space:]]*ports:' "$SCRIPT_DIR/docker-compose.yml"
forbid 'Listen(Stream|Datagram)=' "$SCRIPT_DIR/agent-life-bridge.service" "$SCRIPT_DIR/agent-life-ingress.service"
forbid '(^|[[:space:]])(0\.0\.0\.0|::/0)|network_mode:[[:space:]]*(")?host' "$SCRIPT_DIR/docker-compose.yml" "$SCRIPT_DIR/agent-life-bridge.service" "$SCRIPT_DIR/agent-life-ingress.service"
forbid 'REPLACE_WITH_LOCKED_DIGEST' "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR/Dockerfile.ingress" "$SCRIPT_DIR/docker-compose.yml"
forbid '^/(\.toolchains|node_modules|\.worktrees)' $SCRIPT_DIR/../../.dockerignore

echo 'DEPLOYMENT_TEMPLATE_STATIC_PASS'
