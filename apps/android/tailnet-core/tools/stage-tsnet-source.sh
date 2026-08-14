#!/usr/bin/env bash
set -euo pipefail
export GIT_NO_LAZY_FETCH=1

fail() {
  printf 'TSNET_AAR_INPUTS_BLOCKED: %s\n' "$1" >&2
  exit 2
}

controller_fetch_prerequisite() {
  printf '%s\n' 'git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10' >&2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_dir=''
output_dir=''
lock_file="$script_dir/../native/tsnetbridge/tsnet-aar.lock.json"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || fail '--source requires a path'
      source_dir="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || fail '--output requires a path'
      output_dir="$2"
      shift 2
      ;;
    --lock)
      [[ $# -ge 2 ]] || fail '--lock requires a path'
      lock_file="$2"
      shift 2
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$source_dir" ]] || fail '--source is required'
[[ -n "$output_dir" ]] || fail '--output is required'
[[ -d "$source_dir" ]] || fail "source checkout is missing: $source_dir"
[[ -d "$output_dir" && ! -L "$output_dir" ]] || fail 'output must be an existing directory, not a symlink'
[[ -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail 'output directory must be empty'
source_real="$(realpath -e "$source_dir")" || fail 'source checkout path cannot be resolved'
output_real="$(realpath -e "$output_dir")" || fail 'output directory path cannot be resolved'
case "$output_real/" in
  "$source_real/"*) fail 'output directory must be outside the source checkout' ;;
esac

bash "$script_dir/verify-tsnet-aar-inputs.sh" --source "$source_dir" --lock "$lock_file"

mapfile -t source_values < <(python3 - "$lock_file" <<'PY'
import json, pathlib, sys
source = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["source"]
for key in ("upstreamUrl", "release", "tagObject", "commit"):
    print(source[key])
PY
)
[[ "${#source_values[@]}" -eq 4 ]] || fail 'lock source metadata is incomplete'
upstream_url="${source_values[0]}"
release="${source_values[1]}"
tag_object="${source_values[2]}"
commit="${source_values[3]}"

archive_tar="$(mktemp)"
trap 'rm -f "$archive_tar"' EXIT
if ! git -C "$source_dir" archive --format=tar --output="$archive_tar" "$commit"; then
  controller_fetch_prerequisite
  fail 'pinned archive objects are incomplete'
fi
tar -xf "$archive_tar" -C "$output_dir" || fail 'pinned source archive could not be extracted'
rm -f "$archive_tar"
[[ ! -e "$output_dir/.git" ]] || fail 'Git metadata appeared in staged source'

python3 - "$output_dir/source-metadata.json" "$upstream_url" "$release" "$tag_object" "$commit" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
metadata = {
    "schemaVersion": 1,
    "upstreamUrl": sys.argv[2],
    "release": sys.argv[3],
    "tagObject": sys.argv[4],
    "commit": sys.argv[5],
    "archiveFormat": "git-archive-tar",
}
path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

manifest="$output_dir/source-manifest.sha256"
(
  cd "$output_dir"
  while IFS= read -r -d '' path; do
    sha256sum "$path"
  done < <(find . \( -type f -o -type l \) ! -name source-manifest.sha256 -printf '%P\0' | LC_ALL=C sort -z)
) >"$manifest"

chmod -R a-w "$output_dir"
printf 'TSNET_SOURCE_STAGED\n'
printf 'source_commit=%s\n' "$commit"
printf 'output=%s\n' "$output_dir"
