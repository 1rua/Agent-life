#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
verifier="$repo_root/apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh"

output_file="$(mktemp)"
trap 'rm -f "$output_file"' EXIT

if TSNET_SOURCE_DIR="$repo_root/does-not-exist" bash "$verifier" >"$output_file" 2>&1; then
  echo "expected missing source directory to be rejected" >&2
  exit 1
fi

grep -F "TSNET_SOURCE_DIR is not a directory" "$output_file" >/dev/null
grep -F "MVP-DEP-TSNET remains pending" "$output_file" >/dev/null
