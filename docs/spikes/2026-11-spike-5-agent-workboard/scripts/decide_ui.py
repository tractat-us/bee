# usage: decide_ui.py <cid> <state: ack|done> <label> [note]  -> prints bee_surface_update JSON
import json, sys, os
S=os.path.dirname(os.path.abspath(__file__)); f=os.path.join(S,'decided.json')
order=['topo','l0','explain','otel','emit']
dec=json.load(open(f)) if os.path.exists(f) else {}
cid,state,label=sys.argv[1],sys.argv[2],sys.argv[3]; note=sys.argv[4] if len(sys.argv)>4 else ''
if state=='dismiss': dec[cid]['dismissed']=True
elif cid!='-': dec[cid]={'state':state,'label':label,'note':note}; json.dump(dec,open(f,'w'))
comps=[]
for c,v in dec.items():
    head = "⏳ **Got it: “%s”**. Working on it…" % v['label'] if v['state']=='ack' else "✅ **Decided: “%s”**" % v['label']
    txt = head + ("\n\n" + v['note'] if v['note'] else '')
    comps.append({"id":f"{c}-q","component":"Text","text":txt})
    comps.append({"id":f"{c}-row","component":"Row","children":[f"{c}-more"]+([f"{c}-dis"] if v['state']=='done' else [])})
    comps.append({"id":f"{c}-dis","component":"Button","child":f"{c}-disl","action":{"event":{"name":"dismiss","context":{"card":c}}}})
    comps.append({"id":f"{c}-disl","component":"Text","text":"Dismiss"})
    comps.append({"id":f"{c}-st","component":"Text","variant":"caption","text":""})
pending=[c for c in order if c not in dec]; done=[c for c in order if c in dec and not dec[c].get('dismissed')]
kids=["intro"]+[f"{c}-card" for c in pending]+(["decided-h"] if done else [])+[f"{c}-card" for c in done]
intro = ("**%d call%s waiting on you, heddle #3173.** Click an option to answer (★ = my pick). **More…** shows the tradeoff." % (len(pending),'' if len(pending)==1 else 's')) if pending else "**All calls answered, thanks.** Progress shows on each card below."
comps += [{"id":"root","component":"Column","children":kids},
          {"id":"intro","component":"Text","text":intro},
          {"id":"decided-h","component":"Text","variant":"h5","text":"Decided"}]
print(json.dumps({"name":"bee_surface_update","arguments":{"surfaceId":"heddle-survey","components":comps}}))
