#!/usr/bin/env bash
# Checks a staged release against what the Central Portal refuses: every artifact the BOM manages
# is staged with its jar, sources and javadoc, each file signed and checksummed, and each POM carries
# the metadata Central requires. The signing key's public half must be in the keyring.
set -euo pipefail

version=${1:?usage: verify-staged-release.sh <version> [staging-repository]}
repo=${2:-build/staging-repository}/io/github/libtmux
failed=0
problem() {
    echo "::error::$*"
    failed=1
}

bom="$repo/libtmux-bom/$version/libtmux-bom-$version.pom"
[ -f "$bom" ] || { echo "::error::no staged BOM at $bom"; exit 1; }
artifacts=$(sed -n 's:.*<artifactId>\(libtmux[^<]*\)</artifactId>.*:\1:p' "$bom" | sort -u)

for artifact in $artifacts; do
    dir="$repo/$artifact/$version"
    pom="$dir/$artifact-$version.pom"
    [ -f "$pom" ] || { problem "$artifact: not staged"; continue; }

    files="$artifact-$version.pom"
    if ! grep -q '<packaging>pom</packaging>' "$pom"; then
        files="$files $artifact-$version.jar $artifact-$version-sources.jar $artifact-$version-javadoc.jar"
    fi
    for file in $files; do
        [ -f "$dir/$file" ] || { problem "$artifact: missing $file"; continue; }
        for suffix in asc md5 sha1; do
            [ -f "$dir/$file.$suffix" ] || problem "$artifact: missing $file.$suffix"
        done
        if [ -f "$dir/$file.sha1" ] && [ "$(cut -d' ' -f1 < "$dir/$file.sha1")" != "$(sha1sum < "$dir/$file" | cut -d' ' -f1)" ]; then
            problem "$artifact: $file.sha1 does not match"
        fi
        if [ -f "$dir/$file.asc" ] && ! gpg --batch --verify "$dir/$file.asc" "$dir/$file" 2>/dev/null; then
            problem "$artifact: $file.asc does not verify"
        fi
    done

    for element in '<name>' '<description>' '<url>' '<license>' '<developer>' '<scm>' '<connection>'; do
        grep -q "$element" "$pom" || problem "$artifact: POM has no $element"
    done
    if grep -q -- '-SNAPSHOT<' "$pom"; then
        problem "$artifact: POM names a snapshot"
    fi

    if [ -f "$dir/$artifact-$version-javadoc.jar" ]; then
        grep -qx 'index.html' <<< "$(unzip -Z1 "$dir/$artifact-$version-javadoc.jar")" \
            || problem "$artifact: javadoc jar has no index.html"
    fi
    if [ -f "$dir/$artifact-$version-sources.jar" ]; then
        grep -qE '\.(java|kt|scala)$' <<< "$(unzip -Z1 "$dir/$artifact-$version-sources.jar")" \
            || problem "$artifact: sources jar holds no source"
    fi
done

[ "$failed" -eq 0 ] && echo "$(echo "$artifacts" | wc -w) artifacts staged as Central requires them"
exit "$failed"
