#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${1:-}"
if [[ "$MODE" != "--sdk-free" && "$MODE" != "--release" ]]; then
  echo "usage: $0 --sdk-free|--release" >&2
  exit 2
fi

# The smoke command is the executable test gate. The readiness tool then
# audits WP-00..WP-10 artifacts and reports external release blockers. A
# pending lock/device is expected in SDK-free mode, never converted to PASS.
SMOKE_STATUS=0
"$ROOT_DIR/e2e/mvp/run-smoke.sh" "$MODE" || SMOKE_STATUS=$?

READINESS_STATUS=0
node --experimental-strip-types \
  "$ROOT_DIR/mvp-contract/tools/mvp-readiness.ts" "$MODE" || READINESS_STATUS=$?

if [[ "$SMOKE_STATUS" -ne 0 || "$READINESS_STATUS" -ne 0 ]]; then
  if [[ "$MODE" == "--release" ]]; then
    echo "RELEASE_GATE_BLOCKED: readiness report or executable gate failed"
  else
    echo "SDK_FREE_GATE_BLOCKED: contract/static smoke or artifact audit failed"
  fi
  exit 1
fi

if [[ "$MODE" == "--release" ]]; then
  echo "RELEASE_GATE_PASS"
else
  echo "SDK_FREE_GATE_PASS (production gate remains separate)"
fi
