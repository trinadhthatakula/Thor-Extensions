#!/usr/bin/env bash
#
# build-changed.sh — detect which verified/ extensions need a release.
#
# For each verified/<ext>/:
#   1. read the release versionName from app/build.gradle.kts
#      (skip with a warning if the file or versionName is missing)
#   2. compute the release tag  <ext-dir-name>-v<versionName>
#      e.g. verified/strombringer @ 1.00.0 -> strombringer-v1.00.0
#   3. if that tag already exists (locally, or on origin), the ext is already
#      released -> SKIP it. This is what makes the pipeline idempotent:
#      bumping one extension never rebuilds/re-releases the others.
#   4. otherwise the extension NEEDS a release.
#
# Output:
#   - Human-readable progress to stderr (always).
#   - To stdout: one TAB-separated "<dir>\t<version>\t<tag>" line per extension
#     that needs a release. Run it locally with no args for a dry-run:
#       bash scripts/build-changed.sh
#   - When $GITHUB_OUTPUT is set (running inside GitHub Actions) it also writes:
#       matrix=<json>   a {"include":[ {ext,dir,version,tag,apk_name}, ... ]}
#                       object suitable for `strategy.matrix`
#       any=true|false  whether anything needs building
#
# Fail-safe: a malformed extension directory is skipped with a warning, never
# crashes the run. Exit status is 0 whenever detection completes.
#
# Env knobs:
#   BUILD_CHANGED_SKIP_REMOTE=1   skip the `git ls-remote` check (offline runs)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERIFIED_DIR="verified"

log() { printf '%s\n' "$*" >&2; }

# Return 0 if a git tag exists locally or on origin, 1 otherwise.
tag_exists() {
  local tag="$1"
  if git tag -l "$tag" | grep -qx "$tag"; then
    return 0
  fi
  if [ "${BUILD_CHANGED_SKIP_REMOTE:-0}" != "1" ] && git remote get-url origin >/dev/null 2>&1; then
    if git ls-remote --tags origin "refs/tags/$tag" 2>/dev/null | grep -q "refs/tags/$tag"; then
      return 0
    fi
  fi
  return 1
}

# Extract the first  versionName = "x.y.z"  from a build.gradle.kts (portable
# across GNU + BSD grep/sed). Prints nothing if absent.
read_version() {
  local gradle_file="$1"
  grep -E 'versionName[[:space:]]*=[[:space:]]*"' "$gradle_file" 2>/dev/null \
    | head -n1 \
    | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/'
}

json_entries=""
any="false"

if [ ! -d "$VERIFIED_DIR" ]; then
  log "WARN: no $VERIFIED_DIR/ directory — nothing to do"
else
  for ext_path in "$VERIFIED_DIR"/*/; do
    [ -d "$ext_path" ] || continue
    ext="$(basename "$ext_path")"
    gradle_file="${ext_path}app/build.gradle.kts"

    if [ ! -f "$gradle_file" ]; then
      log "WARN: skip $ext — no app/build.gradle.kts"
      continue
    fi

    version="$(read_version "$gradle_file" || true)"
    if [ -z "$version" ]; then
      log "WARN: skip $ext — no versionName in $gradle_file"
      continue
    fi

    tag="${ext}-v${version}"
    apk_name="${ext}-${version}.apk"
    dir="${VERIFIED_DIR}/${ext}"

    if tag_exists "$tag"; then
      log "SKIP: $ext $version already released (tag $tag)"
      continue
    fi

    log "BUILD: $ext $version needs a release (tag $tag)"
    printf '%s\t%s\t%s\n' "$dir" "$version" "$tag"

    entry="{\"ext\":\"${ext}\",\"dir\":\"${dir}\",\"version\":\"${version}\",\"tag\":\"${tag}\",\"apk_name\":\"${apk_name}\"}"
    if [ -n "$json_entries" ]; then
      json_entries="${json_entries},${entry}"
    else
      json_entries="${entry}"
    fi
    any="true"
  done
fi

matrix="{\"include\":[${json_entries}]}"

log ""
if [ "$any" = "true" ]; then
  log "-> matrix: $matrix"
else
  log "-> nothing to build (all verified extensions already released)"
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    printf 'matrix=%s\n' "$matrix"
    printf 'any=%s\n' "$any"
  } >> "$GITHUB_OUTPUT"
fi
