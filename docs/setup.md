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
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.5.0+8-tem # (1)!
./gradlew :bee-mcp:installDist
BEE=$(pwd)
```

1. Every module builds on a JDK 25 toolchain. This is the exact build CI uses.

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
        "env": { "JAVA_HOME": "/Users/you/.sdkman/candidates/java/25.0.5.0+8-tem" }
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

## Showing the board on another screen

A phone, a tablet or a meeting-room screen can show the board with nothing installed. By default
the host answers only this machine, so start it with `--lan` to let your network reach it:

```sh
./gradlew :bee-host:installDist
bee-host/build/install/bee-host/bin/bee-host --lan
```

It logs the board's address on your network, such as `http://tractatus.local:8233/`, and a join
link.

1. On the other device, open that address. Add `?screen=room` to name it as a room screen, so the
   board shows it as `human:room`.
2. The device shows a six-digit code, and nothing of the board yet.
3. On your own screen, the board asks "A device wants to join". Type the code there and press
   **Admit**. The device joins the board.

Only a person can admit a device. An agent that types the code is refused, and the request says
so. A code lasts two minutes, and three wrong codes void it. The device then offers a new code.
The code field shows dots, and what you type stays on your screen until you press **Admit**.

A device's request reaches the board a few seconds after it shows its code. One device can have
one code at a time, and can ask for a new one every ten seconds. All devices together can ask
30 times an hour; past that, a device is told to try again later. Change the number with
`--join-requests-per-hour <n>`.

The join link carries a key (`?k=…`), so a device that opens it skips the code. The page moves
the key out of the address bar as it loads. Anyone holding the link gets in until the host stops,
so share it only with people you'd admit anyway.

What to expect:

- **This gates who joins; it doesn't encrypt.** The board travels over plain HTTP, so anyone on
  your network who can watch its traffic can read it. Don't put on the board what you wouldn't
  say in the room.
- **Your own machine needs no code** at `http://localhost:8233/`. Opened by its network name, it
  counts as another device and pairs like one.
- **A host restart forgets every key.** A device that can't reconnect after one shows **Pair this
  screen again**.
- **A key belongs to one browser tab.** A new tab on the same device pairs again.
- **A device on the network keeps no copy of the board.** A plain-HTTP page can't take the browser
  lock that guards the board's local copy, so it relies on the host for everything
  ([#39](https://github.com/tractat-us/bee/pull/39)).
