#!/usr/bin/env python3
"""Live dependency map: a plan's tasks drawn as a bee Sketch, each task's state read from GitHub.

usage: livemap.py SPEC.json [--overrides OV.json] [--surface ID] [--open TITLE] > call.json

Prints the `params` of one MCP `tools/call`: `bee_surface_update`, or `bee_surface_open` with
`--open`. Exits non-zero, printing nothing, when GitHub can't be read, so a loop keeps the last good
map rather than drawing every task as blocked.

The spec (see ../example/heddle-overflow.json):

  repo      "owner/name" the PRs and issues live in
  title     the map's heading
  about     optional sentence or two under the heading
  self      the owner value meaning this session (default "us"); "you" means the person
  refresh   minutes between redraws, for the header (default 1)
  tz        time zone for times (default America/New_York)
  nodes     key -> node, in the order to list them
    label     short box title
    issue     find the PR whose body cites "#issue", opened after the issue was; a closed issue is done
    pr        this PR, exactly
    branch    a branch prefix. Nodes sharing one prefix are a stack: its open and merged PRs, oldest
              first, go to those nodes in spec order
    deps      keys this node waits on
    est       minutes of work once started (default 60)
    owner     who does it (default self); anyone else's name shows on the box
    pos       [column, row] to pin the box; omit to place it by depth
    row       preferred row when placed by depth
    aggregate true for a terminal node such as a release: waits on "deps" (default every other
              node), and its edges are not drawn, since they cross every row and read as false
              dependencies. The header says what feeds it instead.

The overrides file is keyed like `nodes` and holds what changes during the run:
    pr        pin a PR the discovery got wrong
    note      a worker is running though no PR exists yet: the box shows in flight, never ready
    started   when that worker started (ISO time), for its ETA
    tag       a remark on a box that is not in flight ("plan being written"): it keeps its state
  "_asks": ["…"] adds lines to the panel's "Waiting on you".
"""
import argparse, html, json, os, re, subprocess, sys
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo

ap = argparse.ArgumentParser()
ap.add_argument('spec'); ap.add_argument('--overrides'); ap.add_argument('--surface'); ap.add_argument('--open', metavar='TITLE')
a = ap.parse_args()
spec = json.load(open(a.spec))
ovp = a.overrides or os.path.join(os.path.dirname(os.path.abspath(a.spec)), spec.get('overrides', ''))
ov = {}
if a.overrides or spec.get('overrides'):
    try: ov = json.load(open(ovp))
    except FileNotFoundError: pass
repo = spec['repo']; OWNER, NAME = repo.split('/')
SELF = spec.get('self', 'us'); TZ = ZoneInfo(spec.get('tz', 'America/New_York'))
now = datetime.now(timezone.utc)
N = spec['nodes']

def die(msg): print(f"livemap: {msg}", file=sys.stderr); sys.exit(1)
def o(k): return {**{f: N[k][f] for f in ('pr', 'note', 'started', 'tag') if f in N[k]}, **(ov.get(k) or {})}
aggs = [k for k in N if N[k].get('aggregate')]
for k in aggs:
    if N[k].get('deps') in (None, '*'): N[k]['deps'] = [x for x in N if x not in aggs]
for k, v in N.items():
    for d in v.get('deps', []):
        if d not in N: die(f"{k} depends on unknown node {d}")

# Topological order, in spec order where free. A cycle is a spec error.
order, state = [], {}
def visit(k, path=()):
    if state.get(k) == 2: return
    if state.get(k) == 1: die("dependency cycle: " + ' -> '.join(path + (k,)))
    state[k] = 1
    for d in N[k].get('deps', []): visit(d, path + (k,))
    state[k] = 2; order.append(k)
for k in N: visit(k)

# One GraphQL query for everything: pinned PRs, closed-ness of issues, stack branches, issue citations.
PRF = 'number state isDraft createdAt mergedAt headRefName autoMergeRequest{enabledAt}'
def q(s): return s.replace('\\', '\\\\').replace('"', '\\"')
pinned = {k: o(k)['pr'] for k in N if o(k).get('pr')}
issues = sorted({N[k]['issue'] for k in N if N[k].get('issue')})
branches = sorted({N[k]['branch'] for k in N if N[k].get('branch')})
inner = ''.join(f'p{n}:pullRequest(number:{int(n)}){{{PRF}}}' for n in sorted(set(pinned.values())))
inner += ''.join(f'i{n}:issue(number:{int(n)}){{state createdAt closedAt}}' for n in issues)
parts = [f'repo:repository(owner:"{q(OWNER)}",name:"{q(NAME)}"){{{inner or "id"}}}']
parts += [f'b{j}:search(query:"{q(f"repo:{repo} is:pr head:{b}")}",type:ISSUE,first:50){{nodes{{...on PullRequest{{{PRF}}}}}}}' for j, b in enumerate(branches)]
parts += [f's{n}:search(query:"{q(f"repo:{repo} is:pr")} \\"#{int(n)}\\" in:body",type:ISSUE,first:10){{nodes{{...on PullRequest{{{PRF} body}}}}}}' for n in issues]
res = subprocess.run(['gh', 'api', 'graphql', '-f', 'query=query{' + ''.join(parts) + '}'], capture_output=True, text=True, cwd='/tmp')
try: R = json.loads(res.stdout)['data']
except Exception: die(f"GitHub query failed: {res.stderr.strip() or res.stdout[:300]}")
if not R or not R.get('repo'): die(f"GitHub query returned no repository: {res.stdout[:300]}")

def iso(s): return datetime.fromisoformat(s.replace('Z', '+00:00')) if s else None
prs = {k: [R['repo'][f'p{n}']] for k, n in pinned.items() if R['repo'].get(f'p{n}')}
claimed = set(pinned.values())
for j, b in enumerate(branches):  # a stack: oldest open-or-merged PR to the first unpinned node, and so on
    found = sorted((p for p in (R.get(f'b{j}') or {}).get('nodes') or [] if p and p['headRefName'].startswith(b)
                    and p['state'] != 'CLOSED' and p['number'] not in claimed), key=lambda p: p['createdAt'])
    for k, p in zip([k for k in N if N[k].get('branch') == b and k not in pinned], found):
        prs[k] = [p]; claimed.add(p['number'])
for k in N:
    n = N[k].get('issue')
    if k in prs or not n: continue
    since = iso((R['repo'].get(f'i{n}') or {}).get('createdAt')) or datetime.min.replace(tzinfo=timezone.utc)
    cites = re.compile(rf'#{int(n)}(?!\d)')
    prs[k] = [p for p in (R.get(f's{n}') or {}).get('nodes') or [] if p and p['number'] not in claimed
              and iso(p['createdAt']) >= since and cites.search(p.get('body') or '')]

def hm(t):
    t = t.astimezone(TZ); d = now.astimezone(TZ)
    return t.strftime('%-I:%M%p').lower()[:-1] if t.date() == d.date() else t.strftime('%a %-I:%M%p').lower()[:-1].capitalize()
def dur(m): m = int(m); return f"{m}m" if m < 60 else f"{m//60}h{m%60:02d}"

info, fin = {}, {}
for k in order:
    v = N[k]; x = o(k); deps = v.get('deps', []); est = v.get('est', 60); tag = x.get('tag')
    ps = prs.get(k, []); live = [p for p in ps if p['state'] == 'OPEN']; merged = [p for p in ps if p['state'] == 'MERGED']
    iss = R['repo'].get(f"i{v.get('issue')}") if v.get('issue') else None
    t0 = max([fin[d] for d in deps] + [now])
    if k in aggs:
        ok = all(info[d][0] == 'done' for d in deps)
        fin[k] = t0; info[k] = ('next' if ok else 'blocked', 'ready' if ok else f"after all {len(deps)}")
    elif merged and not live:
        p = max(merged, key=lambda p: p['number']); fin[k] = iso(p['mergedAt']); info[k] = ('done', f"#{p['number']} {hm(fin[k])}")
    elif live:
        p = max(live, key=lambda p: p['number']); armed = p['autoMergeRequest'] is not None
        st0 = iso(x.get('started')) or iso(p['createdAt'])
        fin[k] = now + timedelta(minutes=20) if armed else max(now + timedelta(minutes=20), st0 + timedelta(minutes=est), t0)
        info[k] = ('merging' if armed else 'review', f"#{p['number']} " + ('armed' if armed else ('draft' if p['isDraft'] else 'review')) + f" ~{hm(fin[k])}")
    elif iss and iss['state'] == 'CLOSED':
        fin[k] = iso(iss['closedAt']); info[k] = ('done', f"closed {hm(fin[k])}")
    elif x.get('note'):  # a worker is running before its PR exists: in flight, never "ready"
        st0 = iso(x.get('started')) or now
        fin[k] = max(now + timedelta(minutes=10), st0 + timedelta(minutes=est), t0)
        info[k] = ('review', f"{x['note']} ~{hm(fin[k])}")
    else:  # a tag is a remark: the box keeps its own state
        ready = all(info[d][0] == 'done' for d in deps)
        fin[k] = t0 + timedelta(minutes=est)
        info[k] = ('next' if ready else 'blocked', (f"{tag} · ETA {hm(fin[k])}" if tag else f"{dur(est)} · ETA {hm(fin[k])}"))

# Layout on a grid of cells, one box per cell, so no two boxes can overlap whatever the graph.
# Pinned boxes first, then the rest by depth (a pinned cell already taken falls back to the next
# free row in its column), then aggregates one column right of everything.
depth = {}
for k in order: depth[k] = 1 + max([depth[d] for d in N[k].get('deps', []) if d not in aggs], default=-1)
cell, taken = {}, set()
def place(k, col, row):
    while (col, row) in taken: row += 1
    cell[k] = (col, row); taken.add((col, row))
for k in N:
    if 'pos' in N[k] and k not in aggs:
        c, r = N[k]['pos']
        if (c, r) in taken: print(f"livemap: {k} pinned to taken cell {c},{r}; moved down", file=sys.stderr)
        place(k, c, r)
for k in order:
    if k not in cell and k not in aggs: place(k, depth[k], N[k].get('row', 0))
for k in aggs:
    rows = sorted(r for _, r in cell.values()) or [0]
    c, r = N[k].get('pos', (max([c for c, _ in cell.values()], default=-1) + 1, N[k].get('row', rows[len(rows) // 2])))
    place(k, c, r)

def esc(s): return html.escape(str(s), quote=True).replace('{', '&#123;').replace('}', '&#125;')
def who(k):
    ow = N[k].get('owner', SELF); return '' if ow in (SELF, 'you') else f" ({ow})"
titles = {k: N[k]['label'] + who(k) for k in N}
# Size the boxes to the longest text, within a cap; longer text is cut with an ellipsis.
need = max(max(len(t) * 6.6 for t in titles.values()), max(len(s) * 5.9 + 14 for _, s in info.values()))
W = int(min(220, max(132, need + 12))); H, CX, X0, Y0, RH = 40, W + 18, 8, 40, 48
def fit(s, px): n = int((W - 10) / px); return s if len(s) <= n else s[:n - 1] + '…'
pos = {k: (X0 + c * CX, Y0 + r * RH) for k, (c, r) in cell.items()}
assert len(set(pos.values())) == len(pos), "two boxes share a cell"

ST = {'done': ('#bfeedd', '#00744f', '', '✓'), 'review': ('#cfe3f5', '#0060a0', '', '▶'), 'merging': ('#f3d3e6', '#a3457c', '', '⇢'),
      'next': ('#faf3a8', '#7a6f00', '6 3', '●'), 'blocked': ('#e4e6ea', '#5b6470', '2 3', '⏸')}
vbw = int(max(x for x, _ in pos.values()) + W + 8); vbh = int(max(y for _, y in pos.values()) + H + 8)
out = [f"<svg viewBox='0 0 {vbw} {vbh}' width='100%' font-family='system-ui,sans-serif'><defs><marker id='b' viewBox='0 0 8 8' refX='7' refY='4' markerWidth='6' markerHeight='6' orient='auto'><path d='M0 0L8 4L0 8z' fill='#8c959f'/></marker></defs>"]
for k in N:
    if k in aggs: continue
    for d in N[k].get('deps', []):
        x1, y1 = pos[d]; x2, y2 = pos[k]; x1 += W; y1 += H / 2; y2 += H / 2; mx = (x1 + x2) / 2
        out.append(f"<path d='M{x1:.0f} {y1:.0f}C{mx:.0f} {y1:.0f} {mx:.0f} {y2:.0f} {x2-2:.0f} {y2:.0f}' fill='none' stroke='#8c959f' stroke-width='1.1' marker-end='url(#b)'/>")
for k in N:
    x, y = pos[k]; s, sub = info[k]; f, sk, da, g = ST[s]; dd = f" stroke-dasharray='{da}'" if da else ''
    out.append(f"<g><title>{esc(titles[k] + ': ' + sub)}</title><rect x='{x}' y='{y}' width='{W}' height='{H}' rx='7' fill='{f}' stroke='{sk}' stroke-width='2'{dd}/>")
    out.append(f"<text x='{x+W/2:.0f}' y='{y+16}' text-anchor='middle' font-size='11.5' font-weight='600' fill='#1f2328'>{esc(fit(titles[k], 6.6))}</text>")
    out.append(f"<text x='{x+W/2:.0f}' y='{y+31}' text-anchor='middle' font-size='10' fill='{sk}' font-weight='600'>{esc(fit(g + ' ' + sub, 5.9))}</text></g>")
lx = 8
for k2, t in [('done', 'merged'), ('review', 'in flight'), ('merging', 'auto-merge armed'), ('next', 'ready'), ('blocked', 'blocked')]:
    f, sk, da, g = ST[k2]; dd = f" stroke-dasharray='{da}'" if da else ''
    out.append(f"<rect x='{lx}' y='8' width='14' height='14' rx='3' fill='{f}' stroke='{sk}' stroke-width='2'{dd}/><text x='{lx+19}' y='19' font-size='11' fill='{sk}' font-weight='600'>{g} {t}</text>"); lx += 19 + len(t) * 6.4 + 26
out.append("</svg>")

# The check-in panel: when the agent expects to look again, what it waits on, what it could start.
work = [k for k in order if k not in aggs]
flight = sorted((k for k in work if info[k][0] in ('review', 'merging')), key=lambda k: fin[k])
def li(xs): return ''.join(f'<li>{x}</li>' for x in xs) or '<li>nothing</li>'
waiting = [f"{esc(titles[k])}: {'auto-merging' if info[k][0] == 'merging' else ('PR open' if prs.get(k) else 'worker running')} (~{hm(fin[k])})" for k in flight]
canstart = [esc(titles[k]) for k in work if info[k][0] == 'next' and N[k].get('owner', SELF) == SELF]
yours = [esc(N[k]['label']) for k in order if info[k][0] == 'next' and N[k].get('owner') == 'you'] + [esc(s) for s in ov.get('_asks', [])]
nxt = flight[0] if flight else None
panel = ("<div class='pn'><div><b>Next check-in:</b> " + (f"~{hm(fin[nxt])} ET, when <i>{esc(titles[nxt])}</i> should land" if nxt else 'none scheduled') +
         ". The agent also acts whenever a worker or CI reports, so it may be sooner.</div>"
         "<div class='cols'><div><b>Waiting on</b><ul>" + li(waiting) + "</ul></div>"
         "<div><b>Can start now (unblocked, not dispatched)</b><ul>" + li(canstart) + "</ul></div>"
         "<div><b>Waiting on you</b><ul>" + li(yours) + "</ul></div></div></div>")

done = sum(1 for k in work if info[k][0] == 'done')
eta = max(fin[k] for k in N)
feeds = ''
for k in aggs:
    feeds += (f" Every box feeds <b>{esc(N[k]['label'])}</b>, so its arrows are left out." if len(N[k]['deps']) == len(work)
              else f" <b>{esc(N[k]['label'])}</b> waits on {len(N[k]['deps'])} boxes; its arrows are left out.")
every = spec.get('refresh', 1)
html_ = ("<style>body{margin:0;background:#fff;color:#1f2328;font-family:system-ui,sans-serif}.w{padding:10px 12px}h3{margin:0 0 2px;font-size:15px}p{margin:3px 0 8px;font-size:12.5px;color:#424a53}.pn{font-size:12.5px;background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:8px 10px;margin:0 0 10px}.cols{display:flex;flex-wrap:wrap;gap:6px 22px;margin-top:4px}.cols>div{flex:1 1 200px}ul{margin:2px 0 0;padding-left:18px}li{margin:1px 0}</style>"
         f"<div class='w'><h3>{esc(spec['title'])}</h3><p><b>{done}/{len(work)}</b> done. Updated <b>{hm(now)} ET</b>, refreshed every {'minute' if every == 1 else f'{every} min'} from GitHub. "
         f"Rough ETA for {esc(N[aggs[0]]['label']) if aggs else 'the whole plan'}: <b>{eta.astimezone(TZ).strftime('%a %-I:%M %p')}</b>. "
         f"Estimates are hand-set, and a worker without a PR yet shows as in flight; actual times come from the PRs.{feeds} {esc(spec.get('about', ''))}</p>"
         + panel + ''.join(out) + "</div>")
surface = a.surface or os.environ.get('LIVEMAP_SURFACE') or spec.get('surface', 'livemap')
call = {"name": "bee_surface_update", "arguments": {"surfaceId": surface, "components": [{"id": "root", "component": "Sketch", "title": spec['title'], "html": html_}]}}
if a.open: call['name'] = 'bee_surface_open'; call['arguments']['title'] = a.open
json.dump(call, sys.stdout)
