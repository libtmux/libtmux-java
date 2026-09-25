#!/usr/bin/env sh
# Exercises require-passed.sh against a stub `gh`, so the case a workflow_dispatch that skips a
# routine job cannot fake — a named check run that never concluded success — is covered without a
# network call or a real repository.
#
#   ./scripts/require-passed.test.sh
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
stub=$(mktemp -d)
trap 'rm -rf "$stub"' EXIT

# Reports the count require-passed.sh's own jq filter would have reported, so the case under test
# controls only what a real gh would have answered, not the script's parsing of it.
cat > "$stub/gh" <<'STUB'
#!/usr/bin/env sh
set -eu
case "$1 $2" in
    "api --paginate") echo "${GH_STUB_CHECK_RUNS_FOUND:?}" ;;
    "run list") echo "${GH_STUB_RUNS_PASSED:?}" ;;
    *)
        echo "stub gh: unexpected invocation: $*" >&2
        exit 1
        ;;
esac
STUB
chmod +x "$stub/gh"

export PATH="$stub:$PATH"
export GH_REPO="example/example"

failures=0

# check_runs_found run_list_passed workflow_arg expected_exit description
check() {
    result=0
    GH_STUB_CHECK_RUNS_FOUND=$1 GH_STUB_RUNS_PASSED=$2 \
        "$here/require-passed.sh" deadbeef "$3" >/dev/null 2>&1 || result=$?
    if [ "$result" -ne "$4" ]; then
        echo "FAIL: $5 (expected exit $4, got $result)" >&2
        failures=$((failures + 1))
    else
        echo "ok: $5"
    fi
}

check 1 0 'scala-matrix.yml:Required checks' 0 \
    "a named check run that concluded success passes"

# The case a bare workflow-run conclusion cannot see: the aggregate job was skipped, so no check
# run by that name ever concluded success, even though the workflow's own run can still succeed.
check 0 0 'scala-matrix.yml:Required checks' 1 \
    "a missing or skipped named check run fails"

check 0 1 'ci.yml' 0 \
    "a bare workflow name still accepts any successful run"

check 0 0 'ci.yml' 1 \
    "a bare workflow name with no successful run still fails"

if [ "$failures" -ne 0 ]; then
    echo "$failures of 4 checks failed" >&2
    exit 1
fi
echo "4 of 4 checks passed"
