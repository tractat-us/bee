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
| `beecall.sh <method> <params-json-or-file>` | One MCP JSON-RPC call. It opens a session on first use and caches its id beside itself. Each request gets a random id, because a fixed id collides with a concurrent long-poll ("Request id is already in use"). |
| `live.py` + `live-loop.sh` | The live map: reads `live-state.json` (tasks, dependencies, PR numbers, estimates), asks GitHub with one GraphQL query, and redraws the `heddle-plan` Sketch every 120 s. |
| `mkcalls.py`, `mksurvey.py` | Build the survey from a list of calls: id, title, tradeoff in Markdown, and options. |
| `decide_ui.py` | Re-renders the survey after an ack, a decision or a dismissal. It keeps `decided.json` beside itself. |
| `waitdecide.sh` | Waits for the next `decide` click, acknowledges it on the card at once, and exits so the agent's turn picks it up. |
| `dismissd.sh` + `handle.py` | Run without the agent: survey dismissals and drops into the Board's dismiss column. Exits on a `decide` so the agent sees it. |

`example/` holds the state the session ended with: `live-state.json` and the in-flight Board's
opening call.

## Known rough edges, next iterations

- **The map's skeleton is hand-kept.** Which PR is which task, worker start times and estimates
  live in `live-state.json`, edited by the agent. The next step is discovering PRs by
  `Part of #N` in their bodies and matching titles to tasks.
- **Everything runs inside the agent's session.** The loops stop when the session ends. A
  launchd job, or a host-side notion, would outlive it.
- **The session id and cursor are cached in files beside the scripts.** A "Session not found"
  after a host restart is handled by re-initialising, but two loops can race to do it.
- **The Sketch is light-themed only.** It sets its own white background so it reads in either
  theme, but it does not follow dark mode.
- **Several of these belong in bee itself.** Candidates are a survey Notion with optional depth
  and an instant ack, a per-surface dismiss (bee#234), and a "check-in" convention for agent
  status surfaces. The draft PRs on presenting and surveying (#161) and on a notion skill (#147)
  are the natural homes to compare against.
