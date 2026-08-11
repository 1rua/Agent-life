#!/usr/bin/env bash
# Verifies the immutable, offline inputs required before any tsnet Android AAR
# build is allowed. It deliberately does not download source or fabricate a
# lock: a project controller must supply a reviewed local Tailscale checkout.
set -euo pipefail

fail() {
  printf 'TSNET_AAR_INPUTS_BLOCKED: %s\n' "$1" >&2
  printf 'MVP-DEP-TSNET remains pending; no AAR was built.\n' >&2
  exit 2
}

source_dir="${TSNET_SOURCE_DIR:-}"
expected_commit="${TSNET_SOURCE_COMMIT:-}"
go_bin="${GO_BIN:-go}"
gomobile_bin="${GOMOBILE_BIN:-gomobile}"

[[ -n "$source_dir" ]] || fail "TSNET_SOURCE_DIR is required"
[[ -d "$source_dir" ]] || fail "TSNET_SOURCE_DIR is not a directory: $source_dir"
[[ -f "$source_dir/go.mod" ]] || fail "Tailscale go.mod is missing: $source_dir/go.mod"
grep -qx 'module tailscale.com' "$source_dir/go.mod" || fail "go.mod is not the Tailscale source module"
[[ -f "$source_dir/tsnet/tsnet.go" ]] || fail "tsnet/tsnet.go is missing from the source checkout"
[[ -n "$expected_commit" ]] || fail "TSNET_SOURCE_COMMIT is required"

command -v "$go_bin" >/dev/null 2>&1 || fail "GO_BIN is not executable: $go_bin"
command -v "$gomobile_bin" >/dev/null 2>&1 || fail "GOMOBILE_BIN is not executable: $gomobile_bin"
[[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]] || fail "ANDROID_HOME must reference an installed SDK"
[[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]] || fail "ANDROID_NDK_HOME must reference an installed NDK"

git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Tailscale source is not a Git checkout"
actual_commit="$(git -C "$source_dir" rev-parse HEAD)"
[[ "$actual_commit" == "$expected_commit" ]] || fail "source commit does not match TSNET_SOURCE_COMMIT"
[[ -z "$(git -C "$source_dir" status --porcelain)" ]] || fail "Tailscale source checkout is dirty"

"$go_bin" version
"$gomobile_bin" help bind >/dev/null
printf 'TSNET_AAR_INPUTS_READY\n'
printf 'source_commit=%s\n' "$actual_commit"
printf 'target=android/arm64\n'
printf 'android_api=34\n'
