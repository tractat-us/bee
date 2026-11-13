#!/bin/zsh
# The heddle map loop. bee closes an MCP session after 2 min of quiet, and curl exits 0 on a
# JSON-RPC "Session not found" reply, so: a presence heartbeat every 50 s, a redraw every ~150 s,
# and any reply without "result" means re-initialise, open the next surface id, close the old one.
# (Pattern from kuilt-d5's live-id-loop.sh.)
S=${0:a:h}
N=$(cat $S/surface-n 2>/dev/null || echo 2)
call() { local out; out=$($S/beecall.sh tools/call "$1" 2>&1); [[ $out == *'"result"'* ]] }
open_surface() {
  export HEDDLE_SURFACE=heddle-map-$N
  python3 $S/live.py 2>>$S/live.err || return 1
  python3 -c "import json;d=json.load(open('$S/live.json'));d['name']='bee_surface_open';d['arguments']['title']='heddle plan: live dependency map';json.dump(d,open('$S/map-open.json','w'))"
  call $S/map-open.json && echo $N > $S/surface-n && echo "$(date) opened $HEDDLE_SURFACE" >> $S/loop.log
}
reopen() {
  local old=heddle-map-$N; rm -f $S/bee-sid; N=$((N+1))
  open_surface && call "{\"name\":\"bee_surface_close\",\"arguments\":{\"surfaceId\":\"$old\"}}"
}
export HEDDLE_SURFACE=heddle-map-$N
[[ -f $S/surface-n ]] || echo $N > $S/surface-n
i=0
while true; do
  call '{"name":"bee_presence","arguments":{"status":"kuilt heddle #3173: keeping the live map current"}}' || reopen
  if (( i % 3 == 0 )) && python3 $S/live.py 2>>$S/live.err; then
    call $S/live.json || reopen
  fi
  i=$((i+1)); sleep 50
done
