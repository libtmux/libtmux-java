#!/usr/bin/env sh
# Build every tmux release this project supports into a tree the matrix can use.
#
#   ./scripts/tmux-matrix.sh [destination]
#
# Then:
#   ./gradlew testTmuxMatrix -PlibtmuxMatrix="$destination"
#
# The lanes are read from the build rather than repeated here, so this cannot drift from what the
# matrix actually runs. CI builds the same releases one per runner; this is the same thing on one
# machine, which is slower and does not need a network round trip per lane.
set -eu

root="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:-$HOME/tmux-builds}"
plugin="$root/build-logic/src/main/kotlin/libtmux.tmux-matrix.gradle.kts"

lanes="$(sed -n 's/^val lanes = listOf(\(.*\))$/\1/p' "$plugin" | tr -d '" ' | tr ',' ' ')"
[ -n "$lanes" ] || { echo "could not read the lanes from $plugin" >&2; exit 1; }

echo "lanes: $lanes"
echo "destination: $destination"

for lane in $lanes; do
    if [ -x "$destination/$lane/bin/tmux" ]; then
        echo "== tmux $lane already built"
        continue
    fi
    echo "== building tmux $lane"
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    curl -fsSL -o "$work/tmux.tar.gz" \
        "https://github.com/tmux/tmux/releases/download/$lane/tmux-$lane.tar.gz"
    tar -xzf "$work/tmux.tar.gz" -C "$work"
    (
        cd "$work/tmux-$lane"
        ./configure --prefix="$destination/$lane" >/dev/null
        make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)" >/dev/null
        make install >/dev/null
    )
    rm -rf "$work"
    trap - EXIT
done

echo
for lane in $lanes; do
    printf '%-6s %s\n' "$lane" "$("$destination/$lane/bin/tmux" -V)"
done
echo
echo "./gradlew testTmuxMatrix -PlibtmuxMatrix=$destination"
