#!/usr/bin/env sh
# End tmux servers this port abandoned, and report what belongs to somebody else.
#
#   ./scripts/reap-stale-servers.sh          # say what would be reaped
#   ./scripts/reap-stale-servers.sh --reap   # actually reap it
#
# The suite does this for itself — see docs/spikes/22 — but a server whose test JVM was killed and
# whose socket the temporary-file cleaner has since removed is reachable by nothing except its own
# argv, and nothing reaps it until the next run. This is that, by hand.
#
# Only sockets under this port's roots are ever touched. Sibling libtmux ports run on this machine
# and their leftovers are not ours to reap, however much they cost us; AGENTS.md explains why.
set -eu

reap=false
[ "${1:-}" = "--reap" ] && reap=true

ours=0
theirs=0

for pid in $(pgrep tmux 2>/dev/null || true); do
    cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null) || continue
    socket=$(printf '%s' "$cmdline" | sed -n 's/.*-S \([^ ]*\).*/\1/p')
    [ -n "$socket" ] || continue

    case "$socket" in
        /tmp/libtmux-java-test/* | /tmp/libtmux-java-dev/*)
            ours=$((ours + 1))
            if [ "$reap" = true ]; then
                # kill-server when the socket is still there, a signal when it is not; a server whose
                # socket was unlinked cannot be reached any other way.
                if [ -S "$socket" ]; then
                    tmux -S "$socket" kill-server 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
                else
                    kill -TERM "$pid" 2>/dev/null || true
                fi
                echo "reaped  $pid  $socket"
            else
                echo "would reap  $pid  $socket"
            fi
            ;;
        *) theirs=$((theirs + 1)) ;;
    esac
done

echo
echo "ours: $ours"
echo "other ports': $theirs  (not this script's to touch)"
[ "$reap" = true ] || [ "$ours" -eq 0 ] || echo
[ "$reap" = true ] || [ "$ours" -eq 0 ] || echo "re-run with --reap to end them"
