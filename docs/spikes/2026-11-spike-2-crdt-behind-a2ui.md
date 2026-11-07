# Spike 2: a CRDT behind the stock A2UI renderer

Task 4 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md), the third part of spike 2.
The question was whether two browsers can edit one surface live, with the replica in wasm and the
stock Lit renderer as its view. They can. Live typing works through the stock renderer with no
fork and no patch, and the suite `e2e/two-browsers.spec.mjs` passes against a real host.

## The pieces

- **`BoardDriver`** (`bee-web`) collects `BoardClient.state`, projects each open surface with
  `toSurfaceView`, and validates it with `SurfaceValidator`. It then renders
  `diff(lastRendered, view)`. It never applies a delta (A14). A surface that fails validation, or
  can't be projected, becomes a `<div class="bee-invalid-surface">` naming its errors. Each action
  becomes `client.mutateOrSkip { appendRecord(author, ActionRecord(…, Basis.capture(it, id), …)) }`,
  so the basis is read inside the transform.
- **`LiveInputs`** (`bee-web`) subscribes to the renderer's data model for every path a
  `TextField` binds. It writes each change through `updateData`, debounced to 150 ms.
- **`renderer-host.js`** (`bee-notions`) gains two exports: `subscribeData`, which wraps web_core's
  `DataModel.subscribe`, and `onTextFieldFocus`.
- **`E2eSeederMain`** writes five fixture surfaces through a JVM `BoardClient`, one per test, and
  returns once a second client reads them all back from the host. The host still has no write route.

## The live-typing route

In v0.9, `TextField` calls `props.setValue` on every `input` event, which writes the renderer's own
`DataModel` at the bound path. Nothing reaches bee unless it watches that model, so the route is
this:

1. **Watch.** `subscribeData(root, surfaceId, path, handler)` finds the surface's model as
   `<a2ui-surface>.surface.dataModel` and calls `dataModel.subscribe(path, …)`. A surface the
   driver deletes and recreates has a new model, so the driver drops its subscriptions on every
   `createSurface` or `deleteSurface`, and `track` subscribes again after the render.
2. **Ignore the echo.** web_core's signals notify synchronously, inside
   `processMessagesWithErrors`. A change that arrives while the driver renders is the driver's
   own, so `LiveInputs.whileRendering` drops it. Comparing values instead would be wrong: typing
   back to the value last rendered is a real edit.
3. **Hold the field.** The basic catalog renders into light DOM, so `focusin` and `focusout` reach
   the root. The handler takes the component id from the Lit element's
   `context.componentModel.id`. A path is held while its field is focused, or while it has an edit
   not yet written. The driver shows the person's own value at a held path, and drops any
   `updateDataModel` aimed at it. When the path is released, the next diff sends what waited.

The hold covers the whole path, not just the input: a `Text` bound to the same path shows the
person's own value too, because the renderer has one data model per surface. Only absolute
bindings (`{"path": "/…"}`) are tracked. A `TextField` inside a list template binds a relative
path, and v1 has none.

**Controls.** Each mechanism has a mutation that turns its test red, so each test can fail.

- **Echo filter off.** The loser's page writes the winner back, which clears the conflict, and
  the conflict test fails with the caption empty.
- **Hold off.** The hold test fails with `Expected: "mine"`, `Received: "theirs"`.

In both runs, every other test stayed green.

## The projection rule's evidence

In the conflict test, both pages lose their socket (`page.routeWebSocket`), then type `apple` and
`banana`, then reconnect. Both pages then show `banana`, the greatest string, in the field. Both
show `apple,banana` in a caption bound to `/_bee/conflicts/~1note`. The caption still shows
`apple,banana` 1.5 s later, so no page resolved the conflict by echoing the winner.

The published path behaves as Task 2 intended. web_core's `parsePath` unescapes `~1` once, so the
key holding the set is the raw pointer `/note`. `Text` renders the array through `String()`, which
joins it with commas. That's good enough for a marker, and a real conflict UI is Task 11's.

The hold test shows the rule's other half. sam's write started from a register that held alex's
`mine`, so it supersedes `mine` instead of conflicting with it. Once alex leaves the field, alex
sees `theirs`, and the caption is empty.

## The frame size of a one-field edit (kuilt#2469)

This was measured on alex's board socket, through the Playwright route, for the one second after a
single keystroke. The edit was `!` appended to `hello`, on a board of five fixture surfaces with
six components each, sent once by the 150 ms debounce:

| Direction | Frames (bytes) |
|---|---|
| up | 947, 115, 93 |
| down | 115, **23,464**, 115 |

- The 947 B frame is the edit's delta. alex isn't the surface's author, and Task 3 measured
  0.6 KB for that case on a 20-component surface. An edit by the surface's author carries that
  replica's whole share of the surface: 7.9 KB in Task 3.
- The 23,464 B frame is the relay's merge echo: the whole board, sent to every spoke for every
  edit. Task 3 measured 8 KB for a one-surface board. It grows with the board, not with the edit.
- The 93 B and 115 B frames are small control frames, which I didn't decode.

So live typing costs about one board-sized frame per spoke per debounce window. With 5 surfaces
that's 23 KB every 150 ms of steady typing, per browser. That's fine on loopback, and it's the
relay's echo to fix, in kuilt, rather than the driver's. The numbers were identical across four
separate runs.

## How `ORMap<String, JsonCrdt>` behaved

It carried five surfaces through a seeder, a reader, up to three browsers and the host with no
surprises. Offline edits on two pages reached each other on reconnect, in each client's first
`FullState`, and formed one two-valued register, not a lost update. Surfaces stayed separate: no
test saw another surface's edits. Its cost is the one above. A delta by a surface's author carries
too much, and the echo carries everything (kuilt#2469).

## Is the mapping generic enough for kuilt?

**The projection and the diff look generic. The live-input half doesn't.**

- `toSurfaceView`, `diff` and the hold overlay depend only on `kuilt-crdt` and A2UI's JSON. Any
  A2UI consumer with a `JsonCrdt` per surface could use them.
- `LiveInputs` depends on web_core's `DataModel` and on the basic catalog's light DOM. It also
  reads the Lit element's `context.componentModel.id`, which is internal, not a documented API.
  That belongs with the renderer glue, not in kuilt.

Graduating the first half waits for a second consumer, as the plan says. Revisit after Task 7.

## Workarounds and findings

- **A dial that never answers stalls `BoardClient`.** The first version of the cut test closed
  each redialled socket from the Playwright route before it connected. The page then stayed
  offline for the whole 15 s, with `lastFailure` null and no further redial. `loom.weave` never
  returned, and nothing bounds it. The suite now holds a redial unanswered while cut, and connects
  it on restore. A real network that drops a handshake could do the same thing, so
  `BoardClient.openOrNull` probably wants a dial timeout. That's Task 3's code, and I didn't touch
  it. It's reported for a follow-up issue.
- **Editing `bee-notions` doesn't reach a local build.** Yarn caches the `file:` dependency by
  its version, `0.1.0`, so `kotlinWasmNpmInstall` keeps the old copy in
  `build/wasm/node_modules/@bee/notions`. Locally, delete `build/wasm/node_modules/.yarn-integrity`
  and run `./gradlew kotlinWasmNpmInstall --rerun`, then `:bee-web:wasmJsBrowserProductionWebpack
  --rerun`. A fresh CI checkout isn't affected. A build fix belongs to the build owner, so it's
  reported, not made here.
- **Playwright starts `webServer` before `globalSetup`.** Locally, the seeder connected to the host
  Playwright had just started, on its port, and the suite ran after it.
- **The host logs at DEBUG to stdout** (no `logback.xml` in `bee-host`), so Playwright's output is
  mostly Netty and Quilter noise.
- **Signatures.** `LiveInputs` takes the renderer's root element, not the `A2uiRenderer`, because
  `A2uiRenderer` keeps its host private and `A2uiRenderer.kt` isn't in this task's files. It also
  takes the author, a scope and an `onProblem` callback. `BoardDriver` takes the root (for
  placeholders), the `LiveInputs`, a clock and `onProblem` besides the plan's four.
- **Test isolation is per surface, not per host.** The suite seeds one surface per test on one
  host, so `--repeat-each` reuses dirty surfaces and fails. Run the suite again instead.
