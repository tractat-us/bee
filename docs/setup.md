# Setting up bee in Claude Code

Two pieces join a Claude Code session to bee: the **bridge** (`bee-mcp`, an MCP server) and a
**`SessionStart` hook** that tells the bridge when you `/clear`. You install both yourself, in your
own Claude Code settings. The bridge doesn't write to `~/.claude`.

So far the bridge serves `bee_wait_action` and `bee_list_actions` over an empty source, and the
`/clear` channel. It joins the board in Task 7 of the
[v1 plan](superpowers/plans/2026-11-06-bee-v1.md).

## 1. Build the bridge

From the root of your bee checkout:

```sh
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.2-tem
./gradlew :bee-mcp:installDist
BEE=$(pwd)
```

This writes the launcher to `$BEE/bee-mcp/build/install/bee-mcp/bin/bee-mcp`. It lives under
`build/`, so `./gradlew clean` deletes it, and every session using it loses bee until you run
`installDist` again. The commands below use `$BEE`. In a JSON file, write the absolute path that
`echo "$BEE"` prints.

## 2. Register the MCP server

Pick one scope:

- **Every project (global).** This writes to your user-level Claude Code config, so every session
  you start, in any directory, launches the bridge:

  ```sh
  claude mcp add --scope user bee -- "$BEE/bee-mcp/build/install/bee-mcp/bin/bee-mcp"
  ```

- **One project.** Put this in that project's `.mcp.json`:

  ```json
  {
    "mcpServers": {
      "bee": {
        "type": "stdio",
        "command": "/absolute/path/to/bee/bee-mcp/build/install/bee-mcp/bin/bee-mcp",
        "env": { "JAVA_HOME": "/Users/you/.sdkman/candidates/java/25.0.2-tem" }
      }
    }
  }
  ```

Either way:

- The launcher needs a JDK 25 on `JAVA_HOME` or `PATH`. Set `env.JAVA_HOME` if Claude Code starts
  without one.
- `BEE_SESSION_NAME` names the session on the board, as `agent:<name>#<8 hex>`. It defaults to
  the working directory's basename.
- The bridge logs to stderr, never stdout, which carries the MCP protocol. Claude Code keeps that
  log.

## 3. Install the `/clear` hook

`/clear` wipes the agent's context but keeps the bridge process running. The hook tells the bridge
to retire its identity, so the claims it held are orphaned, not silently kept (spec §5.2, A8).

Use the same scope you chose for the server:

- **Global:** `~/.claude/settings.json` applies to every session you start.
- **One project:** use that project's `.claude/settings.json`, or `.claude/settings.local.json` to
  keep it out of the project's git history.

Merge this into the file's existing `hooks`:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "clear",
        "hooks": [
          { "type": "command", "command": "/absolute/path/to/bee/bin/bee-session-start-hook" }
        ]
      }
    ]
  }
}
```

The hook reads the event from stdin. On `"source": "clear"`, it does this:

1. It finds the session's `claude` process.
2. It reads `~/.bee/run/<claudePid>.json`, which holds the bridge's port, bearer token and pid.
3. It checks that the bridge is still alive.
4. It sends `POST /retire` to `127.0.0.1` with the token.

For any other source, it exits at once. A `SessionStart` hook's stdout goes into the session's
context, so the hook never writes there. It always exits 0, so a missing bridge never blocks a
session, and it reports problems on stderr.

## Checking it

- `/mcp` in a session lists `bee` with its tools.
- While a session runs, `~/.bee/run/` (mode 0700) holds one `<claudePid>.json` (mode 0600) per
  bridge. The bridge deletes its file on exit. A bridge killed with `SIGKILL` can't, so its file
  stays behind. The hook sees that the file's pid is dead and does nothing.
- After `/clear`, the bridge's log shows `/clear retired agent:…; now agent:…`.

`BEE_RUN_DIR` moves the run directory, for the bridge and the hook alike. It's for tests. Leave it
unset in normal use.

## Local build gotchas

**An edit to `bee-notions` doesn't reach a local build.** `bee-web` takes `bee-notions` as a
`file:` npm dependency. Yarn copies it into `build/wasm/node_modules/@bee/notions` once and, while
its version stays `0.1.0`, treats the copy as current, so a rebuild bundles the old renderer. To
pick up the edit, from the root of your checkout:

```sh
rm build/wasm/node_modules/.yarn-integrity
./gradlew kotlinWasmNpmInstall --rerun
./gradlew :bee-web:wasmJsBrowserProductionWebpack --rerun
```

CI starts each run with no `build/wasm`, so it never hits this.
