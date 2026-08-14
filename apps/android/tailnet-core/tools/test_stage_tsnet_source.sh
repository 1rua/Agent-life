#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
stager="$repo_root/apps/android/tailnet-core/tools/stage-tsnet-source.sh"
tmp_root="$(mktemp -d)"
trap 'chmod -R u+w "$tmp_root" 2>/dev/null || true; rm -rf "$tmp_root"' EXIT

checkout="$tmp_root/tailscale"
git init -q "$checkout"
git -C "$checkout" config user.name 'Tsnet Stage Test'
git -C "$checkout" config user.email 'tsnet-stage-test@example.invalid'
git -C "$checkout" remote add origin https://github.com/tailscale/tailscale.git
printf '1.98.10\n' >"$checkout/VERSION.txt"
printf 'module tailscale.com\n\ngo 1.26.5\n' >"$checkout/go.mod"
printf 'from pinned commit\n' >"$checkout/pinned-only.txt"
git -C "$checkout" add VERSION.txt go.mod pinned-only.txt
git -C "$checkout" commit -q -m pinned
git -C "$checkout" tag -a v1.98.10 -m 'v1.98.10'
pinned_commit="$(git -C "$checkout" rev-parse 'v1.98.10^{commit}')"
tag_object="$(git -C "$checkout" rev-parse v1.98.10)"
rm "$checkout/pinned-only.txt"
printf 'from current HEAD\n' >"$checkout/head-only.txt"
git -C "$checkout" add -A
git -C "$checkout" commit -q -m head

lock_file="$tmp_root/fixture.lock.json"
python3 - "$lock_file" "$tag_object" "$pinned_commit" <<'PY'
import json, pathlib, sys
path_value, tag_object, commit = sys.argv[1:]
path = pathlib.Path(path_value)
data = {
    "schemaVersion": 1,
    "source": {
        "upstreamUrl": "https://github.com/tailscale/tailscale.git",
        "release": "v1.98.10",
        "tagObject": tag_object,
        "commit": commit,
        "sourceFiles": {
            "version": {"path": "VERSION.txt", "value": "1.98.10"},
            "module": {"path": "go.mod", "module": "tailscale.com", "goDirective": "1.26.5"},
        },
    },
    "toolchain": {
        "go": {"archive": "go1.26.5.linux-amd64.tar.gz", "sha256": "5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"},
        "gomobile": {"module": "golang.org/x/mobile", "tools": ["gomobile", "gobind"]},
        "androidNdk": {"archive": "android-ndk-r27c-linux.zip", "revision": "27.2.12479018", "sha256": "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc", "provisionalDigest": True},
        "jdk": {"distribution": "Temurin", "version": "17.0.20+8"},
        "androidSdk": {"gomobileApi": 34, "compileSdk": 35},
        "gradle": {"agp": "8.9.2", "gradle": "8.12", "kotlin": "2.1.20"},
    },
    "abi": {"gomobileTargets": ["android/arm64", "android/amd64"], "aarAbis": ["arm64-v8a", "x86_64"]},
    "build": {
        "argumentVector": ["gomobile", "bind", "-target=android/arm64,android/amd64", "-androidapi=34", "-trimpath", "-tags=ts_omit_cachenetmap", "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384", "-o", "tsnet-android-1.98.10.raw.aar", "./tsnetbridge"],
        "minimumElfLoadAlignment": 16384,
        "maximumAarBytes": 83886080,
        "wireFrame": {"transport": "binary-wss-message", "minimumBytes": 1, "maximumBytes": 262144},
    },
    "dependencies": {
        "golang.org/x/mobile": {"version": "v0.0.0-20240806205939-81131f6468ab", "sum": "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="},
        "github.com/coder/websocket": {"version": "v1.8.12", "sum": None},
    },
    "outputs": {
        "rawAar": "tsnet-android-1.98.10.raw.aar",
        "aar": "tsnet-android-1.98.10.aar",
        "aarSha256": "tsnet-android-1.98.10.aar.sha256",
        "provenance": "tsnet-android-1.98.10.provenance.json",
        "sbom": "tsnet-android-1.98.10.sbom.json",
        "notices": "THIRD_PARTY_NOTICES.md",
    },
}
path.write_text(json.dumps(data, indent=2) + "\n")
PY

before="$(git -C "$checkout" rev-parse HEAD):$(git -C "$checkout" status --porcelain=v1 | sha256sum)"
stage_dir="$(mktemp -d -p "$tmp_root" stage.XXXXXXXXXX)"
bash "$stager" --source "$checkout" --lock "$lock_file" --output "$stage_dir"
after="$(git -C "$checkout" rev-parse HEAD):$(git -C "$checkout" status --porcelain=v1 | sha256sum)"

[[ "$before" == "$after" ]]
[[ -f "$stage_dir/pinned-only.txt" ]]
[[ ! -e "$stage_dir/head-only.txt" ]]
[[ ! -e "$stage_dir/.git" ]]
[[ -f "$stage_dir/source-manifest.sha256" ]]
[[ -f "$stage_dir/source-metadata.json" ]]

python3 - "$stage_dir/source-metadata.json" "$tag_object" "$pinned_commit" <<'PY'
import json, pathlib, sys
metadata = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert metadata == {
    "schemaVersion": 1,
    "upstreamUrl": "https://github.com/tailscale/tailscale.git",
    "release": "v1.98.10",
    "tagObject": sys.argv[2],
    "commit": sys.argv[3],
    "archiveFormat": "git-archive-tar",
}
PY

manifest_paths="$tmp_root/manifest-paths.txt"
sed -n 's/^[0-9a-f]\{64\}  //p' "$stage_dir/source-manifest.sha256" >"$manifest_paths"
LC_ALL=C sort -c "$manifest_paths"
grep -Fx 'VERSION.txt' "$manifest_paths" >/dev/null
grep -Fx 'go.mod' "$manifest_paths" >/dev/null
grep -Fx 'pinned-only.txt' "$manifest_paths" >/dev/null
grep -Fx 'source-metadata.json' "$manifest_paths" >/dev/null
if grep -Fx 'source-manifest.sha256' "$manifest_paths" >/dev/null; then
  echo 'manifest must not inventory itself' >&2
  exit 1
fi
(cd "$stage_dir" && sha256sum -c source-manifest.sha256)

if find "$stage_dir" -perm /222 -print -quit | grep -q .; then
  echo 'staged tree must be read-only' >&2
  exit 1
fi

if ! git -C "$repo_root" check-ignore -q third_party/tailscale; then
  echo 'outer repository must ignore third_party/tailscale' >&2
  exit 1
fi
if git -C "$repo_root" ls-files --error-unmatch third_party/tailscale >/dev/null 2>&1; then
  echo 'third_party/tailscale must never be tracked' >&2
  exit 1
fi
if git -C "$repo_root" ls-files | grep -Eq '(^|/)tailscale/(VERSION\.txt|go\.mod)$'; then
  echo 'Tailscale source must never be copied into a tracked path' >&2
  exit 1
fi

inside_checkout="$tmp_root/inside-checkout"
git clone -q "$checkout" "$inside_checkout"
git -C "$inside_checkout" remote set-url origin https://github.com/tailscale/tailscale.git
inside_output="$inside_checkout/stage-output"
mkdir "$inside_output"
inside_before="$(git -C "$inside_checkout" rev-parse HEAD):$(git -C "$inside_checkout" status --porcelain=v1 | sha256sum)"
status=0
bash "$stager" --source "$inside_checkout" --lock "$lock_file" --output "$inside_output" \
  >"$tmp_root/inside-output.txt" 2>&1 || status=$?
inside_after="$(git -C "$inside_checkout" rev-parse HEAD):$(git -C "$inside_checkout" status --porcelain=v1 | sha256sum)"
[[ "$status" -eq 2 ]] || {
  cat "$tmp_root/inside-output.txt" >&2
  echo 'output inside source checkout must be rejected' >&2
  exit 1
}
[[ "$inside_before" == "$inside_after" ]]
[[ -z "$(find "$inside_output" -mindepth 1 -print -quit)" ]]

missing_blob_checkout="$tmp_root/missing-blob"
git clone -q "$checkout" "$missing_blob_checkout"
git -C "$missing_blob_checkout" remote set-url origin https://github.com/tailscale/tailscale.git
git -C "$missing_blob_checkout" config extensions.partialClone origin
git -C "$missing_blob_checkout" config remote.origin.promisor true
git -C "$missing_blob_checkout" config remote.origin.partialclonefilter blob:none
missing_blob="$(git -C "$missing_blob_checkout" rev-parse "$pinned_commit:pinned-only.txt")"
missing_blob_path="$missing_blob_checkout/.git/objects/${missing_blob:0:2}/${missing_blob:2}"
mv "$missing_blob_path" "$tmp_root/removed-archive-blob"
missing_blob_stage="$(mktemp -d -p "$tmp_root" missing-blob-stage.XXXXXXXXXX)"
missing_blob_trace="$tmp_root/missing-blob-trace.json"
status=0
env GIT_TRACE2_EVENT="$missing_blob_trace" HTTPS_PROXY=http://127.0.0.1:9 \
  bash "$stager" --source "$missing_blob_checkout" --lock "$lock_file" --output "$missing_blob_stage" \
  >"$tmp_root/missing-blob-output.txt" 2>&1 || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/missing-blob-output.txt" >&2; exit 1; }
grep -F 'TSNET_AAR_INPUTS_BLOCKED' "$tmp_root/missing-blob-output.txt" >/dev/null || {
  cat "$tmp_root/missing-blob-output.txt" >&2
  exit 1
}
grep -Fx 'git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10' "$tmp_root/missing-blob-output.txt" >/dev/null || {
  cat "$tmp_root/missing-blob-output.txt" >&2
  exit 1
}
[[ -z "$(find "$missing_blob_stage" -mindepth 1 -print -quit)" ]]
if grep -F '"fetch","origin","--no-tags"' "$missing_blob_trace" >/dev/null; then
  echo 'stager attempted a lazy fetch' >&2
  exit 1
fi

printf 'TSNET_SOURCE_STAGE_TESTS_PASS\n'
