#!/bin/sh
set -eu

if [ "$#" -eq 0 ]; then
    printf 'Usage: sh run.sh program [arguments...]\n' >&2
    exit 2
fi
binary=$(command -v "${TMUX_BIN:-tmux}")
root=${API_RUN_ROOT:-/tmp/libtmux-java-dev}

# A client that names no socket follows TMUX or LIBTMUX_SOCKET*, and otherwise lands on the default
# socket under TMUX_TMPDIR. The program gets none of them, and a TMUX_TMPDIR of its own below.
unset TMUX TMUX_PANE
for name in $(env | sed -n 's/^\(LIBTMUX_SOCKET[A-Za-z0-9_]*\)=.*/\1/p'); do
    unset "$name"
done

mkdir -p "$root"
directory=$(mktemp -d "$root/api.XXXXXX")
tmpdir=$(mktemp -d "$root/tmpdir.XXXXXX")
socket="$directory/tmux.sock"
config="$directory/tmux.conf"

serves() {
    "$binary" -S "$1" display-message -p '#{pid}' >/dev/null 2>&1
}

# Stops the server on the socket, then waits for it to stop answering.
stop() {
    "$binary" -S "$1" kill-server || return 1
    attempts=0
    while serves "$1" && [ "$attempts" -lt 60 ]; do
        sleep 0.05
        attempts=$((attempts + 1))
    done
    ! serves "$1"
}

cleanup() {
    status=$?
    trap - 0 HUP INT TERM PIPE
    # A closed output must not end the cleanup, whose messages are the only thing left to write.
    trap '' PIPE
    set +e
    stuck=
    left=
    if [ -S "$socket" ] && serves "$socket" && ! stop "$socket"; then
        stuck="$stuck tmux.sock"
    fi
    # Every socket anywhere under either directory, without following links. Only one a server answers
    # on is a leak; the runner's own is expected to be there.
    old_ifs=$IFS
    IFS='
'
    set -f
    for candidate in $(find "$tmpdir" "$directory" -type s 2>/dev/null); do
        [ "$candidate" = "$socket" ] && continue
        serves "$candidate" || continue
        case "$candidate" in
            "$tmpdir"/*) name=${candidate#"$tmpdir"/} ;;
            *) name=${candidate#"$directory"/} ;;
        esac
        left="$left $name"
        stop "$candidate" || stuck="$stuck $name"
    done
    set +f
    IFS=$old_ifs
    if [ -n "$stuck" ]; then
        printf 'tmux still answers on%s after kill-server; kept %s and %s under the runner root\n' "$stuck" "${directory##*/}" "${tmpdir##*/}" >&2
        exit 1
    fi
    rm -rf "$directory" "$tmpdir" || exit 1
    if [ -n "$left" ]; then
        printf 'The program left a tmux server running:%s\n' "$left" >&2
        exit 1
    fi
    exit "$status"
}
trap cleanup 0
trap 'exit 1' HUP INT TERM PIPE

# A default socket path longer than this does not fit in sun_path.
default_socket="$tmpdir/tmux-$(id -u)/default"
if [ "$(printf '%s' "$default_socket" | wc -c)" -gt 100 ]; then
    printf 'API_RUN_ROOT is too long for a tmux socket\n' >&2
    exit 1
fi
TMUX_TMPDIR=$tmpdir
export TMUX_TMPDIR

printf 'set-option -g default-shell /bin/sh\n' > "$config"
"$binary" -S "$socket" -f "$config" new-session -d -s work-one -n editor /bin/cat
"$binary" -S "$socket" split-window -h -t '=work-one:editor' /bin/cat
"$binary" -S "$socket" new-window -d -t '=work-one' -n logs /bin/cat
"$binary" -S "$socket" new-session -d -s work-two -n editor /bin/cat
"$@" "$binary" "$socket" "$config"
"$binary" -S "$socket" has-session -t '=work-one'
"$binary" -S "$socket" has-session -t '=work-two'
