import json, sys, subprocess, os
S=sys.argv[1]; t=json.load(sys.stdin); r=t.get('record')
if not r: sys.exit(1)
def call(obj):
    p=os.path.join(S,'h.json'); json.dump(obj,open(p,'w')); subprocess.run([os.path.join(S,'beecall.sh'),'tools/call',p],capture_output=True)
ctx=r.get('context',{}); done_msg=None
if r['name']=='dismiss' and r['surfaceId']=='heddle-survey':
    out=subprocess.run(['python3',os.path.join(S,'decide_ui.py'),ctx['card'],'dismiss','-'],capture_output=True,text=True).stdout
    call(json.loads(out)); done_msg='dismissed'
elif r['name']=='drop' and r['surfaceId']=='kuilt-inflight' and ctx.get('column')=='dismissed':
    b=subprocess.run([os.path.join(S,'beecall.sh'),'tools/call','{"name":"bee_board","arguments":{}}'],capture_output=True,text=True).stdout
    j=json.loads(json.loads(b)['result']['content'][0]['text']); s=[x for x in j['open'] if x['surfaceId']=='kuilt-inflight'][0]
    order=[c for c in s['data']['cards']['order'] if c!=ctx['card']]
    call({"name":"bee_surface_update","arguments":{"surfaceId":"kuilt-inflight","data":{"/cards/order":order}}}); done_msg='dismissed'
else:
    sys.exit(1 if r['name']!='decide' else 0)
if 'token' in r: call({"name":"bee_action_done","arguments":{"recordId":r['recordId'],"token":r['token'],"result":done_msg}})
