# Setting up bee in Claude Code

Two pieces join a Claude Code session to bee: the **bridge** (`bee-mcp`, an MCP server) and a
**`SessionStart` hook** that tells the bridge when you `/clear`. You install both yourself, in your
own Claude Code settings. The bridge doesn't write to `~/.claude`.

The bridge gives a session 12 tools: `bee_board`, `bee_presence`, `bee_surface_open`,
`bee_surface_update`, `bee_surface_close`, `bee_board_clear`, `bee_wait_action`,
`bee_list_actions`, `bee_action_done`, `bee_claim`, `bee_release` and `bee_examples`. It also briefs the session
on how to share the board, and offers a `whiteboard` prompt; see
[Whiteboard with a session](#whiteboard-with-a-session).

Want a board running in five minutes? Start with [Run a board](#run-a-board). The numbered steps
after it set bee up for every session you start.

No Java on the machine the session runs on? See
[An agent with nothing installed](#an-agent-with-nothing-installed).

## Run a board

This is the shortest path from a clone to a Claude session and you sharing one board.

1. Build the host and the bridge, from the root of your checkout:

    ```sh
    source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.5.0+8-tem
    ./gradlew installDist
    BEE=$(pwd)
    ```

2. Start the host in one terminal:

    ```sh
    $BEE/bee-host/build/install/bee-host/bin/bee-host --data-dir /tmp/bee-demo-data
    ```

    It listens on port 8233 (`--port <n>` changes it), answers only this machine, and logs
    `bee host on http://127.0.0.1:8233/`. Without `--data-dir` it keeps the board in
    `~/.bee/host`. A fresh directory gives you a fresh board, and a restart on the same directory
    brings the board back, with every key the host handed out. `--lan` lets other devices on your network reach it; see
    [Showing the board on another screen](#showing-the-board-on-another-screen).

3. Open `http://localhost:8233/?name=<you>` in a browser. The board is empty and says
   `online as human:<you>`. Without `?name=` you join as `human:guest`.

4. Write an MCP config that points a session at the bridge:

    ```sh
    cat > /tmp/bee-mcp.json <<EOF
    { "mcpServers": { "bee": { "type": "stdio",
      "command": "$BEE/bee-mcp/build/install/bee-mcp/bin/bee-mcp",
      "env": { "JAVA_HOME": "$HOME/.sdkman/candidates/java/25.0.5.0+8-tem", "BEE_SESSION_NAME": "demo" } } } }
    EOF
    ```

    The bridge dials `ws://127.0.0.1:8233`. If you gave the host another port, add
    `"BEE_HOST": "ws://127.0.0.1:<port>"` to `env`.

5. Start a session with it:

    ```sh
    claude --mcp-config /tmp/bee-mcp.json --strict-mcp-config --allowedTools "mcp__bee__*"
    ```

    Try this first prompt: *Put a question with three buttons on the board. Wait for my click,
    mark it done, then update the surface to thank me.*

To check it worked:

- Buttons appear on the board in your browser within about 10 seconds.
- After you click one, the session redraws the surface to answer you.
- `/mcp` in the session lists `bee` with its 11 tools.
- Ask a second session, started the same way, to "read the bee board". It sees the same surface,
  opened by `agent:demo#…`.

The numbered steps below make bee available in every session, with the `/clear` hook.

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

A bridge belongs to the board its host kept when the bridge first reached it. If you wipe the
host's data, or move its `claims/` aside, the host starts a new board and refuses the old
bridge, so the old board can't leak into the new one. A refused bridge ends: it prints which board
it was on and which the host keeps now, and exits with status 2, so `/mcp` shows the server as
failed. Restart it from `/mcp` to join the new board. An open browser tab reloads onto the new
board by itself.

## Whiteboard with a session

To use the board as a whiteboard with the people in the room, type this in the session:

```text
/mcp__bee__whiteboard login-flow
```

Keep the topic to one word: Claude Code may pass only the first word of what follows the
command. For a longer topic, say it in your next message. Leave the topic off and the session
asks, on the board, what to work on.

The session draws the topic, keeps the board current as you talk, and reacts to clicks and typing
on the board. It stops when someone says done, when nobody has the board open, or after about
10 minutes with no clicks.

Nothing else needs installing. The bridge sends every session a short briefing as it connects:
look before drawing, one surface per topic, leave other people's surfaces alone, and wait for
clicks on anything interactive. Claude Code cuts that briefing at 4096 characters, so it carries
only the rules and two examples; `bee_examples` returns an example of every component that
renders. All three texts live in `bee-mcp/src/main/kotlin/us/tractat/bee/mcp/WhiteboardGuide.kt`.

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
the key out of the address bar as it loads. Anyone holding the link gets in, across host restarts,
until you make a new link, so share it only with people you'd admit anyway.

### Cutting a device off

The host keeps its keys in its data dir, in a file named `keys` that only you can read. A
restart on the same data dir keeps the same join link, and every paired device stays paired.
Taking access away is a restart with a flag:

| To | Run | What happens |
|---|---|---|
| Stop the join link working | `bee-up --new-link` (or `bee-host --new-link`) | The host prints a new link, and the old one no longer admits. Paired devices keep their keys. |
| Cut off everyone | `bee-up --forget-devices` (or `bee-host --forget-devices`) | Every paired device's key stops working, and so does the join link. The host prints a new link. Each device pairs again or takes the new link. |

`--forget-devices` makes a new link too, because a device that came in by the link, and every
bridge on another machine, holds the link's key, not one of its own. Forgetting only the paired
devices would leave those connected.

With a host already running, `bee-up` restarts it with the flag, on the port, data dir and reach
it was started with. The board itself survives either one. The host's log, `~/.bee/host.log`,
holds the join link, so `bee-up` keeps it readable only by you.

What to expect:

- **This gates who joins; it doesn't encrypt.** The board travels over plain HTTP, so anyone on
  your network who can watch its traffic can read it. Don't put on the board what you wouldn't
  say in the room.
- **Your own machine needs no code** at `http://localhost:8233/`. Opened by its network name, it
  counts as another device and pairs like one.
- **A host restart keeps every key.** A paired tab left open reconnects on its own. After
  `--forget-devices` it can't, and shows **Pair this screen again**.
- **A key belongs to one browser tab.** A new tab on the same device pairs again.
- **The host answers only to its own names**: `localhost`, the address a device used, and the
  network name it logs. Reached by any other name, it refuses, which stops a website from reaching
  it through a name of its own. If your network knows this machine by another name, add it with
  `--host-name <name>`.
- **A device on the network keeps no copy of the board.** A plain-HTTP page can't take the browser
  lock that guards the board's local copy, so it relies on the host for everything
  ([#39](https://github.com/tractat-us/bee/pull/39)).

## An agent with nothing installed

A Claude session can join the board by URL alone, with no bridge to build and no Java. The host
serves MCP over HTTP at `/mcp` and runs a bridge for each session there, so the session gets the
same tools, prompt and instructions as the stdio bridge.

1. Start the host with `bee-up` (or `bee-host --lan` for a teammate). `bee-up` prints both lines
   below with your port and key filled in.
2. Register the URL once:

    ```sh
    # On the host's own machine: loopback needs no key.
    claude mcp add --scope user --transport http bee 'http://localhost:8233/mcp?name=<you>'
    # On a teammate's machine: the join link's key goes in ?k=.
    claude mcp add --scope user --transport http bee 'http://192.168.1.5:8233/mcp?k=…&name=<them>'
    ```

    `?name=` names the session on the board as `agent:<name>-claude#…`. Without it the session
    is `agent:claude#…`. `--scope user` registers bee for every folder; without it, the server
    exists only in the folder you ran the command in.
3. Start `claude` and type `/mcp__bee__whiteboard`.

What to expect:

- **`claude mcp add` refuses a name it already has.** It answers "MCP server bee already exists"
  and exits 1. To point bee at a new link, run `claude mcp remove --scope user bee` first.
- **The key is in your Claude config**, as with `BEE_HOST`. Treat the config like the link.
- **No `/clear` hook.** The hook talks to a bridge process on your machine, and this bridge runs on
  the host. A session that runs `/clear` keeps its identity until the host retires it.
- **A Claude that exits leaves without saying so.** Claude Code closes its stream and sends no
  goodbye, so the host waits two minutes, then departs the session as gone quiet.
- **The bridge lives on the host.** A host restart ends every such session, and the session's
  next call finds it gone. Reconnect from `/mcp` in Claude Code, or restart `claude`.

## An agent on another machine

A teammate's Claude session, on their own laptop, can join your board. The bridge takes the host's
join link as its `BEE_HOST`.

1. Start the host with `--lan`, as above, and copy the join link it logs:
   `http://<your machine>.local:8233/?k=…`.
2. Send your teammate the link. They build the bridge and register it, as in
   [steps 1 and 2](#1-build-the-bridge), and add the link, pasted whole, to the bridge's `env`:

    ```json
    "env": { "BEE_HOST": "http://tractatus.local:8233/?k=…", "BEE_SESSION_NAME": "sam" }
    ```

3. They start a session. It joins your board as `agent:sam#…`, and you both see it in
   `bee_presence`.

`BEE_HOST` also takes `ws://host:port`, `wss://`, `http://host:port/` and `https://`. A value it
can't read stops the bridge at start-up, with a message that names `BEE_HOST`.

What to expect:

- **The key is in their config, and admits whoever holds it until you make a new link.** Treat
  the config like the link itself. The bridge's log names the host and says "with a join key"; it
  never prints the key, not even when a dial fails and the failure names the URL it dialled.
- **A host restart keeps the link.** Their bridge redials on its own, with the same `BEE_HOST`.
- **After `--new-link` or `--forget-devices`, the old key no longer admits**, so their bridge is
  refused ([Cutting a device off](#cutting-a-device-off)). Send the new link, and have them update
  `BEE_HOST` and restart the bridge from `/mcp`.
- **If their machine can't find yours by name**, swap the name for your machine's IP address and
  keep the `?k=…`: `http://192.168.1.5:8233/?k=…`. Some office networks don't pass `.local`
  names between laptops. The host answers to the address a device used.
- **Plain HTTP, no encryption.** As with any device on the network, anyone who can watch the
  traffic can read the board, and the key.
