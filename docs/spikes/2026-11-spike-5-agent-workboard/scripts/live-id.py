# Live dependency map for kuilt-d5's identity track (#3159), derived from GitHub on every run.
# Edges follow the "[after …]" tags on each `### Task` heading of the plan on origin/epic/3159 (0.4 after 0.3 is a merge-order edge).
# Optional per-task overrides (e.g. "worker running") come from id-overrides.json: {"0.3": "worker running"}.
import json, subprocess, os, re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
S = os.path.dirname(os.path.abspath(__file__)); ET = ZoneInfo("America/New_York")
now = datetime.now(timezone.utc)
DEPS = {
 '0.1': [], '0.2': ['0.1'], '0.3': ['0.1'], '0.4': ['0.1', '0.3'],
 '0.5': ['0.2', '0.3', '0.4'], '0.6': ['0.2', '0.3', '0.4'], '0.7': ['0.4', '0.6'],
 '1.1': ['0.7'], '1.2': ['0.3'], '1.3': ['1.1', '0.5'], '1.4': ['1.3'], '1.5': ['1.4', '1.2'], '1.6': ['1.5'],
 '1.7': ['1.6'], '1.8': ['1.7'], '1.9': ['1.8'],
 '2.1': ['0.6'], '2.2': ['2.1'], '2.3': ['0.7'], '2.4': ['1.9', '2.1', '2.3'], '2.5': ['2.4'], '2.6': ['2.5'], '2.7': ['2.6', '2.2'],
 '3.1': ['0.7'], '3.2': ['3.1'], '3.3': ['3.1'], '3.4': ['3.1', 'SF'],
 'SF': ['0.1'],
}
subs = json.loads(subprocess.run(['gh', 'api', 'repos/tractat-us/kuilt/issues/3159/sub_issues', '--paginate'],
                                 capture_output=True, text=True, cwd='/tmp').stdout)
task = {}  # key -> issue
for i in subs:
    m = re.match(r'identity (\d\.\d):', i['title'])
    task[m.group(1) if m else ('SF' if 'Safari' in i['title'] else None)] = i
task.pop(None, None)
keys = [k for k in DEPS if k in task]
q = 'query{' + ''.join(
    f'i{task[k]["number"]}:search(query:"repo:tractat-us/kuilt is:pr \\"#{task[k]["number"]}\\" in:body",type:ISSUE,first:5)'
    '{nodes{...on PullRequest{number state isDraft createdAt mergedAt autoMergeRequest{enabledAt}}}}' for k in keys) + '}'
r = json.loads(subprocess.run(['gh', 'api', 'graphql', '-f', f'query={q}'], capture_output=True, text=True, cwd='/tmp').stdout)['data']
ov = {}
try: ov = json.load(open(os.path.join(S, 'id-overrides.json')))
except Exception: pass
def hm(t):
    t = t.astimezone(ET); n = now.astimezone(ET)
    return t.strftime('%-I:%M%p').lower()[:-1] if t.date() == n.date() else t.strftime('%a %-I:%M%p').lower()[:-1].capitalize()
def iso(s): return datetime.fromisoformat(s.replace('Z', '+00:00'))
from datetime import timedelta
EST = {}
try: EST = json.load(open(os.path.join(S, 'id-estimates.json')))
except Exception: pass
CAP = 2  # kuilt-d5 runs two workers at once
def topo(ks):
    out, seen = [], set()
    def visit(k):
        if k in seen or k not in ks: return
        seen.add(k)
        for d in DEPS[k]: visit(d)
        out.append(k)
    for k in ks: visit(k)
    return out
keys = topo(keys)
def dur(m): m = int(m); return f"{m}m" if m < 60 else f"{m//60}h{m%60:02d}"
info, fin, busy = {}, {}, []  # busy: finish times of slots already occupied
for k in keys:
    iss = task[k]; prs = [p for p in r[f'i{iss["number"]}']['nodes'] if p and iso(p['createdAt']) >= iso(iss['created_at'])]
    live = [p for p in prs if p['state'] == 'OPEN']; merged = [p for p in prs if p['state'] == 'MERGED']
    lab = {l['name'] for l in iss.get('labels', [])}
    o_ = ov.get(k); o_ = {'note': o_} if isinstance(o_, str) else (o_ or None)
    est = EST.get(k, 60)
    if iss['state'] == 'closed':
        fin[k] = iso(iss['closed_at']); info[k] = ('done', f"closed {hm(fin[k])}")
    elif merged and not live:
        p = max(merged, key=lambda p: p['number']); fin[k] = iso(p['mergedAt']); info[k] = ('done', f"#{p['number']} merged {hm(fin[k])}")
    elif live:
        p = max(live, key=lambda p: p['number']); armed = p['autoMergeRequest'] is not None
        st0 = iso(o_['started']) if o_ and o_.get('started') else iso(p['createdAt'])
        fin[k] = now + timedelta(minutes=15) if armed else max(now + timedelta(minutes=15), st0 + timedelta(minutes=est))
        busy.append(fin[k])
        info[k] = ('merging' if armed else 'review', f"#{p['number']} · " + ('merging' if armed else ('draft' if p['isDraft'] else 'in review')) + f" · ~{hm(fin[k])}")
    elif o_:
        st0 = iso(o_['started']) if o_.get('started') else now
        fin[k] = max(now + timedelta(minutes=10), st0 + timedelta(minutes=est)); busy.append(fin[k])
        el = (now - st0).total_seconds() / 60
        info[k] = ('review', f"{o_.get('note', 'worker running')} · {dur(el)}/~{dur(est)}")
    else:
        ready = all(info.get(d, ('',))[0] == 'done' for d in DEPS[k]) and 'blocked' not in lab
        info[k] = ('next' if ready else 'blocked', None)
# project not-started tasks: CAP workers, slice 1 strictly serial (every task edits SeamRoom.kt)
slots = sorted(busy)[:CAP] + [now] * max(0, CAP - len(busy))
slots = sorted(slots)
s1_free = max([fin[k] for k in keys if k.startswith('1.') and k in fin and info[k][0] != 'done'] + [now])
pending = [k for k in keys if info[k][1] is None]
while pending:
    cand = [x for x in pending if all(d in fin for d in DEPS[x])]
    k = min(cand, key=lambda x: (max([fin[d] for d in DEPS[x]] + [now]), keys.index(x)))
    pending.remove(k)
    t0 = max([fin[d] for d in DEPS[k]] + [now, slots[0]])
    if k.startswith('1.'): t0 = max(t0, s1_free)
    fin[k] = t0 + timedelta(minutes=EST.get(k, 60))
    if k.startswith('1.'): s1_free = fin[k]
    slots[0] = fin[k]; slots.sort()
    info[k] = (info[k][0], f"est {dur(EST.get(k, 60))} · ETA {hm(fin[k])}")
eta = max(fin.values())
# layout: one row per slice, columns by depth within the graph
depth = {}
for k in keys: depth[k] = 1 + max([depth[d] for d in DEPS[k] if d in depth], default=-1)
rows = {'0': 0, '1': 1, '2': 2, '3': 3, 'SF': 0}
ST = {'done': ('#bfeedd', '#00744f', '', '✓'), 'review': ('#cfe3f5', '#0060a0', '', '▶'), 'merging': ('#f3d3e6', '#a3457c', '', '⇢'),
      'next': ('#faf3a8', '#7a6f00', '6 3', '●'), 'blocked': ('#e4e6ea', '#5b6470', '2 3', '⏸')}
W, H, CX, X0, Y0, RH = 112, 40, 124, 8, 40, 50
slot = {}; pos = {}
for k in sorted(keys, key=lambda k: (depth[k], k)):
    row = rows['SF' if k == 'SF' else k[0]]
    base = row * 2  # two lanes per slice so siblings at the same depth don't overlap
    lane = slot.get((base, depth[k]), 0); slot[(base, depth[k])] = lane + 1
    pos[k] = (X0 + depth[k] * CX, Y0 + (base + lane) * RH)
vbw = int(max(x for x, _ in pos.values()) + W + 8); vbh = int(max(y for _, y in pos.values()) + H + 8)
o = [f"<svg viewBox='0 0 {vbw} {vbh}' width='100%' font-family='system-ui,sans-serif'><defs><marker id='b' viewBox='0 0 8 8' refX='7' refY='4' markerWidth='6' markerHeight='6' orient='auto'><path d='M0 0L8 4L0 8z' fill='#8c959f'/></marker></defs>"]
for k in keys:
    for d in DEPS[k]:
        if d not in pos: continue
        x1, y1 = pos[d]; x2, y2 = pos[k]; x1 += W; y1 += H / 2; y2 += H / 2; mx = (x1 + x2) / 2
        o.append(f"<path d='M{x1:.0f} {y1:.0f}C{mx:.0f} {y1:.0f} {mx:.0f} {y2:.0f} {x2-2:.0f} {y2:.0f}' fill='none' stroke='#8c959f' stroke-width='1.1' marker-end='url(#b)'/>")
for k in keys:
    x, y = pos[k]; s, sub = info[k]; f, sk, da, g = ST[s]; dd = f" stroke-dasharray='{da}'" if da else ''
    label = ('Safari/FF probe' if k == 'SF' else k) + f" #{task[k]['number']}"
    o.append(f"<rect x='{x}' y='{y}' width='{W}' height='{H}' rx='7' fill='{f}' stroke='{sk}' stroke-width='2'{dd}/>")
    o.append(f"<text x='{x+W/2:.0f}' y='{y+16}' text-anchor='middle' font-size='11.5' font-weight='600' fill='#1f2328'>{label}</text>")
    o.append(f"<text x='{x+W/2:.0f}' y='{y+31}' text-anchor='middle' font-size='10' fill='{sk}' font-weight='600'>{g} {sub}</text>")
lx = 8
for k2, t in [('done', 'merged'), ('review', 'in flight'), ('merging', 'auto-merge armed'), ('next', 'ready'), ('blocked', 'blocked')]:
    f, sk, da, g = ST[k2]; dd = f" stroke-dasharray='{da}'" if da else ''
    o.append(f"<rect x='{lx}' y='8' width='14' height='14' rx='3' fill='{f}' stroke='{sk}' stroke-width='2'{dd}/><text x='{lx+19}' y='19' font-size='11' fill='{sk}' font-weight='600'>{g} {t}</text>"); lx += 19 + len(t) * 6.4 + 26
o.append("</svg>")
done = sum(1 for v in info.values() if v[0] == 'done')
html = ("<style>body{margin:0;background:#fff;color:#1f2328;font-family:system-ui,sans-serif}.w{padding:10px 12px}h3{margin:0 0 2px;font-size:15px}p{margin:3px 0 8px;font-size:12.5px;color:#424a53}</style>"
        f"<div class='w'><h3>identity #3159 (kuilt-d5): live dependency map</h3><p><b>{done}/{len(info)}</b> done. Updated <b>{hm(now)} ET</b>. Rough ETA for the whole chain: <b>{eta.astimezone(ET).strftime('%a %-I:%M %p')}</b> (2 workers, slice 1 serial; estimates from id-estimates.json). Refreshed every 2 min from the epic's sub-issues and their PRs. Rows are slices 0–3.</p>" + ''.join(o) + "</div>")
json.dump({"name": "bee_surface_update", "arguments": {"surfaceId": os.environ.get("ID_SURFACE", "identity-map"), "components": [{"id": "root", "component": "Sketch", "title": "identity plan", "html": html}]}},
          open(os.path.join(S, 'live-id.json'), 'w'))
