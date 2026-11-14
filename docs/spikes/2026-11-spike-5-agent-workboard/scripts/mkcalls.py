import json
calls = [
 ('topo', 'Topology: bad-name subtree', """**The call.** When a child's own name is empty or duplicated, should Topology still report the problems *beneath* it?

**Why it matters.** A problem's address is the list of names from the root (`parent`) plus the child's index. Two siblings both named `batch` would give problems in *different* subtrees the **same** address. A config page could not tell them apart.

- **Keep (worker + reviewer):** skip that subtree. Its problems appear once the name is fixed. The KDocs and cookbook now say so. *Cost:* an admin may fix one error, save, and see a new one.
- **Report everything:** switch the address to an index path. *Cost:* changes `TopologyProblem`'s shape against the plan, and the problem list gets harder to read.

PR #3182 is merging with **Keep**. Overruling means a follow-up PR.""",
  [('keep','Keep: skip until fixed ★'),('index','Report all, by index path')]),
 ('l0', 'L0 edge index before L1', """**The call.** Task C's scale run failed, so how should the ledger index land?

**Numbers** (5k active + 50k retired edges): `acquire` takes 42 ms on JVM, 99 ms on wasm and 962 ms on macOS debug. The same run with 0 retired edges takes 8 ms on JVM. Retired edges make it 5–7× slower. Each lookup scans every edge.

- **L0 first ★:** a separate PR adds parent→children and child→inbound indexes, plus a folded retired-outflow total. That total is *not* a skip, because skipping overstates a parent's holdings. L1 then builds on fast reads. *Cost:* one more task on the long pole, about ½ day.
- **Fold into L1:** one PR. *Cost:* L1 is already the riskiest API task, and mixing a performance change with the API makes review harder.
- **Defer:** ship L1 on scans, index later. *Cost:* the consumer's 5k-child scale is slow from day one, and L1 might freeze an API the index then fights.

Waits for Task C's re-pinned scale probe either way.""",
  [('l0','L0 first ★'),('fold','Fold into L1'),('defer','Defer the index')]),
 ('explain', 'Pull explain() earlier', """**The call.** "Why is X held?" is planned as `explain(leaf)` in Task S2, the **last** task in the chain.

`explain` needs only L1, the engine. It does not need S1's status. It returns, e.g., `BehindAtLevel(parent=a/b, nextChild=a/b/c)`, which reads as "x cannot run: under a/b, c is ahead of it". It also returns `NotEligible` and `NoLiveLineage`.

- **Right after L1 ★:** split `explain` out of S2 into its own task. You get debuggability as soon as the engine exists, and it also checks L1. *Cost:* one more small PR. S2 keeps only `preview`.
- **Keep at S2:** as planned. *Cost:* debuggability arrives last.""",
  [('early','After L1 ★'),('late','Keep at S2')]),
 ('otel', 'heddle → OTel/OTLP (#3184)', """**The call.** Should heddle get OpenTelemetry output, and when?

**The proposal:** a `:kuilt-heddle-otel` module, the same shape as `:kuilt-warp-otel`. It sits outside core, so core's minimal dependency floor doesn't move. It would record:
- metrics per group path: leases, cost charged, overrun, in-flight, configured vs observed share, supply-exhausted;
- a span per lease, from acquire to complete;
- the **hold reason** as a structured attribute (explain's answer).

- **Plan it after S1 ★:** add task O1 to the #3173 plan, after S1, since status-shaped metrics need S1.
- **Plan it after L1:** leases and hold reasons only; share metrics come later. *Cost:* two passes over the module.
- **Not yet:** leave #3184 as needs-design.""",
  [('afterS1','Plan after S1 ★'),('afterL1','After L1, partial'),('notyet','Not yet')]),
 ('emit', 'When to emit hold reasons', """**The call.** How often should the OTel layer record *why* a job is held?

- **On change ★:** emit when a leaf's reason changes, e.g. from `BehindAtLevel(a/b)` to `NotEligible`. *Cost:* small; a little state per waiting leaf.
- **Every refused pick:** complete, but it costs one `explain` per waiting leaf per pick. At 5k children that is real CPU and a lot of telemetry.
- **On demand only:** `explain()` is callable, nothing is emitted. *Cost:* no history; you can't see why a job *was* held yesterday.""",
  [('change','On change ★'),('every','Every refused pick'),('demand','On demand only')]),
]
comps=[{"id":"root","component":"Column","children":["intro","tabs"]},
       {"id":"intro","component":"Text","text":"**Calls waiting on you — heddle #3173.** Pick a tab, read the tradeoff, click an option (★ = my recommendation). I'll act on the click and mark it here."}]
tabs=[]; data={"status":{}}
for cid,title,md,opts in calls:
    tabs.append({"title":title,"child":f"{cid}-col"})
    btns=[]
    for oid,lab in opts:
        bid=f"{cid}-{oid}"
        comps.append({"id":bid,"component":"Button","child":f"{bid}-l","action":{"event":{"name":"decide","context":{"call":cid,"option":oid,"label":lab}}}})
        comps.append({"id":f"{bid}-l","component":"Text","text":lab})
        btns.append(bid)
    comps += [{"id":f"{cid}-col","component":"Column","children":[f"{cid}-md",f"{cid}-row",f"{cid}-st"]},
              {"id":f"{cid}-md","component":"Text","text":md},
              {"id":f"{cid}-row","component":"Row","children":btns},
              {"id":f"{cid}-st","component":"Text","variant":"caption","text":{"path":f"/status/{cid}"}}]
    data["status"][cid]="⏳ Waiting for your call."
comps.insert(2,{"id":"tabs","component":"Tabs","tabs":tabs})
print(json.dumps({"name":"bee_surface_open","arguments":{"surfaceId":"heddle-calls","title":"heddle: calls for you","components":comps,"data":data}}))
