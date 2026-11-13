#!/bin/zsh
# kuilt-d5's identity-map loop. bee closes an MCP session after 2 min of quiet and the surface
# then belongs to a dead session, so: a presence heartbeat every 50 s, a redraw every ~2 min, and
# a reply that is a JSON-RPC error (curl exits 0 on those) means re-initialise, open a fresh
# surface id, and close the old one.
S=${0:a:h}
export BEE_URL=${BEE_URL:-'http://127.0.0.1:8233/mcp?name=kuilt-d5'}
N=$(cat $S/surface-n 2>/dev/null || echo 3)
call() { local out; out=$($S/beecall.sh tools/call "$1" 2>&1); [[ $out == *'"result"'* ]] }
open_surface() {
  export ID_SURFACE=identity-map-$N
  python3 $S/live-id.py 2>>$S/live-id.err || return 1
  python3 -c "import json;d=json.load(open('$S/live-id.json'));d['name']='bee_surface_open';d['arguments']['title']='identity #3159 (kuilt-d5): live dependency map';json.dump(d,open('$S/id-open.json','w'))"
  call $S/id-open.json && echo $N > $S/surface-n && echo "$(date) opened $ID_SURFACE" >> $S/loop.log
}
reopen() {
  local old=identity-map-$N; rm -f $S/bee-sid; N=$((N+1))
  open_surface && call "{\"name\":\"bee_surface_close\",\"arguments\":{\"surfaceId\":\"$old\"}}"
}
export ID_SURFACE=identity-map-$N
[[ -f $S/surface-n ]] || open_surface
i=0
while true; do
  call '{"name":"bee_presence","arguments":{"status":"kuilt #3159 identity: keeping identity-map current"}}' || reopen
  if (( i % 3 == 0 )) && python3 $S/live-id.py 2>>$S/live-id.err; then
    call $S/live-id.json || reopen
  fi
  i=$((i+1)); sleep 50
done
