# Setting up bee in Claude Code

Two pieces join a Claude Code session to bee: the **bridge** (`bee-mcp`, an MCP server) and a
**`SessionStart` hook** that tells the bridge when you `/clear`. You install both yourself, in your
own Claude Code settings. The bridge doesn't write to `~/.claude`.

So far the bridge serves `bee_wait_action` and `bee_list_actions` over an empty source, and the
`/clear` channel. It joins the board in Task 7 of the
[v1 plan](superpowers/plans/2026-11-06-bee-v1.md).

## 1. Build the bridge

```sh
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.2-tem
./gradlew :bee-mcp:installDist
```

This writes the launcher to `bee-mcp/build/install/bee-mcp/bin/bee-mcp`. The commands below call
your checkout `$BEE`, so replace that with its absolute path.

## 2. Register the MCP server

For every project:

```sh
claude mcp add --scope user bee -- "$BEE/bee-mcp/build/install/bee-mcp/bin/bee-mcp"
```

Or for one project, put this in its `.mcp.json`:

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

- The launcher needs a JDK 25 on `JAVA_HOME` or `PATH`. Set `env.JAVA_HOME` if Claude Code starts
  without one.
- `BEE_SESSION_NAME` names the session on the board, as `agent:<name>#<8 hex>`. It defaults to
  the working directory's basename.
- The bridge logs to stderr. Claude Code keeps that in its MCP log.

## 3. Install the `/clear` hook

`/clear` wipes the agent's context but keeps the bridge process running. The hook tells the bridge
to retire its identity, so the claims it held are orphaned, not silently kept (spec §5.2, A8). Add
this to `~/.claude/settings.json`, merging it into any `hooks` you already have:

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

The hook reads the event from stdin. On `"source": "clear"` it finds the session's `claude`
process, reads the bridge's port from `~/.bee/run/<claudePid>.json` and sends `POST /retire` to
`127.0.0.1`. For any other source it exits at once. It never writes to stdout, because a
`SessionStart` hook's stdout goes into the session's context. It always exits 0, so a missing
bridge never blocks a session. Its messages go to stderr.

## Checking it

- `/mcp` in a session lists `bee` with its tools.
- While a session runs, `~/.bee/run/` holds one `<claudePid>.json` per bridge (`{"port":N}`). The
  bridge deletes it on exit. A bridge killed with `SIGKILL` can't, so its file stays behind, and
  the hook then reports a dead port and carries on.
- After `/clear`, the bridge's log shows `/clear retired agent:…; now agent:…`.

`BEE_RUN_DIR` moves the run directory, for the bridge and the hook alike. It's for tests. Leave it
unset in normal use.
