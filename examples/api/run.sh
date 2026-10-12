#!/bin/sh
set -eu

if [ "$#" -eq 0 ]; then
    printf 'Usage: sh run.sh program [arguments...]\n' >&2
    exit 2
fi
binary=$(command -v "${TMUX_BIN:-tmux}")
mkdir -p /tmp/libtmux-java-dev
directory=$(mktemp -d /tmp/libtmux-java-dev/api.XXXXXX)
socket="$directory/tmux.sock"
config="$directory/tmux.conf"

alive() {
    "$binary" -S "$socket" display-message -p '#{pid}' >/dev/null 2>&1
}

cleanup() {
    status=$?
    trap - 0 HUP INT TERM
    if alive; then
        if ! "$binary" -S "$socket" kill-server; then
            printf 'Cannot stop tmux; kept %s\n' "$directory" >&2
            exit 1
        fi
        attempts=0
        while alive && [ "$attempts" -lt 100 ]; do
            sleep 0.05
            attempts=$((attempts + 1))
        done
        if alive; then
            printf 'tmux is still responding; kept %s\n' "$directory" >&2
            exit 1
        fi
    fi
    rm -rf "$directory" || exit 1
    exit "$status"
}
trap cleanup 0
trap 'exit 1' HUP INT TERM

unset TMUX TMUX_PANE
printf 'set-option -g default-shell /bin/sh\n' > "$config"
"$binary" -S "$socket" -f "$config" \
    new-session -d -s work-one -n editor /bin/cat
"$binary" -S "$socket" split-window -h -t '=work-one:editor' /bin/cat
"$binary" -S "$socket" new-window -d -t '=work-one' -n logs /bin/cat
"$binary" -S "$socket" new-session -d -s work-two -n editor /bin/cat
"$@" "$binary" "$socket" "$config"
"$binary" -S "$socket" has-session -t '=work-one'
"$binary" -S "$socket" has-session -t '=work-two'
