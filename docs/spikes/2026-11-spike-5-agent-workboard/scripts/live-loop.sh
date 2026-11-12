#!/bin/zsh
S=${0:a:h}
while true; do python3 $S/live.py 2>>$S/live.err && $S/beecall.sh tools/call $S/live.json >/dev/null 2>&1 || { rm -f $S/bee-sid; }; sleep 120; done
