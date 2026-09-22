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

catalog_value() {
  tr -d '\r' < gradle/libs.versions.toml | sed -n "s/^$1 = \"\(.*\)\"$/\1/p"
}

quoted_property() {
  tr -d '\r' < "$1" | sed -n "s/^$2=\"\(.*\)\"$/\1/p"
}

sha256() {
  sha256sum "$1" | cut -d' ' -f1
}

file_size() {
  stat -c %s "$1"
}

json_strings() {
  jq -nc '$ARGS.positional' --args "$@"
}

module_dir() {
  (cd hysteria/golib && go list -m -f '{{.Dir}}' "$1")
}

submodule_source() {
  local dir="$1" name="$2" physical expected
  [ -n "$dir" ] || return 1
  expected=$(cd "hysteria/upstream/$name" && pwd -P) || return 1
  physical=$(cd "$dir" && pwd -P) || return 1
  [ "$physical" = "$expected" ] || return 1
  echo "${physical#"$repo_root"/}"
}

repo_root=$(pwd -P)

stage="${1:?usage: write-provenance.sh <stage-dir>}"
case "${RELEASE_BUILD:-false}" in
  true) release_build=true ;;
  false) release_build=false ;;
  *) fail "RELEASE_BUILD must be true or false, not $RELEASE_BUILD" ;;
esac
outputs=app/build/outputs/apk/release
aar=hysteria/libs/golib.aar
package=ru.shapovalov.bedlam
go_abis=(armeabi-v7a arm64-v8a x86_64)
variants=(armeabi-v7a arm64-v8a x86_64 universal)
declare -A abi_version_digit=([armeabi-v7a]=1 [arm64-v8a]=2 [x86_64]=4 [universal]=9)
declare -A abi_goarch=([armeabi-v7a]=arm [arm64-v8a]=arm64 [x86_64]=amd64)

version_name=$(catalog_value versionName)
version_code=$(catalog_value versionCode)
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "versionCode ${version_code:-missing} is not a positive number"
go_pin=$(pin GO_VERSION)
ndk_pin=$(pin NDK_VERSION)
ndk_build_number=$(cut -d. -f3 <<< "${ndk_pin%%-*}")
read -r -a core_tags <<< "$(pin HYSTERIA_CORE_TAGS)"

if [ "$release_build" = true ]; then
  expected_tag="${EXPECTED_TAG:?EXPECTED_TAG is required for a release build}"
  expected_cert="${EXPECTED_CERT_SHA256:?EXPECTED_CERT_SHA256 is required for a release build}"
  built_aar_sha256="${GOLIB_SHA256:?GOLIB_SHA256 is required for a release build}"
  [ "$expected_tag" = "v$version_name" ] || fail "Tag $expected_tag does not match versionName $version_name"
  [[ "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || fail "EXPECTED_CERT_SHA256 is not a SHA-256 digest"
else
  expected_cert="${EXPECTED_CERT_SHA256:-}"
  built_aar_sha256="${GOLIB_SHA256:-}"
fi

bash .github/scripts/check-core-pin.sh

tracked_changes=$(git status --porcelain --untracked-files=no)
if [ -z "$tracked_changes" ]; then
  tracked_tree_clean=true
elif [ "$release_build" = true ]; then
  fail "Tracked files differ from the commit: $(head -5 <<< "$tracked_changes" | paste -sd' ')"
else
  tracked_tree_clean=false
fi

ndk_home="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
build_tools_dir=$(find "${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
apksigner="${APKSIGNER:-$build_tools_dir/apksigner}"
aapt2="${AAPT2:-$build_tools_dir/aapt2}"
readelf="${READELF:-$ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}"

go_version=$(go env GOVERSION)
[ "$go_version" = "go$go_pin" ] || fail "Go is $go_version, the pin is go$go_pin"
mobile_version=$(cd hysteria/golib && go list -m -f '{{.Version}}' golang.org/x/mobile)
golib_dir=$(module_dir bedlam/golib)
core_dir=$(module_dir github.com/apernet/hysteria/core/v2)
extras_dir=$(module_dir github.com/apernet/hysteria/extras/v2)
core_source=$(submodule_source "$core_dir" core) \
  || fail "Go resolves the Hysteria core to ${core_dir:-no directory}, not the submodule's hysteria/upstream/core"
extras_source=$(submodule_source "$extras_dir" extras) \
  || fail "Go resolves the Hysteria extras to ${extras_dir:-no directory}, not the submodule's hysteria/upstream/extras"

declare -A tool_module tool_built_by
for tool in gomobile gobind; do
  tool_path=$(command -v "$tool") || fail "$tool is not on PATH"
  if [ -f "$tool_path.exe" ]; then
    tool_path="$tool_path.exe"
  fi
  tool_info=$(go version -m "$tool_path")
  tool_built_by[$tool]=$(head -1 <<< "$tool_info" | awk '{print $NF}')
  tool_module[$tool]=$(awk -F'\t' '$2 == "mod" {print $3 "@" $4}' <<< "$tool_info")
  [ "${tool_module[$tool]}" = "golang.org/x/mobile@$mobile_version" ] \
    || fail "$tool is ${tool_module[$tool]:-unknown}, go.mod selects golang.org/x/mobile@$mobile_version"
  [ "${tool_built_by[$tool]}" = "go$go_pin" ] || fail "$tool was built by ${tool_built_by[$tool]}, the pin is go$go_pin"
done

ndk_revision=$(tr -d '\r' < "$ndk_home/source.properties" | sed -n 's/^Pkg\.Revision *= *//p')
[ "$ndk_revision" = "$ndk_pin" ] || fail "NDK is ${ndk_revision:-unknown}, the pin is $ndk_pin"
java_version=$(quoted_property "${JAVA_HOME:?JAVA_HOME is not set}/release" JAVA_RUNTIME_VERSION)
java_vendor=$(quoted_property "$JAVA_HOME/release" IMPLEMENTOR)
gradle_version=$(./gradlew --version | tr -d '\r' | sed -n 's/^Gradle //p')

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

replacement_dir() {
  awk -F'\t' -v module="$1" '$2 == "dep" && $3 == module { getline; if ($2 == "=>") print $3 }'
}

inspect_library() {
  local library="$1" abi="$2" output="$3"
  local info built_by mobile golib core extras goarch ldflags tags ndk_ident build_id
  info=$(go version -m "$library")
  built_by=$(head -1 <<< "$info" | awk '{print $NF}')
  mobile=$(awk -F'\t' '$2 == "dep" && $3 == "golang.org/x/mobile" {print $4}' <<< "$info")
  golib=$(replacement_dir bedlam/golib <<< "$info")
  core=$(replacement_dir github.com/apernet/hysteria/core/v2 <<< "$info")
  extras=$(replacement_dir github.com/apernet/hysteria/extras/v2 <<< "$info")
  goarch=$(sed -n 's/^\tbuild\tGOARCH=//p' <<< "$info")
  ldflags=$(sed -n 's/^\tbuild\t-ldflags=//p' <<< "$info" | tr -d '"')
  tags=$(sed -n 's/^\tbuild\t-tags=//p' <<< "$info")
  ndk_ident=$("$readelf" -p .note.android.ident "$library" | tr -d '\r' | awk '/^ *\[/ {print $NF}' | tail -2 | paste -sd' ')
  build_id=$(go tool buildid "$library")
  [ "$built_by" = "go$go_pin" ] || fail "$abi libgojni.so was built by $built_by, the pin is go$go_pin"
  [ "$mobile" = "$mobile_version" ] || fail "$abi libgojni.so links golang.org/x/mobile ${mobile:-missing}, go.mod selects $mobile_version"
  [ "$golib" = "$golib_dir" ] || fail "$abi libgojni.so was built from ${golib:-an unknown} golib, not $golib_dir"
  [ "$core" = "$core_dir" ] || fail "$abi libgojni.so links the Hysteria core from ${core:-an unknown directory}, not $core_dir"
  [ "$extras" = "$extras_dir" ] || fail "$abi libgojni.so links the Hysteria extras from ${extras:-an unknown directory}, not $extras_dir"
  [ "$goarch" = "${abi_goarch[$abi]}" ] || fail "$abi libgojni.so is built for GOARCH ${goarch:-unknown}"
  case " $ndk_ident " in
    *" $ndk_build_number "*) ;;
    *) fail "$abi libgojni.so was linked by NDK ${ndk_ident:-unknown}, the pin is $ndk_pin" ;;
  esac
  jq -nc \
    --arg abi "$abi" \
    --arg sha256 "$(sha256 "$library")" \
    --arg goBuildId "$build_id" \
    --arg go "$built_by" \
    --arg goarch "$goarch" \
    --arg golangOrgXMobile "$mobile" \
    --arg ldflags "$ldflags" \
    --arg tags "$tags" \
    --arg ndk "$ndk_ident" \
    '{abi: $abi, sha256: $sha256, goBuildId: $goBuildId, go: $go, goarch: $goarch, golangOrgXMobile: $golangOrgXMobile, ldflags: $ldflags, tags: $tags, ndk: $ndk}' \
    >> "$output"
}

[ -f "$aar" ] || fail "$aar is missing"
aar_sha256=$(sha256 "$aar")
if [ -n "$built_aar_sha256" ] && [ "$built_aar_sha256" != "$aar_sha256" ]; then
  fail "$aar changed after it was built: $built_aar_sha256 then, $aar_sha256 now"
fi
aar_libraries=$(unzip -Z1 "$aar" | tr -d '\r' | grep -E '^jni/.*\.so$' | sort | paste -sd' ' || true)
[ "$aar_libraries" = "jni/arm64-v8a/libgojni.so jni/armeabi-v7a/libgojni.so jni/x86_64/libgojni.so" ] \
  || fail "$aar carries native libraries [$aar_libraries]"
declare -A aar_build_id
: > "$work/aar-libraries.jsonl"
for abi in "${go_abis[@]}"; do
  library="$work/aar-$abi.so"
  unzip -p "$aar" "jni/$abi/libgojni.so" > "$library"
  inspect_library "$library" "$abi" "$work/aar-libraries.jsonl"
  aar_build_id[$abi]=$(go tool buildid "$library")
done

expected_files=$(for variant in "${variants[@]}"; do echo "bedlam-v$version_name-$variant.apk"; done | sort)
found_files=$(find "$outputs" -maxdepth 1 -type f -name '*.apk' -printf '%f\n' | sort)
[ "$found_files" = "$expected_files" ] \
  || fail "Release outputs are [$(paste -sd' ' <<< "$found_files")], expected [$(paste -sd' ' <<< "$expected_files")]"
[ -f "$outputs/output-metadata.json" ] || fail "$outputs/output-metadata.json is missing"

rm -rf "$stage"
mkdir -p "$stage"
all_signed=true
: > "$work/apks.jsonl"
for variant in "${variants[@]}"; do
  file="bedlam-v$version_name-$variant.apk"
  apk="$stage/$file"
  cp "$outputs/$file" "$apk"

  badging=$("$aapt2" dump badging "$apk" | tr -d '\r' | sed -n 1p)
  apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
  apk_version_code=$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<< "$badging")
  apk_version_name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")
  expected_version_code=$((version_code * 10 + ${abi_version_digit[$variant]}))
  [ "$apk_package" = "$package" ] || fail "$file is package ${apk_package:-unknown}, expected $package"
  [ "$apk_version_name" = "$version_name" ] || fail "$file has versionName ${apk_version_name:-unknown}, expected $version_name"
  [ "$apk_version_code" = "$expected_version_code" ] || fail "$file has versionCode ${apk_version_code:-unknown}, expected $expected_version_code"

  signers=()
  if certificates=$("$apksigner" verify --print-certs "$apk" 2> /dev/null); then
    signed=true
    read -r -a signers <<< "$(tr -d '\r' <<< "$certificates" | sed -n 's/.*certificate SHA-256 digest: \([0-9a-f]\{64\}\)$/\1/p' | sort -u | paste -sd' ')"
  else
    signed=false
    all_signed=false
  fi
  if [ "$release_build" = true ]; then
    [ "$signed" = true ] || fail "$file does not verify as a signed APK"
    [ "${signers[*]}" = "$expected_cert" ] || fail "$file is signed by [${signers[*]}], expected $expected_cert"
  fi

  : > "$work/apk-libraries.jsonl"
  library_abis=()
  while read -r entry; do
    abi=$(cut -d/ -f2 <<< "$entry")
    [ -n "${aar_build_id[$abi]:-}" ] || fail "$file carries $entry, which the Go library does not build"
    library="$work/apk-$variant-$abi.so"
    unzip -p "$apk" "$entry" > "$library"
    inspect_library "$library" "$abi" "$work/apk-libraries.jsonl"
    [ "$(go tool buildid "$library")" = "${aar_build_id[$abi]}" ] || fail "$file $entry is not the library built into $aar"
    library_abis+=("$abi")
  done < <(unzip -Z1 "$apk" | tr -d '\r' | grep -E '^lib/[^/]+/libgojni\.so$' | sort)
  if [ "$variant" = universal ]; then
    expected_library_abis="arm64-v8a armeabi-v7a x86_64"
  else
    expected_library_abis="$variant"
  fi
  [ "${library_abis[*]}" = "$expected_library_abis" ] || fail "$file carries Go libraries for [${library_abis[*]}], expected [$expected_library_abis]"

  jq -nc \
    --arg file "$file" \
    --arg variant "$variant" \
    --arg versionName "$apk_version_name" \
    --argjson versionCode "$apk_version_code" \
    --argjson size "$(file_size "$apk")" \
    --arg sha256 "$(sha256 "$apk")" \
    --argjson signed "$signed" \
    --argjson signerCertSha256 "$(json_strings "${signers[@]}")" \
    --slurpfile goLibraries "$work/apk-libraries.jsonl" \
    '{file: $file, variant: $variant, versionName: $versionName, versionCode: $versionCode, size: $size, sha256: $sha256, signed: $signed, signerCertSha256: $signerCertSha256, goLibraries: $goLibraries}' \
    >> "$work/apks.jsonl"
  echo "$file versionCode $apk_version_code sha256 $(sha256 "$apk") signed $signed ${signers[*]}"
done
cp "$outputs/output-metadata.json" "$stage/output-metadata.json"

image_os="${ImageOS:-}"
image_version="${ImageVersion:-}"
run_url=""
if [ -n "${GITHUB_RUN_ID:-}" ]; then
  run_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/$GITHUB_RUN_ID/attempts/${GITHUB_RUN_ATTEMPT:-1}"
fi

manifest="$stage/bedlam-v$version_name-provenance.json"
jq -n \
  --arg repository "${GITHUB_REPOSITORY:-}" \
  --arg commit "$(git rev-parse HEAD)" \
  --arg ref "${GITHUB_REF:-}" \
  --arg versionName "$version_name" \
  --argjson versionCode "$version_code" \
  --argjson trackedTreeClean "$tracked_tree_clean" \
  --arg coreUrl "$(git config -f .gitmodules submodule.hysteria/upstream.url)" \
  --arg coreGitlink "$(git rev-parse HEAD:hysteria/upstream)" \
  --arg coreHead "$(git -C hysteria/upstream rev-parse HEAD)" \
  --argjson coreTags "$(json_strings "${core_tags[@]}")" \
  --arg coreDir "$core_source" \
  --arg extrasDir "$extras_source" \
  --arg go "$go_version" \
  --arg golangOrgXMobile "$mobile_version" \
  --arg ndk "$ndk_revision" \
  --arg java "$java_version" \
  --arg javaVendor "$java_vendor" \
  --arg gradle "$gradle_version" \
  --arg androidGradlePlugin "$(catalog_value agp)" \
  --arg kotlin "$(catalog_value kotlin)" \
  --arg verifierBuildTools "$(basename "$build_tools_dir")" \
  --arg gomobile "${tool_module[gomobile]}" \
  --arg gomobileBuiltBy "${tool_built_by[gomobile]}" \
  --arg gobind "${tool_module[gobind]}" \
  --arg gobindBuiltBy "${tool_built_by[gobind]}" \
  --arg runnerImageOs "$image_os" \
  --arg runnerImageVersion "$image_version" \
  --arg workflow "${GITHUB_WORKFLOW:-}" \
  --arg run "$run_url" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson aarSize "$(file_size "$aar")" \
  --arg aarSha256 "$aar_sha256" \
  --slurpfile aarLibraries "$work/aar-libraries.jsonl" \
  --argjson signingRequired "$release_build" \
  --arg expectedCertSha256 "$expected_cert" \
  --argjson allSigned "$all_signed" \
  --slurpfile apks "$work/apks.jsonl" \
  --argjson metadataSize "$(file_size "$stage/output-metadata.json")" \
  --arg metadataSha256 "$(sha256 "$stage/output-metadata.json")" \
  '{
    schemaVersion: 1,
    app: {repository: $repository, commit: $commit, ref: $ref, versionName: $versionName, versionCode: $versionCode, trackedTreeClean: $trackedTreeClean},
    hysteriaCore: {url: $coreUrl, gitlink: $coreGitlink, head: $coreHead, tags: $coreTags, clean: true, coreDir: $coreDir, extrasDir: $extrasDir},
    toolchain: {go: $go, golangOrgXMobile: $golangOrgXMobile, gomobile: {module: $gomobile, builtBy: $gomobileBuiltBy}, gobind: {module: $gobind, builtBy: $gobindBuiltBy}, ndk: $ndk, java: $java, javaVendor: $javaVendor, gradle: $gradle, androidGradlePlugin: $androidGradlePlugin, kotlin: $kotlin, verifierBuildTools: $verifierBuildTools, runnerImage: {os: $runnerImageOs, version: $runnerImageVersion}},
    build: {workflow: $workflow, run: $run, builtAt: $builtAt},
    golib: {file: "hysteria/libs/golib.aar", size: $aarSize, sha256: $aarSha256, libraries: $aarLibraries},
    signing: {required: $signingRequired, expectedCertSha256: $expectedCertSha256, allSigned: $allSigned},
    apks: $apks,
    outputMetadata: {file: "output-metadata.json", size: $metadataSize, sha256: $metadataSha256}
  }' > "$manifest"
echo "Wrote $manifest"
