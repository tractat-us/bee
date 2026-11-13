#!/bin/zsh
# Refresh the map every 2 min. A live.py failure (e.g. an empty gh reply) skips one
# refresh; only a failed bee call drops the session id.
S=${0:a:h}
while true; do
  $S/beecall.sh tools/call '{"name":"bee_presence","arguments":{"status":"kuilt heddle #3173: keeping the live map current"}}' >/dev/null 2>&1
  if python3 $S/live.py 2>>$S/live.err; then
    $S/beecall.sh tools/call $S/live.json >/dev/null 2>&1 || rm -f $S/bee-sid
  fi
  sleep 120
done
