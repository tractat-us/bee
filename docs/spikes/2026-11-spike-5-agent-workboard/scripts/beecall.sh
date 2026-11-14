#!/bin/zsh
# usage: beecall.sh <method> <params-json-file-or-inline>
#
# One MCP JSON-RPC call to $BEE_URL. Opens a session on first use and caches its id in
# $BEE_SID_FILE (default: bee-sid beside this script; give each loop its own, or two loops race to
# re-initialise one file). Prints the reply. Exit status:
#   0  the reply carries "result"
#   3  the host answered 404: the session is gone (it closes one quiet for 2 min). The cached id is
#      deleted, so the next call opens a new session, which is a new peer: surfaces opened under
#      the old one render grey and must be reopened.
#   2  any other reply without "result" (a JSON-RPC error, an empty body, curl failing)
# Testing curl's own exit code is not enough: it is 0 on a 404 with a JSON error body.
S=${0:a:h}; U=${BEE_URL:-'http://127.0.0.1:8233/mcp?name=agent'}
F=${BEE_SID_FILE:-$S/bee-sid}
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
SID=$(cat $F 2>/dev/null)
if [[ -z $SID ]]; then
  hdr=$(mktemp)
  curl -s -D $hdr -m 10 -X POST "$U" $H -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"bee-spike-script","version":"1"}}}' >/dev/null
  SID=$(grep -i mcp-session-id $hdr | awk '{print $2}' | tr -d '\r'); rm -f $hdr
  [[ -z $SID ]] && { echo '{"error":"initialize returned no session id"}'; exit 2; }
  echo $SID > $F
  curl -s -m 10 -X POST "$U" $H -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
fi
P=$2; [[ -f $P ]] && P=$(cat $P)
# Each request gets a random id: a fixed one collides with a concurrent long-poll.
out=$(curl -s -m 60 -w '\n%{http_code}' -X POST "$U" $H -H "Mcp-Session-Id: $SID" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":$RANDOM$RANDOM,\"method\":\"$1\",\"params\":$P}")
code=${out##*$'\n'}; body=${out%$'\n'*}
print -r -- "$body"
if [[ $code == 404 ]]; then rm -f $F; exit 3; fi
[[ $body == *'"result"'* ]] || exit 2
