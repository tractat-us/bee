#!/bin/zsh
# usage: beecall.sh <method> <params-json-file-or-inline>
S=${0:a:h}; U=${BEE_URL:-'http://127.0.0.1:8233/mcp?name=agent'}
SID=$(cat $S/bee-sid 2>/dev/null)
if [[ -z $SID ]]; then
  curl -s -D $S/hdr.txt -m 10 -X POST "$U" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"kuilt-session","version":"1"}}}' >/dev/null
  SID=$(grep -i mcp-session-id $S/hdr.txt | awk '{print $2}' | tr -d '\r'); echo $SID > $S/bee-sid
  curl -s -m 10 -X POST "$U" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
fi
P=$2; [[ -f $P ]] && P=$(cat $P)
curl -s -m 60 -X POST "$U" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SID" -d "{\"jsonrpc\":\"2.0\",\"id\":$RANDOM$RANDOM,\"method\":\"$1\",\"params\":$P}"
