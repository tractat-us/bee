import json, subprocess, sys, os
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo
S=os.path.dirname(os.path.abspath(__file__)); ET=ZoneInfo("America/New_York")
st=json.load(open(os.path.join(S,'live-state.json'))); now=datetime.now(timezone.utc)
def iso(s): return datetime.fromisoformat(s.replace('Z','+00:00')) if s else None
prs=[v['pr'] for v in st.values() if 'pr' in v]
q='query{repository(owner:"tractat-us",name:"kuilt"){'+''.join(f'p{n}:pullRequest(number:{n}){{state isDraft createdAt mergedAt autoMergeRequest{{enabledAt}}}}' for n in prs)+'}}'
r=json.loads(subprocess.run(['gh','api','graphql','-f',f'query={q}'],capture_output=True,text=True,cwd='/tmp').stdout)['data']['repository']
def hm(t): return t.astimezone(ET).strftime('%-I:%M')
def dur(m): m=int(m); return f"{m}m" if m<60 else f"{m//60}h{m%60:02d}"
order=list(st); fin={}  # node -> finish time (actual or projected)
info={}
for n in order:  # deps listed before dependants
    v=st[n]; p=r.get(f"p{v['pr']}") if 'pr' in v else None
    if p and p['state']=='MERGED':
        fin[n]=iso(p['mergedAt']); info[n]=('done', f"merged {hm(fin[n])} · took {dur((fin[n]-iso(p['createdAt'])).total_seconds()/60)}")
    elif p and p['state']=='OPEN':
        el=(now-iso(p['createdAt'])).total_seconds()/60
        armed=p['autoMergeRequest'] is not None
        stt='merging' if armed else ('call' if v.get('note') else 'review')
        fin[n]=now+timedelta(minutes=15 if armed else 35)
        info[n]=(stt, f"#{v['pr']} · {'merging' if armed else (v.get('note') or 'in flight')} · {dur(el)}")
    elif v.get('started'):
        el=(now-iso(v['started'])).total_seconds()/60
        fin[n]=max(now+timedelta(minutes=10), iso(v['started'])+timedelta(minutes=v['est']))
        info[n]=('review', f"worker running · {dur(el)} / ~{dur(v['est'])}")
    else:
        deps=v.get('deps',[]); ready=all(info.get(d,('',''))[0]=='done' for d in deps)
        start=max([fin.get(d,now) for d in deps]+[now]); fin[n]=start+timedelta(minutes=v['est'])
        info[n]=('next' if ready else 'blocked', f"est {dur(v['est'])} · ETA {hm(fin[n])}")
# layout by depth
depth={}
for n in order: depth[n]=1+max([depth[d] for d in st[n].get('deps',[])], default=-1)
cols={}
for n in order: cols.setdefault(depth[n],[]).append(n)
# Okabe-Ito hues (colour-blind safe), plus a glyph and a border style per state, so colour is never the only cue.
ST={'done':   ('#bfeedd','#00744f','',    '✓'),
    'review': ('#cfe3f5','#0060a0','',    '▶'),
    'merging':('#f3d3e6','#a3457c','',    '⇢'),
    'call':   ('#fde2b0','#a86b00','',    '⚠'),
    'next':   ('#faf3a8','#7a6f00','6 3', '●'),
    'blocked':('#e4e6ea','#5b6470','2 3', '⏸')}
W,H,CX,RY,X0,Y0=176,42,200,54,8,40
maxrows=max(len(c) for c in cols.values())
pos={}
for d,ns in cols.items():
    off=(maxrows-len(ns))/2
    for i,n in enumerate(ns): pos[n]=(X0+d*CX, Y0+(i+off)*RY)
vbw=int(X0+max(cols)*CX+W+8); vbh=int(Y0+maxrows*RY+4)
o=[f"<svg viewBox='0 0 {vbw} {vbh}' width='100%' font-family='system-ui,sans-serif'><defs><marker id='a' viewBox='0 0 8 8' refX='7' refY='4' markerWidth='6' markerHeight='6' orient='auto'><path d='M0 0L8 4L0 8z' fill='#8c959f'/></marker></defs>"]
for n in order:
    for d in st[n].get('deps',[]):
        x1,y1=pos[d]; x2,y2=pos[n]; x1+=W; y1+=H/2; y2+=H/2; mx=(x1+x2)/2
        o.append(f"<path d='M{x1:.0f} {y1:.0f}C{mx:.0f} {y1:.0f} {mx:.0f} {y2:.0f} {x2-2:.0f} {y2:.0f}' fill='none' stroke='#8c959f' stroke-width='1.2' marker-end='url(#a)'/>")
for n in order:
    x,y=pos[n]; s,sub=info[n]; f,sk,da,g=ST[s]; sub=g+' '+sub; dd=f" stroke-dasharray='{da}'" if da else ''
    o.append(f"<rect x='{x:.0f}' y='{y:.0f}' width='{W}' height='{H}' rx='8' fill='{f}' stroke='{sk}' stroke-width='2.2'{dd}/>")
    o.append(f"<text x='{x+W/2:.0f}' y='{y+17:.0f}' text-anchor='middle' font-size='12.5' font-weight='600' fill='#1f2328'>{st[n]['label'].replace('{','&#123;').replace('}','&#125;')}</text>")
    o.append(f"<text x='{x+W/2:.0f}' y='{y+33:.0f}' text-anchor='middle' font-size='10.5' fill='{sk}' font-weight='600'>{sub}</text>")
lx=8
for k,t in [('done','merged'),('review','in flight'),('merging','auto-merge armed'),('call','fix-ups'),('next','ready next'),('blocked','blocked')]:
    f,sk,da,g=ST[k]; dd=f" stroke-dasharray='{da}'" if da else ''
    o.append(f"<rect x='{lx}' y='8' width='16' height='16' rx='3' fill='{f}' stroke='{sk}' stroke-width='2'{dd}/><text x='{lx+21}' y='20' font-size='11.5' fill='{sk}' font-weight='600'>{g} {t}</text>"); lx+=21+len(t)*6.6+30
o.append("</svg>")
eta=max(fin.values())
inflight=[n for n in order if info[n][0] in ('review','merging','call')]
soon=sorted(inflight,key=lambda n: fin[n])
nxt=soon[0] if soon else None
canstart=[st[n]['label'] for n in order if info[n][0]=='next']
import re as _re
_order=eval(_re.search(r"order=(\[.*?\])",open(os.path.join(S,'decide_ui.py')).read()).group(1))
_dec=json.load(open(os.path.join(S,'decided.json'))) if os.path.exists(os.path.join(S,'decided.json')) else {}
yours=[f"survey call: <i>{c}</i> (on heddle-survey)" for c in _order if c not in _dec]
def li(xs): return ''.join(f'<li>{x}</li>' for x in xs) or '<li>nothing</li>'
waiting=[f"{st[n]['label']}: {'auto-merging' if info[n][0]=='merging' else ('worker running' if 'worker' in info[n][1] else 'PR open, review or fix-ups')} (expected ~{hm(fin[n])})" for n in soon]
panel=("<div class='pn'><div><b>Next check-in:</b> "+(f"~{hm(fin[nxt])} ET, when <i>{st[nxt]['label']}</i> should land" if nxt else 'none scheduled')+
  ". I also act whenever a worker or CI reports, so it may be sooner.</div>"
  "<div class='cols'><div><b>Waiting on</b><ul>"+li(waiting)+"</ul></div>"
  "<div><b>Can start now (unblocked, not dispatched)</b><ul>"+li(canstart)+"</ul></div>"
  "<div><b>Waiting on you</b><ul>"+li(yours)+"</ul></div></div></div>")
done=sum(1 for v in info.values() if v[0]=='done')
html=("<style>body{margin:0;background:#fff;color:#1f2328;font-family:system-ui,sans-serif}.w{padding:10px 12px}h3{margin:0 0 2px;font-size:15px}p{margin:3px 0 8px;font-size:12.5px;color:#424a53}.pn{font-size:12.5px;background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:8px 10px;margin:0 0 10px}.cols{display:flex;flex-wrap:wrap;gap:6px 22px;margin-top:4px}.cols>div{flex:1 1 200px}ul{margin:2px 0 0;padding-left:18px}li{margin:1px 0}</style>"
      f"<div class='w'><h3>heddle #3173: live dependency map</h3><p><b>{done}/{len(info)}</b> merged. Updated <b>{hm(now)} ET</b>, refreshed every 2 min from GitHub. Rough ETA for the whole chain: <b>{eta.astimezone(ET).strftime('%a %-I:%M %p')}</b>. Estimates are hand-set; the actual times come from the PRs.</p>"+panel+''.join(o)+"</div>")
u={"name":"bee_surface_update","arguments":{"surfaceId":os.environ.get("HEDDLE_SURFACE","heddle-map-2"),"components":[{"id":"root","component":"Sketch","title":"heddle plan","html":html}]}}
json.dump(u,open(os.path.join(S,'live.json'),'w'))
