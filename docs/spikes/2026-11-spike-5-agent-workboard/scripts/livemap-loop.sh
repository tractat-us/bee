#!/bin/zsh
# usage: livemap-loop.sh SPEC.json [--overrides OV.json]    keep the map live until stopped
#        livemap-loop.sh SPEC.json close                    close the map this loop last opened
#
# Redraws the map every $LIVEMAP_EVERY seconds (default 60). The host closes an MCP session quiet
# for 2 minutes, so the interval must stay well under that: a 120 s sleep lost the race and froze a
# map for an evening. When a call fails anyway (beecall.sh exits non-zero, which a 404 "Session not
# found" now does), the loop opens a new session. That is a new peer, and the board shows surfaces
# of the dead one grey, so it reopens the map under the next surface id and closes the stale one.
# On Ctrl-C or TERM it closes its surface.
#
# Env: BEE_URL (the board's MCP endpoint), LIVEMAP_SURFACE (surface id prefix; default the spec's
# "surface"), LIVEMAP_TITLE (the surface's title; default the spec's "title"), LIVEMAP_STATE (a
# directory for the session id, surface counter and log; default SPEC.state beside the spec).
S=${0:a:h}; SPEC=${1:a}; shift
[[ -f $SPEC ]] || { echo "usage: livemap-loop.sh SPEC.json [--overrides OV.json | close]" >&2; exit 64; }
ST=${LIVEMAP_STATE:-${SPEC:r}.state}; mkdir -p $ST
export BEE_SID_FILE=$ST/sid
PREFIX=${LIVEMAP_SURFACE:-$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('surface','livemap'))" $SPEC)}
TITLE=${LIVEMAP_TITLE:-$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['title'])" $SPEC)}
EVERY=${LIVEMAP_EVERY:-60}
log() { print -r -- "$(date +%T) $*" >> $ST/loop.log }
call() { local out; out=$($S/beecall.sh tools/call "$1" 2>&1); local rc=$?; (( rc )) && log "call failed ($rc): ${out[1,300]}"; return $rc }
close_surface() { [[ -n $1 ]] && call "{\"name\":\"bee_surface_close\",\"arguments\":{\"surfaceId\":\"$1\"}}" && log "closed $1" }

if [[ $1 == close ]]; then close_surface "$(cat $ST/surface 2>/dev/null)" && rm -f $ST/surface; exit; fi

# A fresh session, the map under the next surface id, then the stale surface closed. The counter
# advances even when the open fails, since a surface id is used once.
open_fresh() {
  local old=$(cat $ST/surface 2>/dev/null) n=$(( $(cat $ST/n 2>/dev/null || echo 0) + 1 ))
  echo $n > $ST/n; rm -f $BEE_SID_FILE
  python3 $S/livemap.py $SPEC "$@" --surface $PREFIX-$n --open "$TITLE" > $ST/open.json 2>>$ST/loop.log || return 1
  call $ST/open.json || return 1
  echo $PREFIX-$n > $ST/surface; log "opened $PREFIX-$n"
  [[ -n $old && $old != $PREFIX-$n ]] && close_surface $old
  return 0
}
trap 'close_surface "$(cat $ST/surface 2>/dev/null)" && rm -f $ST/surface; exit 0' INT TERM

open_fresh "$@"
while true; do
  sleep $EVERY
  if [[ ! -s $ST/surface ]]; then open_fresh "$@"; continue; fi
  if python3 $S/livemap.py $SPEC "$@" --surface $(cat $ST/surface) > $ST/update.json 2>>$ST/loop.log; then
    call $ST/update.json || open_fresh "$@"
  else
    # GitHub unreadable: keep the last map, and keep the session warm with a cheap request.
    $S/beecall.sh tools/list '{}' >/dev/null 2>&1 || open_fresh "$@"
  fi
done
