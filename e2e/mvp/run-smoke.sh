#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${1:-}"
if [[ "$MODE" != "--sdk-free" && "$MODE" != "--release" ]]; then
  echo "usage: $0 --sdk-free|--release" >&2
  exit 2
fi

if [[ "$MODE" == "--release" ]]; then
  if ! npm --prefix "$ROOT_DIR" run mvp:lock:check; then
    echo "RELEASE_GATE_BLOCKED: dependency lock is pending"
    exit 1
  fi
  if ! command -v adb >/dev/null 2>&1 || ! adb devices 2>/dev/null | grep -q '[[:space:]]device$'; then
    echo "RELEASE_GATE_BLOCKED: no adb-connected reference device"
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "RELEASE_GATE_BLOCKED: Java/Gradle Android toolchain is unavailable"
    exit 1
  fi
  (cd "$ROOT_DIR/apps/android" && ./gradlew --no-daemon check)
  echo "RELEASE_GATE_PASS"
  exit 0
fi

NODE_BIN="$ROOT_DIR/.worktrees/p0a-protocol-security-model/node_modules/.bin"
VITEST="$NODE_BIN/vitest"
TSC="$NODE_BIN/tsc"

if [[ ! -x "$VITEST" ]]; then
  echo "SDK_FREE_BLOCKED: Vitest launcher missing ($VITEST)" >&2
  exit 1
fi

"$VITEST" --root "$ROOT_DIR" run \
  integrations \
  bridge-contract/test \
  bridge-runtime/test \
  artifact-contract/test \
  mvp-contract/test/mvp-contract.test.ts \
  mvp-contract/test/dependency-lock.test.ts

python3 -m unittest discover -s "$ROOT_DIR/apps/android/tools" -p 'test_*.py'

if [[ -x "$TSC" ]]; then
  "$TSC" --ignoreConfig --noEmit --target ES2022 --module NodeNext \
    --moduleResolution NodeNext --strict --skipLibCheck \
    "$ROOT_DIR"/bridge-contract/src/*.ts \
    "$ROOT_DIR"/bridge-runtime/src/*.ts \
    "$ROOT_DIR"/artifact-contract/src/*.ts \
    "$ROOT_DIR"/mvp-contract/src/wire-codec.ts \
    "$ROOT_DIR"/integrations/shared/adapter.ts \
    "$ROOT_DIR"/integrations/hermes/adapter.ts \
    "$ROOT_DIR"/integrations/openclaw/adapter.ts \
    --types node --typeRoots "$ROOT_DIR/.worktrees/p0a-protocol-security-model/node_modules/@types"
fi

if npm --prefix "$ROOT_DIR" run mvp:lock:check >/tmp/agent-life-mvp-lock.out 2>&1; then
  echo "LOCK_GATE_PASS"
else
  echo "LOCK_GATE_PENDING"
  sed -n '1,16p' /tmp/agent-life-mvp-lock.out
fi

if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q '[[:space:]]device$'; then
  echo "ANDROID_QA_AVAILABLE: run the locked Gradle/device smoke separately"
else
  echo "ANDROID_QA_SKIPPED: no adb/device in this environment"
fi

echo "SDK_FREE_PASS"
