# Spike 3a: bridge lifecycle, the `/clear` channel and bounded waits

Task 5 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md), the first half of spike 3.
It asked three questions:

1. Does a session's bridge survive `/clear`?
2. Can the `SessionStart` hook reach that bridge?
3. Do bounded waits come back before Claude Code gives up on a tool call?

All three hold. The 45 s default (A10) stands.

Measured on 2026-11-06 with Claude Code `2.1.319` on macOS, the bridge on temurin 25.0.2, and no
`MCP_TOOL_TIMEOUT` or other timeout override in the environment or settings. The nested sessions
ran `claude -p --model haiku --strict-mcp-config --mcp-config <tmp>`, each in the background under
`timeout 420`. The bridge registered there served the fake source (`NoActions`) and a diagnostic
`bee_hold(seconds, progress)` tool, which held for the given time and then returned. That tool was
removed before the PR.

## Holds: how long Claude Code waits for a tool

| Hold | Progress notifications | Result | Wall time |
|---|---|---|---|
| 60 s | every 10 s | returned `held 60 s` | 65 s |
| 5 min | every 10 s | returned `held 300 s` | 305 s |
| 5 min | none | returned `held 300 s` | 306 s |

- **Neither length failed.** Claude Code `2.1.319` waited out a silent 5-minute call, so its
  default tool timeout is above 5 minutes and doesn't depend on progress. The plan asked how the
  client fails the other hold. On this version neither failed, so there was nothing to record.
- **Claude Code sends a progress token.** Every call carried
  `_meta: {"progressToken": 2, "claudecode/toolUseId": "toolu_…"}`, so `bee_wait_action`'s
  progress notifications reach a real client.
- **The `-p` stream doesn't show them.** In `--output-format stream-json`, the client emits its own
  `tool_progress` heartbeat every 30 s (`"heartbeat": true`, `elapsed_time_seconds` 30, 60, …). It
  did that 10 times in both 5-minute runs, with progress and without. The server's notifications
  never show up as separate events.

**A10 holds.** The 45 s default and 60 s cap sit well inside what the client tolerates. The bound
isn't needed to dodge the timeout this version has. Its purpose is to keep a wait short enough for
the agent to stay responsive, and short enough that a cancel or `/clear` lands within a minute.
Nothing here argues for changing the default.

## `/clear`: the bridge survives, and the hook reaches it

Opt-in check, which ran. An interactive `claude --model haiku --strict-mcp-config --mcp-config <tmp>
--settings <tmp>` ran in a detached tmux session. The settings file held only the `SessionStart`
hook (`matcher: "clear"`), wrapped to append its stderr to a log. Typing `/clear` into the pane
gave this:

- **The bridge survived.** It was pid 60115 before `/clear` and still 60115 after, with one
  `starts` line in its log.
- **The hook fired and hit `/retire`.** The hook's log showed
  `retired: {"retired":"agent:cleartest#0491068b","current":"agent:cleartest#e08394c9"}`, and the
  bridge logged the same retirement, 12 s after start.
- **Both sides found the same `claudePid` by the nearest-`claude`-ancestor rule.** In the first
  runs the bridge read `ProcessHandle.info().command()` (`/Users/keddie/.local/bin/claude`) and
  the hook read `ps -o comm=` (`claude`). After review, both read `ps -o comm=`, so they apply
  literally one rule, and the rerun found the same pid.
- The new-folder trust prompt defaults to "No, exit", so the script answers it with Down and
  Enter.

The hook also ran by hand, outside a nested session: `source: "startup"` exited 0 and printed
nothing, while `source: "clear"` retired the bridge and wrote nothing to stdout.

## What the measurements changed in the code

- **The run file outlived `/exit`.** On the first tmux run, the bridge left no `bee-mcp ends.` line
  and `~/.bee/run/<pid>.json` stayed behind. Claude Code ends its MCP servers with a signal, so the
  `use {}` close never ran. `Main` now closes `ClearControl` from a JVM shutdown hook as well, and
  `close` is idempotent. The rerun removed the file. A `SIGKILL` still leaves it, and the hook then
  reports a dead port on stderr and exits 0.
- **kotlin-logging printed to stdout.** Its start-up line,
  `kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory`, went to stdout, which
  is the MCP protocol stream. It printed when `MainKt` loaded, before `main` redirected anything.
  `main` now keeps the real stdout for the transport and points `System.out` at stderr, and
  `Main`'s logger is lazy, so it starts after the redirect. `bee-mcp`'s `logback.xml` sends every
  log line to stderr, and holds the SDK's per-request DEBUG and per-tool INFO lines to WARN.

## Choices made inside the task

- **`ClearControl` serves through the JDK's `com.sun.net.httpserver`, not Ktor.** It's one route
  on loopback, and this way it needs no dependency and no native access on JDK 25. Handlers run
  on virtual threads, never on the server's dispatcher.
- **`/retire` is authenticated.** A loopback bind alone admits other OS users, and any web page
  that sends a body-less POST to a guessed port (a simple request, so no preflight). The run file
  is `{"port","token","pid"}`:
  - The token is 32 bytes from `SecureRandom`.
  - The file is mode 0600, in a directory forced to 0700 even when it already existed.
  - A request needs `Authorization: Bearer <token>`, compared in constant time. A browser can't
    send that header cross-origin without a preflight, and the server approves none.
  - The hook passes the token to curl on stdin, not as an argument `ps` would show.
  - The hook checks `kill -0 <pid>` first, so a stale file can't retire another session's bridge
    when the pid or port has been reused.
- **A retire never holds a server thread or shutdown.** It runs on the bridge's `SupervisorJob`
  scope. The response waits up to 5 s and says `200` (done), `202` (still running), or `500`
  (failed). A Depart that hangs in Task 7 stalls neither the hook nor the shutdown hook.
- **`ClearControl` takes `scope` and `claudePid`** (default `ClaudeAncestry.claudePid()`) next to
  the plan's `identity` and `runDir`. With no `claude` ancestor it still serves, but it writes no
  run file and logs a warning.
- **`ClaudeAncestry.claudePid` takes `() -> List<Ancestor>`**, where `Ancestor` is a pid and a
  `comm`. The plan had `() -> List<ProcessHandle>`, but `ProcessHandle` can't carry what `ps`
  says, and one rule needs `ps` on both sides. The bridge runs `ps -A -o pid=,ppid=,comm=` once.
  A `comm` may hold spaces.
- **`BEE_RUN_DIR`** moves the run directory for the bridge and the hook alike, so tests and these
  measurements never touched `~/.bee/run`.
- **The source owns the wait's bound.** `ActionSource.next` must return within its `timeout`,
  because only the source knows which cursor to hand back when nothing came. Task 7's
  `BoardActionSource` inherits that obligation.
- **Cursors are opaque and only a source makes them (a writer's call, after review).**
  - After a timeout, the cursor is a position to resume from. It may have moved past records the
    caller could no longer claim, so it need not equal the cursor passed in.
  - `list` returns `ActionPage(records, cursor)` (`{records, cursor}` on the wire), so a caller
    never builds a cursor to page on.
- **Arguments are refused by name.** `timeoutSeconds` must be a JSON integer from 1 to 60. A
  string such as `"30"` is refused, not read as a number, because the schema says integer.
  `cursor` and `since` must be strings, and JSON `null` counts as absent. Each refusal is a tool
  error that names the argument.
- **`BridgeIdentity.retire` mints first, then reports.** A failed report propagates without
  leaving the bridge on the identity it meant to retire.
- **`ClearControlTest` binds an ephemeral loopback port** in the required gate. The channel is
  loopback HTTP by design (A8). Every request has a 5 s timeout. Only the hung-retire test waits
  on the clock (a 200 ms answer bound), and it asserts an upper bound, never an exact time. The
  coordinator is raising this test's clash with the "in-memory" gate rule with Iain.

## Open for Task 7 and Iain

- If the board-backed source returns a different cursor shape, it replaces `NoActions.START`
  (`"0"`), the cursor of a caller that has seen nothing.
- The 5-minute holds show that this client version doesn't need the bound. A future version with a
  shorter default, or a user's `MCP_TOOL_TIMEOUT` under 60 s, would. The cap stays as the safe
  side.
- **An npm-installed Claude Code is untested.** It runs as `node`. The shared `ps` rule finds it
  only if `ps -o comm=` shows its process title (`claude`). The bridge and the hook now agree
  either way, so the failure mode is "neither finds it" and `/clear` retires nothing. They can't
  disagree anymore.
- No kuilt gap came up in this half.
