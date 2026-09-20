#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"
if [[ -n "${LIBTMUX_BOM_REPOSITORY:-}" && "$LIBTMUX_BOM_REPOSITORY" != /* ]]; then
    echo 'LIBTMUX_BOM_REPOSITORY must be an absolute local directory, not a URL' >&2
    exit 2
fi
version_args=()
if [[ -n "${LIBTMUX_JAVA_VERSION:-}" ]]; then
    version_args+=("-PlibtmuxVersion=$LIBTMUX_JAVA_VERSION")
fi
exec ./gradlew --console=plain \
    --init-script scala/scripts/stage-bom.init.gradle.kts \
    "${version_args[@]}" \
    :libtmux-bom:publishAllPublicationsToScalaBomRepository
