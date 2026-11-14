import json, runpy, sys
src=open(sys.argv[1]).read()
# reuse the calls list from mkcalls.py
ns={}; exec(src.split("comps=[")[0], ns); calls=ns['calls']
short={
 'topo':"Topology: when a child's name is broken, hold back the problems beneath it until it's fixed?",
 'l0':"Task C's scale run failed. Land the ledger edge index as its own task (L0) before L1?",
 'explain':"Move explain() ('why is X held?') to right after L1, instead of last?",
 'otel':"Add :kuilt-heddle-otel (#3184): metrics, a span per lease, hold reasons. When?",
 'emit':"How often should hold reasons be emitted?",
}
comps=[{"id":"root","component":"Column","children":["intro"]+[f"{c[0]}-card" for c in calls]},
       {"id":"intro","component":"Text","text":"**Calls waiting on you, heddle #3173.** Click an option to answer (★ marks my recommendation). Click **More** for the tradeoff."}]
for cid,title,md,opts in calls:
    btns=[]
    for oid,lab in opts:
        bid=f"{cid}-{oid}"
        comps.append({"id":bid,"component":"Button","child":f"{bid}-l","action":{"event":{"name":"decide","context":{"call":cid,"option":oid,"label":lab}}}})
        comps.append({"id":f"{bid}-l","component":"Text","text":lab})
        btns.append(bid)
    comps += [
      {"id":f"{cid}-card","component":"Card","child":f"{cid}-col"},
      {"id":f"{cid}-col","component":"Column","children":[f"{cid}-h",f"{cid}-q",f"{cid}-row",f"{cid}-st"]},
      {"id":f"{cid}-h","component":"Text","variant":"h5","text":title},
      {"id":f"{cid}-q","component":"Text","text":short[cid]},
      {"id":f"{cid}-row","component":"Row","children":btns+[f"{cid}-more"]},
      {"id":f"{cid}-more","component":"Modal","trigger":f"{cid}-mbtn","content":f"{cid}-md"},
      {"id":f"{cid}-mbtn","component":"Button","child":f"{cid}-mbl","action":{"event":{"name":"more","context":{"call":cid}}}},
      {"id":f"{cid}-mbl","component":"Text","text":"More…"},
      {"id":f"{cid}-md","component":"Text","text":md},
      {"id":f"{cid}-st","component":"Text","variant":"caption","text":{"path":f"/status/{cid}"}},
    ]
print(json.dumps({"name":"bee_surface_open","arguments":{"surfaceId":"heddle-survey","title":"heddle: calls for you","components":comps,"data":{"status":{c[0]:"⏳ Waiting for your call." for c in calls}}}}))
