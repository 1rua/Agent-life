#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
verifier="$repo_root/apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh"
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

pass_count=0

pass() {
  pass_count=$((pass_count + 1))
  printf 'ok %d - %s\n' "$pass_count" "$1"
}

create_checkout() {
  local checkout="$1"
  local version_value="${2:-1.98.10}"
  local module_value="${3:-tailscale.com}"
  local go_value="${4:-1.26.5}"

  git init -q "$checkout"
  git -C "$checkout" config user.name 'Tsnet Test'
  git -C "$checkout" config user.email 'tsnet-test@example.invalid'
  git -C "$checkout" remote add origin https://github.com/tailscale/tailscale.git
  printf '%s\n' "$version_value" >"$checkout/VERSION.txt"
  printf 'module %s\n\ngo %s\n' "$module_value" "$go_value" >"$checkout/go.mod"
  printf 'pinned archive content\n' >"$checkout/pinned-only.txt"
  git -C "$checkout" add VERSION.txt go.mod pinned-only.txt
  git -C "$checkout" commit -q -m pinned
  git -C "$checkout" tag -a v1.98.10 -m 'v1.98.10'
  FIXTURE_COMMIT="$(git -C "$checkout" rev-parse 'v1.98.10^{commit}')"
  FIXTURE_TAG_OBJECT="$(git -C "$checkout" rev-parse v1.98.10)"
}

write_lock() {
  local lock_file="$1"
  local tag_object="$2"
  local commit="$3"
  local version_value="${4:-1.98.10}"
  local module_value="${5:-tailscale.com}"
  local go_value="${6:-1.26.5}"

  cat >"$lock_file" <<EOF
{
  "schemaVersion": 1,
  "source": {
    "upstreamUrl": "https://github.com/tailscale/tailscale.git",
    "release": "v1.98.10",
    "tagObject": "$tag_object",
    "commit": "$commit",
    "sourceFiles": {
      "version": {"path": "VERSION.txt", "value": "$version_value"},
      "module": {"path": "go.mod", "module": "$module_value", "goDirective": "$go_value"}
    }
  },
  "toolchain": {
    "go": {"archive": "go1.26.5.linux-amd64.tar.gz", "sha256": "5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"},
    "gomobile": {"module": "golang.org/x/mobile", "tools": ["gomobile", "gobind"]},
    "androidNdk": {"archive": "android-ndk-r27c-linux.zip", "revision": "27.2.12479018", "sha256": "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc", "provisionalDigest": true},
    "jdk": {"distribution": "Temurin", "version": "17.0.20+8"},
    "androidSdk": {"gomobileApi": 34, "compileSdk": 35},
    "gradle": {"agp": "8.9.2", "gradle": "8.12", "kotlin": "2.1.20"}
  },
  "abi": {
    "gomobileTargets": ["android/arm64", "android/amd64"],
    "aarAbis": ["arm64-v8a", "x86_64"]
  },
  "build": {
    "argumentVector": ["gomobile", "bind", "-target=android/arm64,android/amd64", "-androidapi=34", "-trimpath", "-tags=ts_omit_cachenetmap", "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384", "-o", "tsnet-android-1.98.10.raw.aar", "./tsnetbridge"],
    "minimumElfLoadAlignment": 16384,
    "maximumAarBytes": 83886080,
    "wireFrame": {"transport": "binary-wss-message", "minimumBytes": 1, "maximumBytes": 262144}
  },
  "dependencies": {
    "golang.org/x/mobile": {"version": "v0.0.0-20240806205939-81131f6468ab", "sum": "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="},
    "github.com/coder/websocket": {"version": "v1.8.12", "sum": null}
  },
  "outputs": {
    "rawAar": "tsnet-android-1.98.10.raw.aar",
    "aar": "tsnet-android-1.98.10.aar",
    "aarSha256": "tsnet-android-1.98.10.aar.sha256",
    "provenance": "tsnet-android-1.98.10.provenance.json",
    "sbom": "tsnet-android-1.98.10.sbom.json",
    "notices": "THIRD_PARTY_NOTICES.md"
  }
}
EOF
}

expect_blocked() {
  local name="$1"
  shift
  local output_file="$tmp_root/output-$pass_count.txt"
  local status=0

  "$@" >"$output_file" 2>&1 || status=$?
  if [[ "$status" -ne 2 ]]; then
    printf 'not ok - %s (expected exit 2, got %s)\n' "$name" "$status" >&2
    cat "$output_file" >&2
    exit 1
  fi
  grep -F 'TSNET_AAR_INPUTS_BLOCKED' "$output_file" >/dev/null || {
    printf 'not ok - %s (missing blocked marker)\n' "$name" >&2
    cat "$output_file" >&2
    exit 1
  }
  pass "$name"
}

base="$tmp_root/base"
create_checkout "$base"
write_lock "$tmp_root/base.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"

expect_blocked 'missing checkout is rejected' \
  bash "$verifier" --source "$tmp_root/missing" --lock "$tmp_root/base.lock.json"

wrong_origin="$tmp_root/wrong-origin"
create_checkout "$wrong_origin"
write_lock "$tmp_root/wrong-origin.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
git -C "$wrong_origin" remote set-url origin https://example.invalid/tailscale.git
expect_blocked 'wrong origin URL is rejected' \
  bash "$verifier" --source "$wrong_origin" --lock "$tmp_root/wrong-origin.lock.json"

dirty="$tmp_root/dirty"
create_checkout "$dirty"
write_lock "$tmp_root/dirty.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
printf 'dirty\n' >>"$dirty/VERSION.txt"
expect_blocked 'dirty checkout is rejected' \
  bash "$verifier" --source "$dirty" --lock "$tmp_root/dirty.lock.json"

missing_tag="$tmp_root/missing-tag"
create_checkout "$missing_tag"
write_lock "$tmp_root/missing-tag.lock.json" 0000000000000000000000000000000000000000 "$FIXTURE_COMMIT"
expect_blocked 'missing tag object is rejected' \
  bash "$verifier" --source "$missing_tag" --lock "$tmp_root/missing-tag.lock.json"

promisor="$tmp_root/promisor"
create_checkout "$promisor"
write_lock "$tmp_root/promisor.lock.json" 1111111111111111111111111111111111111111 "$FIXTURE_COMMIT"
git -C "$promisor" config extensions.partialClone origin
git -C "$promisor" config remote.origin.promisor true
git -C "$promisor" config remote.origin.partialclonefilter blob:none
promisor_trace="$tmp_root/promisor-trace.json"
expect_blocked 'missing promisor object is rejected without lazy fetch' \
  env GIT_TRACE2_EVENT="$promisor_trace" HTTPS_PROXY=http://127.0.0.1:9 \
  bash "$verifier" --source "$promisor" --lock "$tmp_root/promisor.lock.json"
if grep -F '"fetch","origin","--no-tags"' "$promisor_trace" >/dev/null; then
  echo 'not ok - verifier attempted a lazy fetch' >&2
  exit 1
fi

wrong_tag_type="$tmp_root/wrong-tag-type"
create_checkout "$wrong_tag_type"
write_lock "$tmp_root/wrong-tag-type.lock.json" "$FIXTURE_COMMIT" "$FIXTURE_COMMIT"
expect_blocked 'lightweight or wrong tag object is rejected' \
  bash "$verifier" --source "$wrong_tag_type" --lock "$tmp_root/wrong-tag-type.lock.json"

wrong_peeled="$tmp_root/wrong-peeled"
create_checkout "$wrong_peeled"
pinned_tag_object="$FIXTURE_TAG_OBJECT"
printf 'second commit\n' >"$wrong_peeled/second.txt"
git -C "$wrong_peeled" add second.txt
git -C "$wrong_peeled" commit -q -m second
second_commit="$(git -C "$wrong_peeled" rev-parse HEAD)"
write_lock "$tmp_root/wrong-peeled.lock.json" "$pinned_tag_object" "$second_commit"
expect_blocked 'wrong peeled commit is rejected' \
  bash "$verifier" --source "$wrong_peeled" --lock "$tmp_root/wrong-peeled.lock.json"

wrong_version="$tmp_root/wrong-version"
create_checkout "$wrong_version" 9.9.9
write_lock "$tmp_root/wrong-version.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
expect_blocked 'wrong VERSION.txt is rejected' \
  bash "$verifier" --source "$wrong_version" --lock "$tmp_root/wrong-version.lock.json"

wrong_module="$tmp_root/wrong-module"
create_checkout "$wrong_module" 1.98.10 example.invalid/tailscale
write_lock "$tmp_root/wrong-module.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
expect_blocked 'wrong module is rejected' \
  bash "$verifier" --source "$wrong_module" --lock "$tmp_root/wrong-module.lock.json"

wrong_go="$tmp_root/wrong-go"
create_checkout "$wrong_go" 1.98.10 tailscale.com 9.9.9
write_lock "$tmp_root/wrong-go.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
expect_blocked 'wrong Go directive is rejected' \
  bash "$verifier" --source "$wrong_go" --lock "$tmp_root/wrong-go.lock.json"

missing_commit="$tmp_root/missing-commit"
create_checkout "$missing_commit"
write_lock "$tmp_root/missing-commit.lock.json" "$FIXTURE_TAG_OBJECT" "$FIXTURE_COMMIT"
printf 'current HEAD remains readable\n' >"$missing_commit/head.txt"
git -C "$missing_commit" add head.txt
git -C "$missing_commit" commit -q -m head
commit_object="$missing_commit/.git/objects/${FIXTURE_COMMIT:0:2}/${FIXTURE_COMMIT:2}"
mv "$commit_object" "$tmp_root/removed-commit-object"
missing_output="$tmp_root/missing-commit-output.txt"
status=0
bash "$verifier" --source "$missing_commit" --lock "$tmp_root/missing-commit.lock.json" >"$missing_output" 2>&1 || status=$?
[[ "$status" -eq 2 ]] || { cat "$missing_output" >&2; exit 1; }
grep -Fx 'git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10' "$missing_output" >/dev/null
pass 'missing commit object prints the exact controller action without executing it'

unknown_lock="$tmp_root/unknown.lock.json"
cp "$tmp_root/base.lock.json" "$unknown_lock"
python3 - "$unknown_lock" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["unexpected"] = True
path.write_text(json.dumps(data))
PY
expect_blocked 'unknown lock keys are rejected' \
  bash "$verifier" --source "$base" --lock "$unknown_lock"

missing_lock_key="$tmp_root/missing-key.lock.json"
cp "$tmp_root/base.lock.json" "$missing_lock_key"
python3 - "$missing_lock_key" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
del data["source"]["tagObject"]
path.write_text(json.dumps(data))
PY
expect_blocked 'missing lock keys are rejected' \
  bash "$verifier" --source "$base" --lock "$missing_lock_key"

head_differs="$tmp_root/head-differs"
create_checkout "$head_differs"
pinned_commit="$FIXTURE_COMMIT"
pinned_object="$FIXTURE_TAG_OBJECT"
printf 'current HEAD only\n' >"$head_differs/head-only.txt"
git -C "$head_differs" add head-only.txt
git -C "$head_differs" commit -q -m head
write_lock "$tmp_root/head-differs.lock.json" "$pinned_object" "$pinned_commit"
before="$(git -C "$head_differs" rev-parse HEAD):$(git -C "$head_differs" status --porcelain=v1 | sha256sum)"
ready_output="$tmp_root/ready-output.txt"
bash "$verifier" --source "$head_differs" --lock "$tmp_root/head-differs.lock.json" >"$ready_output"
after="$(git -C "$head_differs" rev-parse HEAD):$(git -C "$head_differs" status --porcelain=v1 | sha256sum)"
[[ "$before" == "$after" ]]
grep -Fx 'TSNET_AAR_INPUTS_READY' "$ready_output" >/dev/null
pass 'valid checkout with a different HEAD is verified without mutation'

printf '1..%d\n' "$pass_count"
