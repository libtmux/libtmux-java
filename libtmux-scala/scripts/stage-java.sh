#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"
if [[ -n "${LIBTMUX_JAVA_REPOSITORY:-}" && "$LIBTMUX_JAVA_REPOSITORY" != /* ]]; then
    echo 'LIBTMUX_JAVA_REPOSITORY must be an absolute local directory, not a URL' >&2
    exit 2
fi
version_args=()
if [[ -n "${LIBTMUX_JAVA_VERSION:-}" ]]; then
    version_args+=("-PlibtmuxVersion=$LIBTMUX_JAVA_VERSION")
fi
exec ./gradlew --console=plain \
    --init-script libtmux-scala/scripts/stage-java.init.gradle.kts \
    "${version_args[@]}" \
    :libtmux:publishAllPublicationsToScalaDevRepository \
    :libtmux-junit5:publishAllPublicationsToScalaDevRepository \
    :libtmux-jackson:publishAllPublicationsToScalaDevRepository
