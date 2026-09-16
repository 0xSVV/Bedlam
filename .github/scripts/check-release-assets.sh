#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail() {
  echo "::error::$1"
  exit 1
}

tag="${1:?usage: check-release-assets.sh <tag> <stage-dir>}"
stage="${2:?usage: check-release-assets.sh <tag> <stage-dir>}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"

staged=$(
  for file in "$stage"/*; do
    [ -f "$file" ] || continue
    echo "$(sha256sum "$file" | cut -d' ' -f1) $(basename "$file")"
  done | sort -k2
)
[ -n "$staged" ] || fail "$stage holds no files"

release_readable=false
for attempt in 1 2 3 4 5 6; do
  if response=$(gh api "repos/$repository/releases/tags/$tag" --jq '.assets[] | "\(.digest // "missing" | ltrimstr("sha256:")) \(.name)"'); then
    release_readable=true
    uploaded=$(sort -k2 <<< "$response")
    grep -q '^missing ' <<< "$uploaded" || break
    problem="GitHub has not reported every asset digest yet"
  else
    release_readable=false
    problem="Cannot read release $tag of $repository"
  fi
  [ "$attempt" -lt 6 ] || break
  echo "$problem, retrying"
  sleep 10
done
[ "$release_readable" = true ] || fail "Cannot read release $tag of $repository"

if [ "$uploaded" != "$staged" ]; then
  printf 'Staged:\n%s\nUploaded:\n%s\n' "$staged" "$uploaded"
  fail "Release $tag assets differ from the files this job verified"
fi
echo "Release $tag carries exactly the $(wc -l <<< "$staged") files this job verified"
