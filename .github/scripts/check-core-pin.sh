#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail() {
  echo "::error::$1"
  exit 1
}

pin() {
  sed -n "s/^$1=//p" .github/toolchain.env
}

submodule=hysteria/upstream
expected_commit=$(pin HYSTERIA_CORE_COMMIT)
read -r -a expected_tags <<< "$(pin HYSTERIA_CORE_TAGS)"

[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || fail "HYSTERIA_CORE_COMMIT in .github/toolchain.env is not a full commit hash"
[ "${#expected_tags[@]}" -gt 0 ] || fail "HYSTERIA_CORE_TAGS in .github/toolchain.env is empty"

gitlink=$(git rev-parse --verify --quiet "HEAD:$submodule") || fail "HEAD has no $submodule gitlink"
[ "$gitlink" = "$expected_commit" ] || fail "$submodule gitlink is $gitlink, expected $expected_commit"

indexed=$(git ls-files --stage -- "$submodule" | awk '{print $2}')
[ "$indexed" = "$gitlink" ] || fail "$submodule index entry ${indexed:-missing} differs from the HEAD gitlink $gitlink"

[ -e "$submodule/.git" ] || fail "$submodule is not initialized"
submodule_head=$(git -C "$submodule" rev-parse HEAD)
[ "$submodule_head" = "$gitlink" ] || fail "$submodule HEAD is $submodule_head, the gitlink is $gitlink"

changes=$(git -C "$submodule" status --porcelain --untracked-files=all --ignored --ignore-submodules=none)
[ -z "$changes" ] || fail "$submodule has local changes: $(head -5 <<< "$changes" | paste -sd' ')"

url=$(git config -f .gitmodules "submodule.$submodule.url")
patterns=()
for tag in "${expected_tags[@]}"; do
  patterns+=("refs/tags/$tag" "refs/tags/$tag^{}")
done
remote_tags=$(git ls-remote "$url" "${patterns[@]}") || fail "Cannot list the tags of $url"
for tag in "${expected_tags[@]}"; do
  tagged=$(awk -v direct="refs/tags/$tag" -v peeled="refs/tags/$tag^{}" '
    $2 == direct { direct_commit = $1 }
    $2 == peeled { peeled_commit = $1 }
    END { print (peeled_commit != "" ? peeled_commit : direct_commit) }
  ' <<< "$remote_tags")
  [ "$tagged" = "$expected_commit" ] || fail "Tag $tag at $url is ${tagged:-missing}, expected $expected_commit"
done

echo "Hysteria core $expected_commit is pinned, clean, and tagged ${expected_tags[*]}"
