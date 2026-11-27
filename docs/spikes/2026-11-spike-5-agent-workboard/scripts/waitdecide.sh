#!/bin/zsh
# Wait for the next "decide" click on heddle-survey; print the record(s) and exit.
S=${0:a:h}; CUR=$(cat $S/bee-cursor 2>/dev/null)
for i in {1..170}; do
  if [[ -n $CUR ]]; then A="{\"name\":\"bee_wait_action\",\"arguments\":{\"surfaceId\":\"heddle-survey\",\"cursor\":\"$CUR\",\"timeoutSeconds\":50}}"
  else A='{"name":"bee_wait_action","arguments":{"surfaceId":"heddle-survey","timeoutSeconds":50}}'; fi
  R=$($S/beecall.sh tools/call "$A")
  if echo "$R" | grep -q 'Session not found'; then rm -f $S/bee-sid; continue; fi
  T=$(echo "$R" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['result']['content'][0]['text'])" 2>/dev/null)
  NC=$(echo "$T" | python3 -c "import json,sys;print(json.load(sys.stdin).get('cursor',''))" 2>/dev/null); [[ -n $NC ]] && { CUR=$NC; echo $CUR > $S/bee-cursor; }
  if echo "$T" | grep -q '"decide"'; then
    echo "$T" | python3 -c "import json,sys;c=json.load(sys.stdin)['record']['context'];print(c['call']);print(c['label'])" | { read CID; read LAB; python3 $S/decide_ui.py "$CID" ack "$LAB" > $S/ack.json; $S/beecall.sh tools/call $S/ack.json >/dev/null; }
    echo "$T"; exit 0; fi
done
echo "NO-CLICK-AFTER-~2.4h"
