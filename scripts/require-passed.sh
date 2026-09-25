#!/usr/bin/env sh
# Refuse to release a commit until its own evidence has passed.
#
#   ./scripts/require-passed.sh <commit> ci.yml tmux-matrix.yml
#   ./scripts/require-passed.sh <commit> scala-matrix.yml:"Required checks"
#
# A release job builds and checks on one runner. What the commit claims is wider: both JDKs and
# macOS in CI, every supported tmux in the matrix, installed consumers in the Scala matrix. Each
# named workflow must have a successful run for exactly this commit. Nothing is re-run here: a
# workflow still running, or never run for this commit, fails the release, and the release is
# started again once it has passed.
#
# A bare workflow file accepts any successful run of it, which only proves what the run's overall
# conclusion already says. That is exactly what a workflow_dispatch skipping a routine job through
# an `if:` condition can still report success for. A workflow with a job like that names a check
# run instead, after "workflow.yml:" — only that named check run's own conclusion is trusted, which
# cannot happen while the job it aggregates was skipped or never ran.
set -eu

commit=$1
shift
for workflow in "$@"; do
    case $workflow in
        *:*)
            file=${workflow%%:*}
            check_name=${workflow#*:}
            found=$(gh api --paginate "repos/$GH_REPO/commits/$commit/check-runs" \
                --jq --arg name "$check_name" \
                '[.check_runs[] | select(.name == $name and .conclusion == "success")] | length')
            if [ "$found" -lt 1 ]; then
                echo "::error::$file: no successful '$check_name' check run for $commit;" \
                    "let it pass, then release again" >&2
                exit 1
            fi
            echo "$file: '$check_name' passed on $commit"
            ;;
        *)
            passed=$(gh run list --workflow "$workflow" --commit "$commit" --limit 50 \
                --json conclusion --jq 'map(select(.conclusion == "success")) | length')
            if [ "$passed" -lt 1 ]; then
                echo "::error::$workflow has no successful run for $commit; let it pass, then release again" >&2
                exit 1
            fi
            echo "$workflow passed on $commit"
            ;;
    esac
done
