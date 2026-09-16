#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail() {
  echo "::error::$1"
  exit 1
}

catalog_value() {
  tr -d '\r' | sed -n "s/^$1 = \"\(.*\)\"$/\1/p"
}

tag="${1:?usage: check-release-version.sh <tag>}"
catalog=gradle/libs.versions.toml

[[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || fail "Tag $tag is not v<major>.<minor>.<patch>"
version="${BASH_REMATCH[1]}"

checkout_commit=$(git rev-parse HEAD)
remote_tag=$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}") || fail "Cannot list tag $tag at origin"
tagged_commit=$(awk -v direct="refs/tags/$tag" -v peeled="refs/tags/$tag^{}" '
  $2 == direct { direct_commit = $1 }
  $2 == peeled { peeled_commit = $1 }
  END { print (peeled_commit != "" ? peeled_commit : direct_commit) }
' <<< "$remote_tag")
[ -n "$tagged_commit" ] || fail "Tag $tag does not exist at origin"
[ "$tagged_commit" = "$checkout_commit" ] || fail "Tag $tag at origin is at $tagged_commit, the checkout is at $checkout_commit"

name=$(catalog_value versionName < "$catalog")
code=$(catalog_value versionCode < "$catalog")
[ "$name" = "$version" ] || fail "Tag $tag does not match versionName ${name:-missing} in $catalog"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || fail "versionCode ${code:-missing} in $catalog is not a positive number"
[ -s "release-notes/$version.md" ] || fail "release-notes/$version.md is missing or empty"

previous="${PREVIOUS_TAG:-}"
if [ -z "$previous" ]; then
  previous=$(gh api "repos/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}/releases/latest" --jq .tag_name) \
    || fail "Cannot read the latest release of $GITHUB_REPOSITORY"
fi

if [ "$previous" = "$tag" ]; then
  echo "Tag $tag is already the latest release, so there is no earlier version to compare"
else
  if ! git rev-parse --verify --quiet "refs/tags/$previous" > /dev/null; then
    git fetch --quiet --no-tags --depth=1 origin "refs/tags/$previous:refs/tags/$previous" \
      || fail "Cannot fetch the previous release tag $previous"
  fi
  previous_catalog=$(git show "refs/tags/$previous:$catalog") || fail "Cannot read $catalog at $previous"
  previous_name=$(catalog_value versionName <<< "$previous_catalog")
  previous_code=$(catalog_value versionCode <<< "$previous_catalog")
  [[ "$previous_code" =~ ^[0-9]+$ ]] || fail "versionCode ${previous_code:-missing} of $previous is not a number"
  highest=$(printf '%s\n%s\n' "$previous_name" "$version" | sort -V | tail -1)
  if [ "$version" = "$previous_name" ] || [ "$highest" != "$version" ]; then
    fail "versionName $version is not above $previous_name of $previous"
  fi
  [ "$code" -gt "$previous_code" ] || fail "versionCode $code is not above $previous_code of $previous"
  echo "Version $version ($code) is above $previous_name ($previous_code) of $previous"
fi

echo "name=$version" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "code=$code" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "Tag $tag matches versionName $version and versionCode $code at $checkout_commit"
