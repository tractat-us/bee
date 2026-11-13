#!/bin/zsh
# Identity-map half of live-loop.sh, standalone: redraw the identity-map surface every 2 min.
# Needs beecall.sh, live-id.py, id-estimates.json and (optionally) id-overrides.json beside it.
# The surface must already be open under THIS bee session. A surface id is used once, and one
# opened by another session (or before a re-initialise) renders grey, so open a fresh id:
#   export ID_SURFACE=identity-map-2
#   python3 live-id.py && python3 -c "import json;d=json.load(open('live-id.json'));d['name']='bee_surface_open';d['arguments']['title']='identity #3159: live dependency map';json.dump(d,open('id-open.json','w'))" && ./beecall.sh tools/call id-open.json
# then run this loop with the same ID_SURFACE in its environment.
S=${0:a:h}
while true; do
  if python3 $S/live-id.py 2>>$S/live-id.err; then
    $S/beecall.sh tools/call $S/live-id.json >/dev/null 2>&1 || rm -f $S/bee-sid
  fi
  sleep 120
done
