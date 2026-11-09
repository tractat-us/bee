# The Claude Code plugin

Install the bee plugin, and your Claude sessions join a bee board with nothing else to set up. You
write no MCP config, build no bridge, and need no Java. You give the plugin one thing: the board's
address.

```sh
claude plugin marketplace add tractat-us/bee
claude plugin install bee@bee --scope local --config 'board_url=http://127.0.0.1:8233/mcp?name=${USER}'
```

Start `claude` in that folder and type `/bee:draw login-flow`, or say "draw the login flow on the
board". The session draws on the board that `bin/bee-up` started on this machine.

The plugin needs a host that serves MCP over HTTP at `/mcp`, which bee has had since
[#122](https://github.com/tractat-us/bee/pull/122). Against an older host, `/mcp` in Claude Code
shows `bee` as failed.

## What you get

- **The board's tools and prompts.** The plugin's MCP server, `bee`, connects over HTTP to the
  board's host. The host runs a bridge for each session, so a plugin session gets the same 12 tools
  and the same briefing as the kit's bridge. Their names carry the plugin's prefix:
  `mcp__plugin_bee_bee__bee_board`, and so on.
- **Two short commands, or plain words.**

    | Type | Or say | What the session does |
    |---|---|---|
    | `/bee:draw <topic>` | "draw this on the board", "let's whiteboard it" | Whiteboards with the people in the room until someone says done |
    | `/bee:checkin [task]` | "check in on the board" | Keeps a status card for the session on the board while it works |

    Each runs the same steps as the server's own prompt,
    [`whiteboard` or `checkin`](setup.md#whiteboard-with-a-session). Those prompts are also there,
    under the plugin's longer names: `/mcp__plugin_bee_bee__whiteboard` and
    `/mcp__plugin_bee_bee__checkin`.

- **A skill, `bee`.** It tells the session how to wait for an answer, what each card's state
  means, and what never goes on the board. It loads by itself when you mention the board.

## Pick the board

The plugin has one setting, `board_url`: the host's address, then `/mcp`, then who you are.

| The board | `board_url` |
|---|---|
| Started by `bee-up` on this machine | `http://127.0.0.1:8233/mcp?name=${USER}` |
| On a coworker's machine | `http://<their machine>:8233/mcp?k=<key>&name=${USER}` |

- `?name=` names the session on the board as `agent:<name>-claude#…`. Claude Code fills in
  `${USER}` as it connects. Without `?name=`, the session is `agent:claude#…`.
- `?k=` is the key from the coworker's join link, the part after `?k=` in
  `http://<their machine>:8233/?k=…`. Your own machine needs no key.
- If you leave the setting out, it starts as the first row.

Set it as you install, with `--config` as above, or change it later:

```sh
echo '{"board_url":"http://192.168.1.5:8233/mcp?k=…&name=${USER}"}' | claude plugin configure bee@bee --values-stdin
```

Restart Claude Code, or reconnect `bee` from `/mcp`, to dial the new address. Installed from
`/plugin` inside Claude Code, the plugin asks for the address as it enables.

The setting belongs to you, not to a folder: every folder where bee is on dials the same board.

## Choose where it's on

Every session that has the plugin on joins the board, and stays on it for two minutes after it
exits. That includes `claude -p` runs and workers that other sessions start. So turn it on only
where you want sessions on the board:

- **On the host's own machine**, install with `--scope local`, as above. bee is then on in that
  folder only, for you only, and stays out of the project's git history.
- **On a coworker's machine**, `--scope user` puts bee in every folder, as `claude mcp add --scope
  user` does in [An agent with nothing installed](setup.md#an-agent-with-nothing-installed).
- To turn it on in another folder later, run `claude plugin install bee@bee --scope local` there.
  It keeps the board you set.

## What's different from the kit

The kit's bridge, `~/.bee/mcp.json` from `bee-up`, still works, and its prompts keep their short
names, `/mcp__bee__whiteboard`. Use one or the other in a session, not both: two servers put two
copies of you on the board.

- **No `/clear` hook.** The plugin ships none. The kit's hook tells a bridge running on your
  machine that you cleared. A plugin session's bridge runs on the host, where the hook can't reach
  it. After `/clear`, the session keeps its identity until the host retires it.
- **A host restart ends the session's bridge.** The session's next call finds it gone. Reconnect
  from `/mcp`, or restart `claude`.
- **The key sits in your Claude settings**, in `~/.claude/settings.json` under `pluginConfigs`.
  Treat that file like the join link.
- **Plain HTTP.** Anyone who can watch your network can read the board and the key. Don't put on
  the board what you wouldn't say in the room.

## Remove it

```sh
claude plugin uninstall bee@bee --scope local
claude plugin marketplace remove bee
```

Use the scope you installed with. A session that's running keeps the board until it exits.
