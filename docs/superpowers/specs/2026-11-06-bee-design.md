# bee: design

**Date:** 2026-11-06 · **Status:** draft for approval · **Owner:** Iain Keddie

**Amended 2026-11-06 by the [v1 plan](../plans/2026-11-06-bee-v1.md#spec-amendments).** Its
amendments A1–A14, its decisions and Iain's LAN pairing ruling are folded into the sections below.

Decisions are marked **(Iain)** when they came from Iain in the design conversation, and
**(design call)** when the writer made them. Push back on any design call.

## 1. The idea

A quilting bee is a group of people gathered around one quilt, each working their own corner and
talking while they sew. bee gives a person or a small team, plus their coding agents, one live
board to gather around.

People talk out loud. The board carries what's worth seeing or capturing, and the agents redraw it
as the conversation moves. The board can be:

- a survey on a design question
- a vote, where everyone taps an answer while the room keeps talking
- a plain view of where things stand
- a work split: drag cards into people's columns, and each person's own tab shows their share
- a code review the group walks through together
- nothing at all: "clear the board", and talk about lunch

**bee is a communication medium, not an app with screens (Iain).** The format is deliberately loose.
Nothing about the board's layout is fixed. When the group realizes the discussion has to change,
someone asks an agent and the board becomes something else.

### Who it is for, in order

1. **Iain alone, with many concurrent Claude sessions (Iain).** This is the first real use. Here
   the "others" around the board are his own sessions: workers report status, ask him to rule on
   things, and claim work. Today that traffic runs through text files and blocking prompts:
   `morning-report.md`, `next-plan.md`, `AskUserQuestion`, and Draft-PR claims.
2. **A small team at a hackathon, each with their own coding agent (Iain).** bee arrives as an
   existing open-source tool, like someone's own editor. It is **not** the hackathon project.

### Why it matters

Today one agent always has context another lacks, and people carry it between them by pasting.
Screenshots arrive with no context, agreed follow-ups fall out of view, and a session's state dies
with the session. bee makes the board the shared state. Anything on it is visible to every person
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

- A team deployment. v1 is one person, with the host on their laptop (§5). Other browsers on the
  same network, such as a conference-room screen, may join it once a person there admits it
  (§5.7).
- Peer-to-peer browser networking (WebRTC). Everyone talks through the host.
- The phone app. It comes after v1, as a native shell around the web client (§5.5).
- A general A2UI implementation. bee uses an existing renderer.
- Bookings, payments, or any side effect beyond the board and the agents' own work.

**Anti-goals**

- **Building the tool instead of the work (Iain).** If using bee costs more attention than it saves,
  it has failed, whatever it can technically do. Bias every choice toward "useful in the first hour".
- Silent automation of a group decision. Reassigning, discarding or overriding someone's work is
  always a visible choice.
- A board that leaks. Free-form surfaces make it easy to paste something sensitive. bee must make
  what's shared, and with whom, obvious (§9).

## 3. Success

**The acceptance test (Iain): the brainstorming skill's visual companion runs on bee.** Today the
companion is a small single-session server: an agent writes an HTML file to `screen_dir`, the
browser shows the newest one, and clicks land in `state_dir/events`. In this very conversation it
idled out and lost its session. bee is done for v1 when that skill pushes its screens, and reads
its clicks, through bee instead:

| Companion today | On bee |
|---|---|
| Write an HTML file to `screen_dir` | An agent opens or updates a surface (a `Sketch` for free-form mockups) |
| Clicks appear in `state_dir/events` | Action records the agent reads |
| Push `waiting.html` | Clear the board |
| Idle timeout loses the session | The board outlives the server and every session |

**Where that change lands (design call): a bee-side adapter first.** The companion lives in the
portable `agent-scheme` plugin (`tractat-us/claude-plugins`), which also runs on machines with no
bee. So v1 doesn't touch it. A bee adapter emulates the companion's contract: it watches
`screen_dir`, shows each new screen as a `Sketch` surface, and writes clicks back to
`state_dir/events`. Teaching the plugin to prefer bee when it's present, with a fallback, is a
later and separate PR in that repo.

**Secondary test:** two concurrent Claude sessions plus Iain use one board for a working day. One
session asks a question and Iain answers it on the board. One session claims a card, the other
sees the claim, and killing the claimant shows up on the board.

## 4. How it works, in plain terms

Everything attached to the board keeps its own copy of it:

- **A browser**, any browser: your laptop, a conference-room screen, a guest's. Nothing to install.
- **A Claude session**, attached through its own small MCP bridge.
- **The bee host**, which is always on. It relays changes between everyone, keeps a backup copy,
  and serves the web page.

Each copy is a shared, mergeable document. When one copy changes, the change flows through the host
to all the others, and every screen updates. A copy that was away catches up when it returns.

When you tap a button, two things happen:

1. **Your screen updates at once.** The edit goes into your browser's own copy of the board.
2. **An action record is written onto the board.** It says who did what, and what they could see
   when they did it. Whichever agent wired that button picks the record up, does the work, and
   writes the result back as another edit. If that agent isn't attached right now, the record waits.

When something must be done by exactly one peer, a peer **claims** it. The claim goes through a
vote among the voters, so two peers can't both win. If the claimant disappears, the board says so
and whoever is present decides what happens next.

## 5. Architecture (v1)

**Push as much as possible into the browser (Iain).** A browser is a full peer, with its own kuilt
replica, so any screen in the building can join with nothing installed. The host shrinks to what
only an always-on machine can do. This is close to the earlier handoff's shape, with one change:
WebRTC is deferred, and everyone talks through the host over WebSockets, outbound only.

```
browser (any)  ── bee client: kuilt replica (wasm) + Lit renderer ─┐
browser (any)  ── bee client                                        ├── WebSockets ─ bee host (JVM)
Claude session ── bee-mcp: kuilt replica (JVM) + MCP tools ────────┤               relay · replica on disk
Claude session ── bee-mcp                                           ┘               voter · serves the page
```

### 5.1 The bee client (browser)

- **One Kotlin Multiplatform client, compiled to wasm for the browser (Iain's direction; the module
  shape is a design call).** It holds:
  - the **board replica**: every surface as a kuilt `JsonCrdt` document, replicated by kuilt's
    `Quilter`
  - durable local state in kuilt's `IndexedDbDurableStore`, so a reload or a closed laptop loses
    nothing
  - two connections to the host, one per endpoint (§5.4)
  - an **in-page A2UI driver** that turns the replica into A2UI messages for the renderer, and the
    renderer's actions back into edits (§5.3)
- kuilt's CRDT, Raft and WebSocket modules all build for wasmJs.
- **Two endpoints, one identity rule each (design call).**
  - The board runs reconnect shape (A), from #2869's M2 (§8), at `/ws/board`:
    `KtorMeshClientLoom` spokes into the host's `hostedOverlay`, a fresh fabric `PeerId` per
    weave, and a `Quilter` rebuilt per connection from the held state.
  - Claims run kuilt's `clusterClient` over `KtorClientLoom` (the Js engine in browsers) into
    `serverCluster`'s `KtorRoomHost` at `/ws/claims`. `ClusterClient` owns its `Room` privately,
    so the browser's `Quilter` needs that second socket.
  - Raft identity is per connection, and bee's human identity sits above it.
  - No kuilt test has dialled a real WebSocket from wasmJs yet, so Spike 4's browser smoke is the
    first evidence.
- **Browser identity (design call).**
  - The person's name is asked once and kept in `localStorage["bee.human.name"]`.
  - Every tab is its own replica, under a fresh CRDT author per page load.
  - Only the tab holding the Web Lock `bee-board-store` writes the IndexedDB snapshot.
  - A room screen is a named human peer opened with `?screen=<name>`, and it never proposes a
    claim.
- **Spike 1 is the glue (Iain: "good with the new spike").** Kotlin/wasm and the JavaScript renderer
  have to share one page: messages in, actions out. It's the newest and riskiest seam, so it goes
  first.

### 5.2 The MCP bridge (`bee-mcp`)

- A stdio MCP server, one per Claude session, launched by Claude Code from its MCP config. It's the
  same client core as the browser, compiled for the JVM, holding its own replica and connecting to
  the host the same way. Its lifetime *is* the session's **(design call)**: when it disconnects,
  the session crashed or ended.
- Gives each session a **peer identity** (session name plus a fresh instance id), distinct from
  the transport connection. A peer identity has a kind, **human** or **agent** (design call). All of
  a person's browser tabs are one human peer, and a tab is just a connection of that peer, so a
  `Poll` counts people and agents, not tabs.
- **The identity format (design call).** An agent is `agent:<name>#<8 hex>`, fresh per bridge
  identity, where `name` is `$BEE_SESSION_NAME`, else the working directory's basename. A human is
  `human:<name>`, the same across tabs and devices. Only agents depart (§6.4).
- **CRDT authors are not peer identities (design call).** A replica writes as `web-<uuid>` per page
  load, `mcp-<uuid>` per bridge process, or `host-<uuid>` per host boot.
- **`/clear` is a departure (design call).** A stdio MCP server normally survives `/clear`, but the
  agent has lost its context, so its claims should not stay live. A `SessionStart` hook with
  `source=clear` tells the bridge to retire its identity and take a fresh one. The old identity's
  claims become orphaned (§6.4). Spike 3 verifies that the bridge does survive `/clear` and that
  the hook fires.
- **The `/clear` channel (design call).** At start, the bridge writes `~/.bee/run/<claudePid>.json`
  (`{"port":N}`) and serves `POST /retire` on loopback. `claudePid` is the nearest ancestor process
  named `claude`. The `SessionStart` hook finds the same pid by the same rule and POSTs to it.
- **The tools (design call).** Every writing tool's description starts "Puts … on the shared
  board, visible to everyone attached", so sharing is always an explicit act (§9).
  - Board: `bee_board`, `bee_surface_open`, `bee_surface_update`, `bee_surface_close`,
    `bee_board_clear`.
  - Records: `bee_wait_action`, `bee_list_actions`, `bee_action_done`.
  - Claims and presence: `bee_claim`, `bee_release`, `bee_presence`.
- **Waiting without polling, bounded (design call).** `bee_wait_action` holds until a matching
  action record arrives, and returns within 45 s, or a caller-chosen time up to 60 s. It returns
  `{record?, timedOut, cursor}`, and sends progress notifications while it holds.
  `bee_list_actions(since)` reads without blocking. This is the replacement for reading
  `state_dir/events`. Bounded waits make a long hold unnecessary, so Spike 3 doesn't measure a
  30-minute one **(Iain: dropped)**.
- **Records are addressed to a surface, never to a session instance (design call).** Session
  identities are fresh each time, so a record addressed to one would wait forever once that session
  ended. Instead, any attached session may pick up a record on a surface it handles, and **handling
  always goes through a claim** (§6.3), so two sessions never both act on one click.
- **A session handles a surface by subscribing to it (design call).** Waiting on a surface is how
  a session handles it, and any session may wait on any surface.
- **The bridge validates what it writes (design call)** against the Notions schema (§5.6), and
  refuses a malformed surface with an error that names the bad id or type.
- **A retired bridge re-identifies before it acts (design call).** If the bridge finds its identity
  retired (§6.4), it takes a fresh one through the `/clear` path, and refuses the board write it was
  asked for. It reads retirement from its claim table and from the board's mirror of every
  committed `Depart`, so the check holds while claims are offline, and while a fresh claims link
  catches up from an empty table.
- **When claims are offline (Task 7).** The bridge rebuilds a dead claims link itself, with
  backoff, because a refused dial ends kuilt's `clusterClient` (kuilt#3110). A link counts as up
  once it has folded a committed entry. Until then, every claim tool answers "Claims offline:
  …", a wait holds for the link within its bound, and a `/clear` `Depart` or a release waits and is
  proposed again until it commits. Board writes still go ahead.
- **A claim of the bridge's own that no caller received is given back and claimed again, never
  taken over (Iain's ruling).** A late win, a claim whose link died after the voter appended it, or
  one whose release couldn't commit leaves the target held under the bridge's identity. The bridge
  sweeps each claims link's table as it catches up, so such a claim is given back on the next link
  even if no wait reaches that surface again (bee#31): it's marked as being given back and
  released, queued until the release commits. The sweep passes over a claim the bridge handed to a
  caller (a wait's record, or a `bee_claim` win), one a wait has in hand, and any target with a
  claim in flight, whose win isn't in anyone's hands yet. A wait that reaches such a claim first
  follows the same rule, then claims the record again under a fresh token. A release of the old
  token still on its way then finds a different token and is rejected, so it can't free a record a
  wait has returned. A claim the bridge handed to a caller is passed over; one another wait has in
  hand, or whose target has a claim in flight, is waited out.
  **Not yet excluded: a claim reassigned to this identity.** The sweep tells a claim nobody here
  received only by the bridge's own record, so it would give back a `Reassign` of an orphan to
  this session (§6.4) as if it were a stranded late win. Nothing proposes `Reassign` before plan
  Task 11, which must add the provenance that tells the two apart.
- **`bee_board` reports the board and the session's claims link.** `open`, `closed`, `attached`
  (who is present, from presence), `departed`, `claimViews` (the host's claims mirror, by target)
  and `claims`, which is `online` or `offline` for this session's link.

### 5.3 The A2UI boundary, inside the page

- **Pinned protocol and renderer (design call):** the official **Lit** (Web Components) renderer,
  `@a2ui/lit` with `@a2ui/web_core`, framework-free. **The pin is a spec version.** Spike 1 pins
  exact package versions and records which spec version they implement, and uses v1.0 only if
  **both** packages export it. On 2026-11-06, `@a2ui/web_core@0.15.0` exports `./v1_0`, but
  `@a2ui/lit@0.15.0` exports only `.`, `./ui`, `./0.8`, `./v0_8` and `./v0_9`, so today it's
  **v0.9.1** (`version: "v0.9"`).
- The in-page driver plays the A2UI server's part. It feeds the renderer `createSurface`,
  `updateComponents`, `updateDataModel` and `deleteSurface`, and receives `action`. The renderer is
  stock and never sees a CRDT.
- **The CRDT is the source of truth, and the renderer is a view.** A2UI's `updateDataModel`
  replaces (upserts) a value at a JSON Pointer path. The driver turns each merged CRDT change into
  those replacements, and each `action` into a CRDT edit plus an action record. Merge rules live
  only in the replica, never in the renderer.
- **The driver diffs; it never applies deltas (design call).** `Quilter.state` is a conflated
  `StateFlow`, so a delta can be skipped. The driver diffs the view it last rendered against the
  current one, and replaces an array whole when its ids change. It validates each surface before
  rendering it, and shows a placeholder naming the errors instead of an invalid one. bee builds
  this on kuilt's public API, and it can graduate later.
- **How a surface is stored (design call).** Each A2UI component is stored whole, as one leaf
  holding its canonical JSON. A JSON array written as data is also stored whole, as one leaf. A
  data path never changes type, because an object-versus-scalar conflict silently deletes the
  losing subtree. §6.1 says which state is appended and which is edited.
- **Concurrent values need a projection.** `JsonCrdt` leaves are `MVRegister`s, which keep *every*
  concurrently written value, but A2UI's `updateDataModel` takes exactly one. The driver projects a
  deterministic winner for display, and marks the conflict so a surface can show it ("two answers")
  instead of hiding it.
  - **A register keeps every concurrent value only when a write starts from the register just
    read** (`leaf.register.set(replica, v)`). kuilt's documented `Leaf(MVRegister.empty().set(…))`
    creates phantom conflicts, so bee never writes that way.
  - **The winner (design call)** is the greatest value under a total order on `JsonValue`:
    `Null` < `Bool` < `Num` < `Str`, then by value, with strings compared by UTF-16 code unit.
    It's the same on every peer, never arrival order. The full set of values is published at
    `/_bee/conflicts/<escaped pointer>`.
- **Live shared typing.** In v0.9, bound inputs such as a `TextField` update the renderer's *local*
  model, and send it only on an action with `sendDataModel`. So the driver subscribes to
  `@a2ui/web_core`'s data model for every bound path and writes changes into the replica,
  debounced. It holds remote changes to a path the person has focused or left dirty until they
  leave it. Spike 2 proves this route.

### 5.4 The bee host

- **Relay.** Every peer dials the host. Nobody needs to accept inbound connections, which matches
  the handoff's network constraint. The board relay sits at `/ws/board` (`hostedOverlay` and the
  host's `Quilter`), and the claims voter at `/ws/claims` (`serverCluster` on a `KtorRoomHost`),
  as §5.1 describes. A browser dials with its key **and** `?board=<id>`; the host refuses a
  socket that names another board, or none, before the upgrade (bee#46).
- **The always-on replica**, persisted with `FileChannelDurableStore`, so the board outlives every
  peer, including every browser closing at once.
- **kuilt ships the stores; only the `Quilter` adapter is missing (design call).** `kuilt-store`
  has `FileChannelDurableStore` (JVM, takes a `File`) and `IndexedDbDurableStore` (wasmJs), and
  `kuilt-raft` has `DurableStoreRaftStorage`. For Raft it's configuration: open the storage first
  (`DurableStoreRaftStorage.open` suspends), then pass `storageFactory = { storage }`, because
  `serverCluster` otherwise defaults to `InMemoryRaftStorage`. The one gap is a
  `Quilter`↔`DurableStore` adapter that snapshots on change and seeds `initial` on start. It's
  generic, so bee proves it with no bee types, and it's a candidate for the kuilt tracking issue
  (§11).
- **The voter that never claims** (§6.3). It's also the only writer of the claims mirror and the
  departure list on the board.
- **Serves the web client** (the wasm bundle, the renderer and the Notions catalog), so a
  conference-room browser needs only a URL.
- **Its coordinates (design call).** Port `8233` ("BEE" on a keypad), data in `~/.bee/host/`,
  routes `/`, `/ws/board` and `/ws/claims`. The host has no write route: it writes to the board
  only through its own replica.
- **Where it runs.** Solo: on Iain's laptop. It binds loopback only unless it's started with
  `--lan` (design call), and then it's reachable on his network at `tractatus.local` (§5.7).
  For a team: on a shared server. For an employer's hackathon on employer code, that's the
  hosting party's call.

### 5.5 After v1: the phone app (Iain)

A "blank white" app for iPhone and Android: **a Compose Multiplatform frame around the web client
(Iain)**. Compose draws the app's own chrome (connect to a host, which board, presence), and the
board itself is the same web client in a WebView. kuilt is KMP throughout and the iOS and Android
build setup already exists, so the app costs little beyond the browser client. Later, a native
Compose renderer can replace the WebView once A2UI's official Compose renderer exists, and the
client core then runs natively instead of as wasm.

### 5.6 The Notions catalog

bee's custom A2UI catalog, advertised through `supportedCatalogIds` as
`https://tractat.us/bee/catalogs/notions/v1.json` (design call). **The name "Notions" is a design
call: in sewing, notions are the buttons, snaps and fasteners.** It holds the basic catalog's
components plus these:

- **`Sketch` (Iain: include now, replace later).** Agent-supplied HTML in a sandboxed iframe
  (`sandbox` without `allow-same-origin`, and no network beyond the page). It's the mockup escape
  hatch the visual companion needs. Clicks inside it reach the driver as A2UI actions through a
  `postMessage` bridge. When A2UI gains its own way to embed content, bee switches to it.
- **`Board`**: columns and draggable cards. It's the work split, where a drop is an action.
- **`Poll`**: a question, options, and live per-peer answers. Options and question revisions are
  appended, never edited, so a vote's basis can say which options the voter had seen (§6.1).
- **`Microphone` (later):** A2UI's basic catalog has no audio input. A placed microphone
  captures in the browser and returns a transcript as an action. Before it ships it needs a consent
  rule, because it records everyone in the room, not just the person who asked.

The basic catalog (`Text`, `Card`, `Row`, `Column`, `Button`, `ChoicePicker`, …) covers the rest.

Because the host can't refuse a CRDT write, the catalog's schema is enforced by the writers: the
bridge validates a surface before writing it (§5.2), and the browser driver before rendering it
(§5.3).

### 5.7 Letting a second screen in (Iain)

**A browser on the local network gets a key through Bluetooth-style pairing (Iain).** A browser
that arrives over the LAN without a key shows a six-digit code, and the board asks the current
participants to type it in. A matching code admits the browser and gives it a key. The flow is a
design call:

1. The keyless browser opens a pairing endpoint that carries no board or claims traffic. The host
   sends it a code and a pairing id, and the browser shows the code full screen.
2. The host puts a join request on the board ("A device wants to join: enter the code it shows").
   The code itself is never written to the board.
3. A participant types the code into that surface, and it arrives as an action record (§6.2). A
   match mints a key, sends it to the browser, and closes the request with "admitted by <name>".
4. The browser dials `/ws/board` and `/ws/claims` with its key. With `--lan` on, both refuse a
   non-loopback connection that has no valid key. Keys live in the host's memory, so a host restart
   re-pairs every LAN device.

**Only human peers may admit a device (Iain: humans only, for now).** This departs from the
orphan-decision rule in §6.4 on purpose: admission is access control, and a typed code proves
someone can see the new screen.

**The rule checks the kind a record declares, not a verified person (design call, bee#64).**
Nothing ties a record's actor to the connection that wrote it (bee#60). So any board writer can
enter a code as a person: every process on the host's machine, every keyed device, and an agent
driving a real browser. The code is the real proof: only the new screen shows it, so a forged
admit still needs someone who can see that screen. The rule stops a well-behaved agent, never a
hostile process on the host's machine, which already holds what an admit grants, since loopback
needs no key. "Admitted by <name>" names the declared actor.

- `Pairing.attempt` enforces the declared-kind check (`Attempt.NotHuman`, `PairingTest`).
- No new gate may rest on peer kind until actors are attested. Review holds that until the type
  below exists.
- **Open (bee#64), before a second person or a keyed agent attaches:** a keyed connection's actor
  must match the identity its key was minted for. That's generic, so it's a kuilt issue: the
  relay attests each record's author by its connection. `Pairing.attempt` then takes the attested
  type, so a declared identity no longer compiles there. Loopback stays trusted, because the host
  can't tell a person's browser from a process on its own machine.

Rejected for v1 (bee#64):

- **A host-served admit page with a CSRF-bound token, or a cookie set at page load.** Any local
  process can fetch the page and replay its token, and an agent can drive the page in a browser.
- **The #60 page-confirm.** The admit button is already page-owned UI, and this forgery is a
  hand-written record, not a confused deputy.
- **WebAuthn user verification on admit.** It alone stops a driven browser, but enrolment needs
  its own trusted channel, and a room screen has no authenticator. Revisit it if bee runs
  untrusted code on the host's machine.

The other details are design calls too. Loopback connections need no key. A key in the URL
(`?k=`) still admits, for a join link or QR code. A code expires after 2 minutes, and 3 wrong
entries void it.

Before any key check, every call must come from the host's own site (bee#45, bee#54). Its `Host`
must name the host, and its `Origin`, if any, must be the host's own. Its Fetch Metadata, if any,
depends on the path. The page alone takes a top-level navigation from anywhere, such as a join
link or a typed URL, but never a prefetch or a prerender (`Sec-Purpose`): Chrome would admit the
page, then see its subresources and sockets refused, and activate it blank, whereas a refusal makes
Chrome drop the speculation and navigate normally. Every other path, `/pair` included, takes only the page's own `same-origin`
calls and no prefetch, since no legitimate caller navigates there.

**This is an access gate, not transport security.** It runs over plain HTTP, and peer kind is
self-declared by trusted local peers. §9's sharing rule still governs what goes on the board.

## 6. The board's state

### 6.1 Surfaces

A **surface** is an A2UI component tree plus its data model, held as one `JsonCrdt` document with
a stable surface id. The board is the set of open surfaces plus their layout.
**"Clear the board" closes surfaces. It doesn't delete them (design call):** closed surfaces move
to a history strip and can be reopened with their results. This takes the persistence goal at its
word. A lunch break shouldn't erase the morning's vote.

**The layout (design call).** Open surfaces stack in the order they were opened (ties by surface
id). Closed surfaces sit in a collapsed history strip, newest first, and reopen with their records.

**Two kinds of state in a surface (design call).** A sequence element in kuilt's `Rga` is
write-once: it has insert, remove and compact, but no update, so two concurrent edits of one
element would duplicate it. So a surface keeps two kinds of state apart:

- **Appended content**: anything a basis must name, or a handler must reason about having seen
  (§6.2). Poll options and question revisions are appended entries in a `JsonNode.Array`, never
  edited, and action records are appended to the board's own `Rga`, outside any surface. Correcting an
  entry means removing it and appending a new one with a new identity.
- **Editable state**: cards, columns, components and data fields. It lives in an object keyed by a
  stable id, with its order kept in a separate array of ids that the projection dedupes, keeping
  the first occurrence. No basis names it.

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

**Anything a basis must name is an appended `Rga` entry, never an edited leaf (design call).**
This follows #2869's own rule, "a scalar input has no identity": its prototype appended an entry
rather than use a register. **`MVRegister` dots stay private (Iain)**, so bee asks kuilt for
nothing to open them.

- Poll options and question revisions are appended entries in a `JsonNode.Array` (§6.1).
- Each array's `Rga` mints sequence numbers on its own, so two arrays in one surface can hand out
  the same dot. So the basis keys dots by each array's JSON pointer, and never flattens them
  through `JsonCrdt.causalDots()`. Record dots, from the board's own `Rga`, are held apart from
  the per-pointer surface maps for the same reason.
- A scalar leaf, or any editable keyed state, is display state that no handler may reason about.

**Capturing the basis (design call):**

Every peer holds a replica, so every action takes its basis from the actor's own replica at the
moment it acts. For a browser click, the in-page driver stamps the record from that browser's
replica as it turns the A2UI `action` into an edit. A click can't claim to have seen something its
browser hadn't yet received. A handler whose replica lacks a dot the basis names treats the basis
as incomplete: unknown, never applicable.

A handler finds its records by surface: a session handles a surface by waiting on it (§5.2).

### 6.3 Claims: exactly one

A **claim** says "peer P takes action record or card A". It commits as a `kuilt-raft` log entry,
and the committed outcome is mirrored onto the board so everyone sees it. An action that can't be
claimed yet, because no quorum is reachable, waits rather than risk running twice. kuilt's
exactly-once dedup design (`docs/superpowers/specs/2026-06-16-raft-exactly-once-dedup-design.md`
in kuilt) is the starting point.

**In v1 the voter set is the host alone, so a claim commits once the host accepts it (design
call).** That's deliberate. It keeps the claim API and its board mirror identical when more voters
join later. The host votes but never claims. Browsers and bridges claim through it, which is the
shape of kuilt's `kuilt-cluster` (`ServerCluster` voters, `clusterClient` learners that forward
proposals with `requestId` dedup). The kuilt peer confirmed that `clusterClient` compiles for
wasmJs and that a single voter commits inline and never steps down. Spike 4 opens with a fit check
of the questions still open before it builds on `kuilt-cluster`.

**Claims are a state machine, and the host owns the mirror (design call).** `kuilt-cluster`'s
`ClientSessionTable` dedups one proposer's retries, not two peers racing, so bee adds a claim
table over the committed log:

- The first committed `Claim` of a free target wins, and later ones apply as rejected.
- A held claim carries a **fencing token**, the `(term, index)` of the log entry that granted it.
  A `Complete` or `Release` with a stale token applies as rejected.
- The host applies the committed log, and it's the only writer of the claims mirror and the list
  of departed peers on the board.

### 6.4 Presence and failure

- Presence is kuilt's `EphemeralMap`. Each peer shows as attached, with an optional "working on…"
  line.
- **Crashed** means the agent's claims link is gone: the voter drops its claims node after the 30 s
  learner grace, and bee departs the agent whose newest presence entry names that node (bee#28).
  The peer's claims show as **orphaned**: still held, flagged, and never auto-reassigned **(Iain)**.
- **Quiet** means the agent hasn't acted for a while (for example, a long build). It shows as
  "quiet since …" until a timeout, then **the session is declared dead, exactly as if it had
  crashed (Iain).** Telling dead from slow can't be done exactly, so bee picks a reasonable
  threshold and acts on it: **20 minutes since the poster's last tool call (design
  call).** A session waiting on a surface is never quiet.
- **"Dead" is a committed retirement (design call).** A crashed or quiet session is retired when
  the host commits `Depart(identity, reason)` through Raft, and the claim table rejects every later
  command from a retired identity. A bridge that finds itself retired re-identifies (§5.2). Only
  agents depart. A person's tabs come and go without retiring them.
- An orphaned claim produces a decision surface (for example, a `Poll`: reassign to …, release,
  wait). **Any participant, human or agent, may answer it (Iain; there's no human-only rule).** The
  decision commits as a new claim or a release through Raft, like any other.

## 7. bee and kuilt

- **bee is its own repo, `tractat-us/bee`, beside kuilt (Iain).** Its name is written in
  lowercase everywhere, like kuilt's **(Iain)**. It's private while it starts and
  meant to be open source under **AGPL-3.0 (Iain)**. **Every outside contribution requires a
  strict CLA that assigns all rights to Iain (Iain)**, so relicensing (for example, moving a piece
  into Apache-licensed kuilt, or a separate grant to an employer) stays his to decide. kuilt may reference public `tractat-us` projects **(Iain)**,
  which needs a matching edit to kuilt's References policy (§11).
- **Generic pieces graduate to kuilt** once bee has proven them: surfaces on `JsonCrdt` (including
  the A2UI mapping, if it stays generic), action records with a basis, exactly-once claims, a
  voter that never claims, and anything presence or liveness needs. bee owns the client and its
  in-page A2UI driver, the host, the MCP bridge, the Notions catalog, the phone frame, policy and
  deployment.
- **A kuilt peer session handles kuilt asks during development (Iain).** When bee hits a kuilt gap,
  it files a kuilt issue and hands it to that session, a separate Claude Code session in the kuilt
  checkout. bee never patches kuilt locally.
- bee consumes kuilt as published snapshots, with `if (file("../kuilt").exists())
  includeBuild("../kuilt")` for side-by-side work.

## 8. bee and kuilt#2869

**bee is a separate epic. #2869 stays paused (Iain).** #2869 is a research epic with hypotheses
fixed in advance on a dinner story, so changing its story mid-M2 would void its baseline. bee
borrows from it:

- the input-history contract (§6.2) and M1's `CONTRACTS.md`, frozen in kuilt at
  `demo/workspace/` (commit `53f89b34`)
- M2's reconnect shape (A), how a client's replica survives losing its connection to the host,
  from `docs/spikes/2026-10-reconnect-shape.md` in `tractat-us/kuilt-workspace` (private, checked
  out at `~/tractatus/kuilt-workspace`)
- the re-check of a shown answer when the viewer learns more (kuilt#2880). Its code was removed
  from kuilt and survives only at `53f89b34`, so it's borrowable as a design, not as a dependency.

bee also answers #2869's M5 question, whether A2UI earns its place over a fixed screen, for a case
that has no fixed screen at all.

**Revisit #2869 at three checkpoints (Iain):** after this spec, after the plan, and during execution.
At each one, look for pieces to pick up in either direction.

## 9. Constraints and risks

- **Sharing must be obvious.** A Claude session can put anything on the board: a diff, a log, a
  secret it found. v1 is single-user, so the risk is small, but the bridge's tools should make
  "this goes on the shared board" an explicit act, never a side effect. Before a second person
  attaches, there must be a rule for what may never be written to the board.
- **Who can reach the board.** The host binds loopback only by default. On the local network a
  browser needs a key, which a person grants by pairing (§5.7). That's an access gate, not
  transport security. Loopback needs no key, so the host refuses a call that any other site's page
  sent, by its `Host`, `Origin` and Fetch Metadata headers (§5.7).
- **`Sketch` runs agent-written HTML.** It's sandboxed and has no same-origin access. Neither the
  host nor the bee client ever executes surface content outside that sandbox.
- **IP.** bee is built on Iain's own time and equipment as open source. It's brought to an employer
  hackathon as an existing tool, never built there. Hosting there is the employer's call.
- **Keep it small.** Each piece earns its place by being needed in the first hour of real use.

## 10. Left to the builder or the plan

The v1 plan decided all but the last of these, and the sections above now carry the answers. Its
[Decisions section](../plans/2026-11-06-bee-v1.md#decisions-this-plan-makes-spec-10) lists them
together.

- ~~The exact MCP tool list and its shape.~~ Decided: §5.2.
- ~~How a surface's author declares which action records it handles, and how handlers find
  them.~~ Decided: a subscription by surface (§5.2, §6.2).
- ~~The timeout after which a quiet session is declared dead.~~ Decided: 20 minutes (§6.4).
- ~~The layout and history-strip UI.~~ Decided: §6.1.
- ~~The exact peer identity format.~~ Decided: §5.2.
- Whether the A2UI mapping is generic enough to graduate to kuilt, or stays in bee. **Still
  open.** The plan keeps it graduable, since its module depends only on `kuilt-crdt`, and
  revisits the question once the browser driver works.

## 11. Follow-ups outside bee

- **kuilt References policy:** amend kuilt's `AGENTS.md` to allow references to public `tractat-us`
  projects (Iain's ruling). That's a kuilt PR, best landed once bee is public.
- **Workspace index:** bee added to `~/tractatus/CLAUDE.md` (done).
- **The CLA:** the assignment agreement's text (have a lawyer write it, not an agent) and a signing
  check on PRs. Both are needed before the repo accepts outside contributions. `CONTRIBUTING.md`
  says so meanwhile.
- **Epic issues:** a bee epic in `tractat-us/bee`, and a kuilt tracking issue for the primitives
  bee will need. Each links the other and kuilt#2869.

## 12. Build order

The [v1 plan](../plans/2026-11-06-bee-v1.md#dependency-graph-and-fan-out) builds the seven spikes
as eleven tasks. The bridge spike needs claims, because handling a record goes through a claim
(§5.2). So its risky half, the bridge's lifecycle, `/clear` and bounded waits, runs beside the
others, and its board-and-claims half joins them afterwards (design call).

1. **Kotlin/wasm and the renderer in one page** (spike 1), plus all the build wiring. This proves
   the newest seam and pins versions.
2. **The board lattice and its projection** (spike 2, part 1).
3. **The relay** (spike 2, part 2).
4. **The browser driver: two browsers edit one surface** (spike 2, part 3). This proves the
   projection and live shared typing (§5.3).
5. **Bridge mechanics** (spike 3, first half).
6. **Claims** (spike 4): two peers race for one card, and exactly one wins.
7. **Join**, which completes spikes 3 and 4. Then Iain reviews before anything more.
8. **`Sketch` and the companion** (spike 5). This is the acceptance test (§3).
9. **Durability** (spike 6): restart the host, and reload or close every browser, with surfaces,
   records and claims intact.
10. **A second screen** (spike 7): a browser on the same network pairs (§5.7) and joins through
    `tractatus.local` with nothing installed.
11. **Working-day surfaces**: `Board`, `Poll`, orphan decisions and the history strip, which §3's
    secondary test needs.

```
Task 1 (alone; precondition: tractat-us/bee#5 merged)
 ├─► Task 2 lattice + projection ─► Task 3 relay ─► Task 4 driver + e2e ─┐
 ├─► Task 5 bridge mechanics ─────────────────────────────────────────────┼─► Task 7 join (completes spikes 3–4)
 └─► Task 6 claims ───────────────────────────────────────────────────────┘        ══ STOP for Iain's review ══
                                                                                    ├─► Task 8  Sketch + companion (spike 5)
                                                                                    ├─► Task 9  durability (spike 6)
                                                                                    ├─► Task 11 Board, Poll, orphans, history
                                                                                    └─► Task 10 second screen (spike 7; LAN pairing)
```
