#!/usr/bin/env bash
# A rebuild under a running host keeps its last board save (bee#260).
#
#   e2e/rebuild-under-host.sh [--scripts <dir>]
#
# Starts a host through bin/bee-up, draws a surface on its board, rewrites the host install's
# bee-host.jar in place the way installDist does, then stops the host. It passes when the host's
# shutdown logged no NoClassDefFoundError and the next bee-up restores the surface.
#
# It never touches this checkout's own install: bee-up and bee-env run from a scratch checkout
# holding a copy of bee-host and bee-mcp's installs, with a scratch BEE_HOME, on a port the host
# picks. Build the installs first: ./gradlew :bee-host:installDist :bee-mcp:installDist.
# --scripts takes bee-up and bee-env from <dir> instead of bin/, to run the check against another
# revision of them.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
scripts=$repo/bin
while [ $# -gt 0 ]; do
    case $1 in
        --scripts) scripts=$(cd "$2" && pwd); shift 2 ;;
        -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "rebuild-under-host: unknown argument $1 (try --help)" >&2; exit 2 ;;
    esac
done
for install in bee-host bee-mcp; do
    [ -x "$repo/$install/build/install/$install/bin/$install" ] || {
        echo "rebuild-under-host: no $install install; run ./gradlew :bee-host:installDist :bee-mcp:installDist first." >&2
        exit 1
    }
done

java_home=${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.5.0+8-tem}

scratch=$(mktemp -d "${TMPDIR:-/tmp}/bee-rebuild-under-host.XXXXXX")
checkout=$scratch/checkout
export BEE_HOME=$scratch/home
mkdir -p "$checkout/bin" "$checkout/bee-host/build/install" "$checkout/bee-mcp/build/install"
cp "$scripts/bee-up" "$scripts/bee-env" "$checkout/bin/"
cp -R "$repo/bee-host/build/install/bee-host" "$checkout/bee-host/build/install/"
cp -R "$repo/bee-mcp/build/install/bee-mcp" "$checkout/bee-mcp/build/install/"
log=$BEE_HOME/host.log
pid=""

# Stops the host this check started, by the pid it recorded, whatever happened.
finish() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid"; fi
    rm -rf "$scratch"
}
trap finish EXIT
fail() {
    echo "FAIL: $1" >&2
    if [ -f "$log" ]; then echo "--- $log, last lines:" >&2; tail -25 "$log" >&2; fi
    exit 1
}

up() {
    "$checkout/bin/bee-up" --local --no-open --port 0 > "$scratch/bee-up.out" 2>&1 || {
        cat "$scratch/bee-up.out" >&2
        fail "bee-up didn't start the host"
    }
    pid=$(cat "$BEE_HOME/host.pid")
    port=$(sed -n 's/.*bee host on http:\/\/[^:]*:\([0-9]*\)\/.*/\1/p' "$log" | tail -1)
}

# One JSON-RPC message to the host's MCP endpoint, as a Claude with nothing installed sends it.
session=""
mcp() {
    curl -sS -m 30 -D "$scratch/headers" -o "$scratch/answer" "http://127.0.0.1:$port/mcp?name=rebuild-check" \
        -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' \
        -H 'mcp-protocol-version: 2025-06-18' ${session:+-H "mcp-session-id: $session"} --data "$1"
    if [ -z "$session" ]; then
        session=$(sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: *\([^[:space:]]*\).*/\1/p' "$scratch/headers")
    fi
}

up
echo "Host up (pid $pid, port $port), running $(ps -o command= -p "$pid" | sed 's/.* -classpath \([^:]*\)\/lib\/.*/\1/')"
mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"rebuild-check","version":"0"}}}'
[ -n "$session" ] || fail "the host's MCP endpoint gave no session"
mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}'
mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"bee_surface_open","arguments":{"surfaceId":"rebuilt","title":"Drawn before the rebuild","components":[{"id":"root","component":"Text","text":"Still here"}]}}}'
grep -q '"opened":"rebuilt"' "$scratch/answer" || { cat "$scratch/answer" >&2; fail "bee_surface_open was refused"; }

# installDist writes a changed jar over the old one, in place: the same file, new bytes. A repack
# of the same classes moves every entry, as a real rebuild does.
jar=$checkout/bee-host/build/install/bee-host/lib/bee-host.jar
mkdir "$scratch/classes"
(cd "$scratch/classes" && "$java_home/bin/jar" xf "$jar")
"$java_home/bin/jar" --create --file "$scratch/rebuilt.jar" --no-compress -C "$scratch/classes" .
cmp -s "$scratch/rebuilt.jar" "$jar" && fail "the rebuilt jar is the same bytes as the old one"
cat "$scratch/rebuilt.jar" > "$jar"

"$checkout/bin/bee-up" --stop > /dev/null
pid=""
if grep -q -e NoClassDefFoundError -e 'Exception in thread' "$log"; then
    fail "the host's shutdown died after the rebuild, so its last save never ran"
fi
cp "$log" "$scratch/first-host.log"

up
restored=$(grep 'board .* restored as ' "$log" | tail -1)
case $restored in
    *": 1 surfaces"*) ;;
    *) fail "the next host didn't restore the surface drawn before the rebuild: ${restored:-no restore line}" ;;
esac
"$checkout/bin/bee-up" --stop > /dev/null
pid=""
echo "PASS: the host stopped cleanly after a rebuild under it, and the next one restored the board."
