#!/bin/zsh
# Long-running: handles dismiss clicks (survey) and drops into "dismissed" (board) on my surfaces, unattended.
S=${0:a:h}; CUR=$(cat $S/bee-cursor-all 2>/dev/null)
while true; do
  if [[ -n $CUR ]]; then A="{\"name\":\"bee_wait_action\",\"arguments\":{\"cursor\":\"$CUR\",\"timeoutSeconds\":50}}"; else A='{"name":"bee_wait_action","arguments":{"timeoutSeconds":50}}'; fi
  R=$($S/beecall.sh tools/call "$A")
  if echo "$R" | grep -q 'Session not found'; then rm -f $S/bee-sid; continue; fi
  T=$(echo "$R" | python3 -c "import json,sys;print(json.load(sys.stdin)['result']['content'][0]['text'])" 2>/dev/null) || { sleep 5; continue; }
  NC=$(echo "$T" | python3 -c "import json,sys;print(json.load(sys.stdin).get('cursor',''))" 2>/dev/null); [[ -n $NC ]] && { CUR=$NC; echo $CUR > $S/bee-cursor-all; }
  echo "$T" | python3 $S/handle.py "$S" && echo "$(date +%T) $T" >> $S/dismissd.log
  echo "$T" | grep -q '"decide"' && { echo "$T"; exit 0; }
done
