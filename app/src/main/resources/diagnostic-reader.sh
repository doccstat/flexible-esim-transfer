# Run only by the user-started recorder. No log files, property changes or app operations.
[ "$(/system/bin/id -u)" = 0 ] || exit 1
case "$1" in ''|*[!0-9,]*) exit 2 ;; esac
/system/bin/logcat -d -t 1 -v brief -b main -b system -b crash --uid="$1" '*:V' >/dev/null 2>&1 || exit 1
exec 3<&0
/system/bin/logcat -T 1 -v brief -b main -b system -b crash --uid="$1" '*:V' &
reader=$!
watcher=''
cleanup() {
    if [ -n "$reader" ]; then
        kill "$reader" 2>/dev/null
        wait "$reader" 2>/dev/null
    fi
    if [ -n "$watcher" ]; then
        kill "$watcher" 2>/dev/null
        wait "$watcher" 2>/dev/null
    fi
}
trap cleanup EXIT
trap 'exit 0' HUP INT TERM
# EOF also stops the exact child when the app dies. The outer timeout is a second bound.
( IFS= read -r stop <&3; kill "$reader" 2>/dev/null ) &
watcher=$!
printf '%s\n' FET_READER_STARTED
wait "$reader"
result=$?
reader=''
exit "$result"
