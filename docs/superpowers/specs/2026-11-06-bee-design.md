# Bee: design

**Date:** 2026-11-06 · **Status:** draft for approval · **Owner:** Iain Keddie

Decisions are marked **(Iain)** when they came from Iain in the design conversation, and
**(design call)** when the writer made them. Push back on any design call.

## 1. The idea

A quilting bee is a group of people gathered around one quilt, each working their own corner and
talking while they sew. Bee gives a person or a small team, plus their coding agents, one live
board to gather around.

People talk out loud. The board carries what's worth seeing or capturing, and the agents redraw it
as the conversation moves. The board can be:

- a survey on a design question
- a vote, where everyone taps an answer while the room keeps talking
- a plain view of where things stand
- a work split: drag cards into people's columns, and each person's own tab shows their share
- a code review the group walks through together
- nothing at all: "clear the board", and talk about lunch

**Bee is a communication medium, not an app with screens (Iain).** The format is deliberately loose.
Nothing about the board's layout is fixed. When the group realizes the discussion has to change,
someone asks an agent and the board becomes something else.

### Who it is for, in order

1. **Iain alone, with many concurrent Claude sessions (Iain).** This is the first real use. Here
   the "others" around the board are his own sessions: workers report status, ask him to rule on
   things, and claim work. Today that traffic runs through text files and blocking prompts:
   `morning-report.md`, `next-plan.md`, `AskUserQuestion`, and Draft-PR claims.
2. **A small team at a hackathon, each with their own coding agent (Iain).** Bee arrives as an
   existing open-source tool, like someone's own editor. It is **not** the hackathon project.

### Why it matters

Today one agent always has context another lacks, and people carry it between them by pasting.
Screenshots arrive with no context, agreed follow-ups fall out of view, and a session's state dies
with the session. Bee makes the board the shared state. Anything on it is visible to every person
and agent attached, and it's still there when they come back.

## 2. Goals, non-goals, anti-goals

**Goals**

- One board, rendered the same for every attached peer, which agents compose freely.
- **The board outlives every peer (Iain).** Sessions start, `/clear`, crash and finish, and tabs
  close. A newcomer sees the board as the others left it, with no hand-off file needed.
- People act on the board directly (click, vote, drag), and agents see the result.
- "Someone needs to do X" is done by exactly one peer.
- When a peer dies holding work, **the board detects it and the current participants decide what
  to do (Iain).** Nothing reassigns itself.

**Non-goals for v1**

- More than one machine. v1 is one person on one laptop (see §5).
- Peer-to-peer browser networking (WebRTC), browser-held replicas (wasm), and offline-first
  phones.
- A general A2UI implementation. Bee uses an existing renderer.
- Bookings, payments, or any side effect beyond the board and the agents' own work.

**Anti-goals**

- **Building the tool instead of the work (Iain).** If using Bee costs more attention than it saves,
  it has failed, whatever it can technically do. Bias every choice toward "useful in the first hour".
- Silent automation of a group decision. Reassigning, discarding or overriding someone's work is
  always a visible choice.
- A board that leaks. Free-form surfaces make it easy to paste something sensitive. Bee must make
  what's shared, and with whom, obvious (§9).

## 3. Success

**The acceptance test (Iain): the brainstorming skill's visual companion runs on Bee.** Today the
companion is a small single-session server: an agent writes an HTML file to `screen_dir`, the
browser shows the newest one, and clicks land in `state_dir/events`. In this very conversation it
idled out and lost its session. Bee is done for v1 when that skill pushes its screens, and reads
its clicks, through Bee instead:

| Companion today | On Bee |
|---|---|
| Write an HTML file to `screen_dir` | An agent opens or updates a surface (a `Sketch` for free-form mockups) |
| Clicks appear in `state_dir/events` | Action records the agent reads |
| Push `waiting.html` | Clear the board |
| Idle timeout loses the session | The board outlives the server and every session |

**Where that change lands (design call): a Bee-side adapter first.** The companion lives in the
portable `agent-scheme` plugin (`tractat-us/claude-plugins`), which also runs on machines with no
Bee. So v1 doesn't touch it. A Bee adapter emulates the companion's contract: it watches
`screen_dir`, shows each new screen as a `Sketch` surface, and writes clicks back to
`state_dir/events`. Teaching the plugin to prefer Bee when it's present, with a fallback, is a
later and separate PR in that repo.

**Secondary test:** two concurrent Claude sessions plus Iain use one board for a working day. One
session asks a question and Iain answers it on the board. One session claims a card, the other
sees the claim, and killing the claimant shows up on the board.

## 4. How it works, in plain terms

There are three kinds of peer:

- **Browser tabs**, where people look and click.
- **Claude sessions**, each attached through its own small MCP bridge.
- **The Bee daemon**, which is always on and holds the board.

The daemon keeps the board as a shared, mergeable document. A tab shows it. A session reads it and
changes it through MCP tools. When anything changes, every attached peer sees the change.

When you tap a button, two things happen:

1. **Your tab updates at once.** The edit goes into the board's data.
2. **An action record is written onto the board.** It says who did what, and what they could see
   when they did it. Whichever agent wired that button picks the record up, does the work, and
   writes the result back as another edit. If that agent isn't attached right now, the record waits.

When something must be done by exactly one peer, a peer **claims** it. The claim goes through a
vote among the voters, so two peers can't both win. If the claimant disappears, the board says so
and the people present decide what happens next.

## 5. Architecture (v1)

**Design call: in v1 the board lives in one always-on JVM daemon, not in the browser.** This departs
from the earlier brainstorm's handoff, which put the replica in the browser tab and synced tabs over
WebRTC. Here's why:

- kuilt-crdt and kuilt-raft do build for wasmJs. A replica in the browser would still bring
  IndexedDB persistence, WebRTC between tabs, and a replica per tab into v1. On the JVM daemon none
  of that is needed.
- The official A2UI web renderer can stay stock. kuilt sits *behind* the A2UI protocol, and the
  renderer never sees a CRDT.
- Spikes 1 and 2 from the handoff (WebRTC without STUN or TURN, signalling over a WebSocket) and
  the browser half of spike 3 drop out of v1. They aren't ruled out, only deferred.

```
Claude session A ── bee-mcp (stdio) ─┐
Claude session B ── bee-mcp (stdio) ─┼── bee daemon (JVM) ── WebSocket ── browser tab(s)
Worker C ────────── bee-mcp (stdio) ─┘     replica · presence · claims · disk
```

### 5.1 The daemon

- Holds the **always-on board replica**: every surface as a kuilt `JsonCrdt` document.
- **Each MCP bridge is a replica too (design call).** The bridges and the daemon replicate with
  kuilt's `Quilter` over a localhost WebSocket fabric: `KtorServerLoom` on the daemon,
  `KtorClientLoom` in each bridge. This is what makes concurrent writers real (spike 2), lets a
  session read the board locally, and gives "what the actor had seen" a causal meaning (§6.2). It's
  also the same shape as several machines later, so adding a second daemon is a topology change,
  not a rewrite.
- **Browser tabs are not replicas.** They speak A2UI only (§5.3).
- Holds **presence** in an `EphemeralMap`, keyed by peer.
- Runs **claims** over `kuilt-raft` (§6.3).
- Persists everything durable through kuilt's `DurableStore` (`FileChannelDurableStore` on JVM), so
  a daemon restart loses nothing but presence.
- Serves the browser over a WebSocket, speaking **plain A2UI** (§5.3).
- Accepts bridge connections from MCP bridges on localhost.

### 5.2 The MCP bridge (`bee-mcp`)

- A stdio MCP server, one per Claude session, launched by Claude Code from its MCP config.
  **(design call)** Using one process per session means the bridge's lifetime *is* the session's.
  When the bridge disconnects, the session crashed or ended, and kuilt's liveness detection
  (`kuilt-liveness`) reports it as a departure.
- Gives each session a **peer identity** (session name plus a fresh instance id), distinct from
  the transport connection. A peer identity has a kind, **human** or **agent** (design call). All of
  a person's browser tabs are one human peer, and a tab is just a connection of that peer, so a
  `Poll` counts people and agents, not tabs.
- **`/clear` is a departure (design call).** A stdio MCP server normally survives `/clear`, but the
  agent has lost its context, so its claims should not stay live. A `SessionStart` hook with
  `source=clear` tells the bridge to retire its identity and take a fresh one. The old identity's
  claims become orphaned (§6.4). Spike 3 verifies that the bridge does survive `/clear` and that
  the hook fires.
- Exposes tools for: reading the board; opening, updating and closing surfaces; reading and
  answering action records; claiming and releasing work; and posting presence ("working on X").
  The exact tool list is left to the plan.
- **Waiting without polling (design call):** a tool that blocks until a matching action record
  arrives, with a timeout. This is the replacement for reading `state_dir/events`.
- **Records are addressed to a surface, never to a session instance (design call).** Session
  identities are fresh each time, so a record addressed to one would wait forever once that session
  ended. Instead, any attached session may pick up a record on a surface it handles, and **handling
  always goes through a claim** (§6.3), so two sessions never both act on one click.

### 5.3 The browser and the A2UI boundary

- **Pinned protocol and renderer (design call):** A2UI **v0.9.1** with the official **Lit** (Web
  Components) renderer. Lit is stable for v0.9 and framework-free. v1.0 is a release candidate, so
  re-pin when it lands.
- The daemon is the browser's A2UI server. It sends `createSurface`, `updateComponents`,
  `updateDataModel` and `deleteSurface`, and receives `action`.
- **The CRDT is the source of truth, and the renderer is a view.** A2UI's `updateDataModel`
  replaces (upserts) a value at a JSON Pointer path. The daemon turns each merged CRDT change into
  those replacements for the browser. In the other direction it turns each browser `action` into a
  CRDT edit plus an action record. Merge rules live only in the daemon, never in the renderer.
- **Concurrent values need a projection.** `JsonCrdt` leaves are `MVRegister`s, which keep *every*
  concurrently written value, but A2UI's `updateDataModel` takes exactly one. The daemon projects a
  deterministic winner for display, and marks the conflict so a surface can show it ("two answers")
  instead of hiding it. Spike 2 picks the rule.
- **Open for the spike:** in v0.9, bound inputs such as a `TextField` update the renderer's *local*
  model, and send it only on an action with `sendDataModel`. Live shared typing needs Bee's page to
  observe local data-model changes (likely through `@a2ui/web_core`) or a Bee component that does.
  Spike 4 settles this.

### 5.4 The Neon catalog

Bee's custom A2UI catalog, advertised through `supportedCatalogIds`. **The name "Neon" is a design
call, a nod to Iain's A2O aside.** It starts with:

- **`Sketch` (Iain: include now, replace later).** Agent-supplied HTML in a sandboxed iframe
  (`sandbox` without `allow-same-origin`, and no network beyond the page). It's the mockup escape
  hatch the visual companion needs. Clicks inside it reach the daemon as A2UI actions through a
  `postMessage` bridge. When A2UI gains its own way to embed content, Bee switches to it.
- **`Board`**: columns and draggable cards. It's the work split, where a drop is an action.
- **`Poll`**: a question, options, and live per-peer answers.
- **`Microphone` (later):** A2UI's basic catalog has no audio input. A placed microphone
  captures in the browser and returns a transcript as an action. Before it ships it needs a consent
  rule, because it records everyone in the room, not just the person who asked.

The basic catalog (`Text`, `Card`, `Row`, `Column`, `Button`, `ChoicePicker`, …) covers the rest.

### 5.5 Later: more than one machine (not v1)

Each person runs their own daemon. Daemons replicate through one shared relay host
(`KtorRoomHost`), making outbound connections only, which matches the constraint that peers can't
accept inbound connections. The host keeps a replica so a late-night change reaches the others by
morning, and it votes in Raft but never claims, so a lone peer plus the host is a majority. The
browser stays a renderer throughout. Where the host runs, especially for an employer's hackathon
on employer code, is the hosting party's call.

## 6. The board's state

### 6.1 Surfaces

A **surface** is an A2UI component tree plus its data model, held as one `JsonCrdt` document with
a stable surface id. The board is the set of open surfaces plus their layout.
**"Clear the board" closes surfaces. It doesn't delete them (design call):** closed surfaces move
to a history strip and can be reopened with their results. This takes the persistence goal at its
word. A lunch break shouldn't erase the morning's vote.

Two concurrent edits to one surface must still render. That's the handoff's spike 4, and the
mapping between `JsonCrdt` merges and A2UI path replacement is where the risk sits. kuilt#2469
(nested-write cost, and the concurrent-delete choice) is related.

### 6.2 Action records

An **action record** is an immutable entry on the board, written when a person or agent triggers a
component. It holds:

- a record id
- the surface and component
- the actor (a peer identity)
- the action name and payload
- **the basis**: what the actor had seen when they acted

The basis follows kuilt#2869's input-history contract. A record keeps the causal context it was
made in, and merging it later can never make it look as though it saw newer state. That lets a
handler tell "the clicker voted before the third option was added" from "the clicker chose not
to". **Design call:** records live in a dot-carrying sequence (kuilt `Rga`), per #2869's ruling
that input identities come only from dot-carrying types.

**Capturing the basis (design call):**

- **An agent's action** takes its basis from its bridge's own replica at the moment it acts.
- **A browser click** has no causal context of its own, because an A2UI `action` carries none.
  The daemon therefore records, for each tab connection, the board version it last sent that tab,
  and stamps that version as the click's basis. A click can't claim to have seen something its tab
  hadn't yet been sent.

How a surface's author declares which records are theirs is open (§10).

### 6.3 Claims: exactly one

A **claim** says "peer P takes action record or card A". It commits as a `kuilt-raft` log entry,
and the committed outcome is mirrored onto the board so everyone sees it. An action that can't be
claimed yet, because no quorum is reachable, waits rather than risk running twice. kuilt's
exactly-once dedup design (`docs/superpowers/specs/2026-06-16-raft-exactly-once-dedup-design.md`
in kuilt) is the starting point.

**In v1, on one laptop, the voter set is the daemon alone, so a claim is a local commit (design
call).** That's deliberate. It keeps the claim API and its board mirror identical when the relay
host and other daemons join as voters later. The daemon is a voter that never claims. Sessions
claim through it.

### 6.4 Presence and failure

- Presence is kuilt's `EphemeralMap`. Each peer shows as attached, with an optional "working on…"
  line.
- **Crashed** means the bridge connection is gone, detected by `kuilt-liveness`. The peer's claims
  show as **orphaned**: still held, flagged, and never auto-reassigned **(Iain)**.
- **Quiet** means the bridge is alive but the agent hasn't acted for a while (for example, a long
  build). It shows as "quiet since …", not as failed **(design call)**. A quiet claim doesn't
  lapse on its own. How long before the board prompts is open (§10).
- An orphaned claim produces a decision surface (for example, a `Poll`: reassign to …, release,
  wait). The decision commits as a new claim or a release through Raft, like any other.

## 7. Bee and kuilt

- **Bee is its own repo, `tractat-us/bee`, beside kuilt (Iain).** It's private while it starts and
  meant to be open source under **AGPL-3.0 (Iain)**. **Every outside contribution requires a
  strict CLA that assigns all rights to Iain (Iain)**, so relicensing (for example, moving a piece
  into Apache-licensed kuilt, or a separate grant to an employer) stays his to decide. kuilt may reference public `tractat-us` projects **(Iain)**,
  which needs a matching edit to kuilt's References policy (§11).
- **Generic pieces graduate to kuilt** once Bee has proven them: surfaces on `JsonCrdt` (including
  the A2UI mapping, if it stays generic), action records with a basis, exactly-once claims, a
  voter that never claims, and anything presence or liveness needs. Bee owns the daemon, the MCP
  bridge, the Neon catalog, policy and deployment.
- **A kuilt peer session handles kuilt asks during development (Iain).** When Bee hits a kuilt gap,
  it files a kuilt issue and hands it to that session, a separate Claude Code session in the kuilt
  checkout. Bee never patches kuilt locally.
- Bee consumes kuilt as published snapshots, with `if (file("../kuilt").exists())
  includeBuild("../kuilt")` for side-by-side work.

## 8. Bee and kuilt#2869

**Bee is a separate epic. #2869 stays paused (Iain).** #2869 is a research epic with hypotheses
fixed in advance on a dinner story, so changing its story mid-M2 would void its baseline. Bee
borrows from it:

- the input-history contract (§6.2) and M1's `CONTRACTS.md`, frozen in kuilt at
  `demo/workspace/` (commit `53f89b34`)
- M2's reconnect shape (A), how a bridge's replica survives losing its connection to the daemon,
  from `docs/spikes/2026-10-reconnect-shape.md` in `tractat-us/kuilt-workspace` (private, checked
  out at `~/tractatus/kuilt-workspace`)
- the re-check of a shown answer when the viewer learns more (kuilt#2880). Its code was removed
  from kuilt and survives only at `53f89b34`, so it's borrowable as a design, not as a dependency.

Bee also answers #2869's M5 question, whether A2UI earns its place over a fixed screen, for a case
that has no fixed screen at all.

**Revisit #2869 at three checkpoints (Iain):** after this spec, after the plan, and during execution.
At each one, look for pieces to pick up in either direction.

## 9. Constraints and risks

- **Sharing must be obvious.** A Claude session can put anything on the board: a diff, a log, a
  secret it found. v1 is single-user, so the risk is small, but the bridge's tools should make
  "this goes on the shared board" an explicit act, never a side effect. Before a second person
  attaches, there must be a rule for what may never be written to the board.
- **`Sketch` runs agent-written HTML.** It's sandboxed and has no same-origin access. The daemon
  never executes surface content.
- **IP.** Bee is built on Iain's own time and equipment as open source. It's brought to an employer
  hackathon as an existing tool, never built there. Hosting there is the employer's call.
- **Keep it small.** Each piece earns its place by being needed in the first hour of real use.

## 10. Left to the builder or the plan

- The exact MCP tool list and its shape.
- How a surface's author declares which action records it handles, and how handlers find them.
  Candidates: a handler field on the component, or a subscription by surface.
- The quiet-period threshold before the board prompts about a quiet claim.
- The layout and history-strip UI.
- Whether the A2UI mapping is generic enough to graduate to kuilt, or stays in Bee.
- The exact peer identity format. The rules are in §5.2: fresh per session, kind human or agent,
  and retired on `/clear`.

## 11. Follow-ups outside Bee

- **kuilt References policy:** amend kuilt's `AGENTS.md` to allow references to public `tractat-us`
  projects (Iain's ruling). That's a kuilt PR, best landed once Bee is public.
- **Workspace index:** Bee added to `~/tractatus/CLAUDE.md` (done).
- **The CLA:** the assignment agreement's text (have a lawyer write it, not an agent) and a signing
  check on PRs. Both are needed before the repo accepts outside contributions. `CONTRIBUTING.md`
  says so meanwhile.
- **Epic issues:** a Bee epic in `tractat-us/bee`, and a kuilt tracking issue for the primitives
  Bee will need. Each links the other and kuilt#2869.

## 12. Spike order (proposed)

1. **Daemon and renderer.** The daemon serves one hard-coded A2UI surface to the stock Lit renderer.
   A button click arrives as an action. Proves the A2UI boundary and pins versions.
2. **`JsonCrdt` behind A2UI.** Two writers edit one surface concurrently, and the merge renders
   correctly. Settles live shared typing (§5.3). (Handoff spike 4.)
3. **MCP bridge.** A Claude session opens a surface, waits for an action record, and answers it.
   A second session sees it. Kill one, and the board shows it gone. (Handoff spike 5.)
4. **Claims.** Two sessions race for one card, and exactly one wins. Kill the winner, and the claim
   shows as orphaned. (Handoff spike 6, single voter.)
5. **`Sketch` and the companion.** The brainstorming companion's screens render through Bee. This
   is the acceptance test (§3).
6. **Durability.** Restart the daemon with surfaces, records and claims intact. (Handoff spike 3,
   JVM half.)
