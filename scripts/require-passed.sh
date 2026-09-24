#!/usr/bin/env sh
# Refuse to release a commit until its own evidence has passed.
#
#   ./scripts/require-passed.sh <commit> ci.yml tmux-matrix.yml
#
# A release job builds and checks on one runner. What the commit claims is wider: both JDKs and
# macOS in CI, every supported tmux in the matrix, installed consumers in the Scala matrix. Each
# named workflow must have a successful run for exactly this commit. Nothing is re-run here: a
# workflow still running, or never run for this commit, fails the release, and the release is
# started again once it has passed.
set -eu

commit=$1
shift
for workflow in "$@"; do
    passed=$(gh run list --workflow "$workflow" --commit "$commit" --limit 50 \
        --json conclusion --jq 'map(select(.conclusion == "success")) | length')
    if [ "$passed" -lt 1 ]; then
        echo "::error::$workflow has no successful run for $commit; let it pass, then release again" >&2
        exit 1
    fi
    echo "$workflow passed on $commit"
done
