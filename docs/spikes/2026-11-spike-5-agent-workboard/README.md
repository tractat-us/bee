# Spike 5: an agent keeps its work on the board

A kuilt session ran a multi-worker plan on 2026-11-12, and Iain watched it on a local bee board
instead of in the terminal. He asked for each piece as he went. This folder keeps the scripts that
session drew with, as they ran, so the ideas can be improved one at a time rather than
rediscovered. It is a spike: nothing here is product code, and the scripts are hand-rolled
against the MCP endpoint rather than through the plugin.

## What Iain asked for, in order

1. **"A nice visual in bee of the work in flight and the plan."** Two surfaces: a Sketch of the
   task dependency map, and a Board of cards by state (landed, in review, needs a call, next,
   proposed), each linking to its PR or issue.
2. **"Where it needs a call, I'd like to click on them, see a summary of the tradeoff, and have
   the button be clickable."** First drawn as Tabs, one per call, with the full tradeoff and a
   button row.
3. **"Basically the survey, but optionally with more information."** Redrawn as a column of
   Cards. Each card has a one-line question, an option button per answer with ★ on the agent's
   recommendation, and a **More…** Modal holding the tradeoff and its numbers.
4. **"I clicked on two buttons but I don't see an indication that it worked, or the box
   vanishing."** The status caption bound by data path was too quiet. Now a click immediately
   swaps the card to "⏳ Got it", with its buttons gone, before the agent has read the record.
   Once the agent acts, it shows "✅ Decided … and what happens next", and the card moves under a
   **Decided** heading. The header counts the calls still open.
5. **"I want to dismiss cards without talking to you about it."** Decided survey cards get a
   **Dismiss** button. The Board gets a "Dismiss (drop here)" column. A background handler acts
   on both without the agent's turn. Dismissing a whole surface is a product gap, filed as
   [bee#234](https://github.com/tractat-us/bee/issues/234).
6. **"Keep the dependency map up to date, and put times on the cards. So I can look at the map
   and know when you'll check back, or whether more can be scheduled."** The map is now
   regenerated every two minutes from GitHub. Merged tasks show when they merged and how long they
   took. Open PRs show how long they have been open, and pending tasks show an estimate and an ETA
   along their dependencies. Above the map, a panel shows **Next check-in**, **Waiting on**, **Can
   start now** (unblocked but not dispatched, which means the agent is under-scheduling) and
   **Waiting on you**.

## The notions, and what each one taught

| Notion | Built from | Lesson |
|---|---|---|
| Live dependency map | Sketch (inline SVG), redrawn whole | Times and a "when will you check back" line made it a dashboard, not a picture. |
| In-flight board | Board | Cards linking to PRs are enough. A drop column doubles as a dismiss gesture. |
| Survey with optional depth | Card, Button, Modal | The question goes up front and the tradeoff behind **More…**. A click must change the card at once. |
| Unattended handlers | `bee_wait_action` loop | Dismissals and acknowledgements must not wait for the agent's next turn. |

## The scripts

All of them live in `scripts/` and expect `zsh`, `python3` and an authenticated `gh`. Set
`BEE_URL` to the board's MCP endpoint, for example `http://127.0.0.1:8233/mcp?name=me`.

| Script | What it does |
|---|---|
| `beecall.sh <method> <params-json-or-file>` | One MCP JSON-RPC call. It opens a session on first use and caches its id in `$BEE_SID_FILE` (default: beside itself). It exits 3 when the host answers 404 (the session is gone), 2 on any other reply without a `"result"`. Each request gets a random id, because a fixed id collides with a concurrent long-poll ("Request id is already in use"). |
| `livemap.py SPEC.json` | The live dependency map, for any plan. Reads a graph spec, asks GitHub with one GraphQL query, and prints the `bee_surface_update` call (`--open TITLE` for `bee_surface_open`). Exits non-zero, printing nothing, when GitHub can't be read. |
| `livemap-loop.sh SPEC.json` | Keeps that map live: redraws every 60 s, and heals a lost session (below). `livemap-loop.sh SPEC.json close` closes its surface, as does Ctrl-C. |
| `mkcalls.py`, `mksurvey.py` | Build the survey from a list of calls: id, title, tradeoff in Markdown, and options. |
| `decide_ui.py` | Re-renders the survey after an ack, a decision or a dismissal. It keeps `decided.json` beside itself. |
| `waitdecide.sh` | Waits for the next `decide` click, acknowledges it on the card at once, and exits so the agent's turn picks it up. |
| `dismissd.sh` + `handle.py` | Run without the agent: survey dismissals and drops into the Board's dismiss column. Exits on a `decide` so the agent sees it. |

`example/` holds the in-flight Board's opening call, and `heddle-overflow.json` with its
overrides `heddle-overflow.ov.json`: the spec for the map a kuilt session kept on 2026-11-13.
The two per-plan maps this replaced (`live.py`, `live-id.py` and their loops, each with the plan
written into its Python) are in this branch's history at `615e5399`.

## A live map for your plan

Write a spec. Each node is one box: a `label`, and how to find its PR, which is one of an `issue`
(the newest PR whose body cites `#N`, opened after the issue), a `pr`, or a `branch` prefix.
Nodes sharing a prefix are a stack, and its PRs go to them oldest first. Then its `deps`, an `est`
in minutes, an `owner` (anyone but `self` and `you` shows on the box), and an optional `pos`
`[column, row]`. A node with `"aggregate": true`, such as a release, waits on every other node
unless given `deps`. The full field list is at the top of `livemap.py`.

```json
{"repo": "tractat-us/kuilt", "title": "my plan: live dependency map", "surface": "my-map",
 "overrides": "my-plan.ov.json",
 "nodes": {
  "doc": {"label": "doc: design", "issue": 3192, "est": 40},
  "s1":  {"label": "1 core", "branch": "feat/3313", "owner": "peer", "est": 90},
  "s2":  {"label": "2 wire", "branch": "feat/3313", "owner": "peer", "deps": ["s1"]},
  "impl": {"label": "impl", "deps": ["doc", "s2"], "est": 180},
  "rel": {"label": "release (by hand)", "aggregate": true, "owner": "you"}}}
```

The overrides file holds what changes as the run goes. `note` marks a worker running before it has
a PR (`{"impl": {"note": "worker A", "started": "2026-11-14T03:38:00Z"}}`), so dispatched work never
reads "ready". `tag` is a remark that leaves the state alone (`{"impl": {"tag": "plan being
written"}}`). `pr` pins a PR the discovery got wrong, and `"_asks": [...]` adds lines to the panel's
"Waiting on you". Edit it while the loop runs; the next redraw picks it up. Then:

```sh
BEE_URL='http://127.0.0.1:8233/mcp?name=me' scripts/livemap-loop.sh my-plan.json
```

The loop keeps its session id, surface counter and log in `my-plan.state/` beside the spec.

**What the map promises.** Boxes sit one per cell of a grid, so none hides another, whatever the
graph: a pinned cell already taken moves down a row, with a warning. Boxes widen to fit their text,
up to a cap, past which it is cut with an ellipsis. Edges into an aggregate are not drawn: from every
sink they crossed every row and read as false dependencies. The header says what feeds it instead.
A box is "in flight" only with a PR or a `note`. Above the map, a panel shows **Next check-in**,
**Waiting on**, **Can start now** (unblocked, not dispatched, owned by `self`) and **Waiting on
you**.

**Why the maps went grey, and how the loop heals.** The host closes an MCP session quiet for two
minutes, and the first loop slept 120 s, right on that edge. From then on every update got a 404
with `"Session not found"`, but `curl` exits 0 on that, so nothing noticed and the map froze for an
evening. A new session is a new peer, and the board greys the surfaces of the old one, so a surface
id can't simply be reused. `livemap-loop.sh` redraws every 60 s, well inside the timeout. When a call
fails anyway, it opens a new session, reopens the map under the next surface id, and closes the
stale one.

## Known rough edges, next iterations

- **The plan's shape is still hand-written.** The spec names every node and edge; only PRs and
  their states are discovered. The identity map derived its nodes from an epic's sub-issues, and
  `livemap.py` doesn't yet. A spec field naming the epic would bring that back.
- **ETAs assume unlimited workers.** A pending box starts when its last dependency ends. The
  identity map modelled two workers and a serial slice; the generator dropped that.
- **A stack's PRs are matched by age.** The oldest open-or-merged PR on the prefix goes to the
  stack's first node. A slice that is reopened as a new PR shifts the rest; pin it with `pr`.
- **Edges can cross boxes.** The grid stops boxes overlapping each other, not lines running behind
  them. A pinned layout fixes it by hand.
- **Everything runs inside the agent's session.** The loops stop when the session ends. A
  launchd job, or a host-side notion, would outlive it.
- **Every lapse costs a surface.** Healing works, but each one opens a new surface id and greys
  the old one until it closes. The host's side is filed: a long-lived script should be able to keep
  its session or rebind its surfaces ([bee#237](https://github.com/tractat-us/bee/issues/237)), and
  an expired session's 404 should say it expired and what it stranded
  ([bee#238](https://github.com/tractat-us/bee/issues/238)).
- **The Sketch is light-themed only.** It sets its own white background so it reads in either
  theme, but it does not follow dark mode.
- **Several of these belong in bee itself.** Candidates are a survey Notion with optional depth
  and an instant ack, a per-surface dismiss (bee#234), and a "check-in" convention for agent
  status surfaces. The draft PRs on presenting and surveying (#161) and on a notion skill (#147)
  are the natural homes to compare against.
