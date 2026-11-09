# bee for Claude Code

This plugin joins your Claude sessions to a [bee](../../README.md) board: a live board that the
people in the room and their sessions all see. It brings the board's MCP server, over HTTP, with
its tools, its briefing and its prompts, plus short commands for them and a `bee` skill on how to
share the board.

```sh
claude plugin marketplace add tractat-us/bee
claude plugin install bee@bee --scope local --config 'board_url=http://127.0.0.1:8233/mcp?name=${USER}'
```

Then start `claude` and type `/bee:draw <topic>`, or `/bee:checkin` for a status card while the
session works. Plain words work too: "draw this on the board", "check in on the board".

`board_url` is the host's address plus `/mcp?name=<you>`, and `&k=<key>` from the join link for a
board on another machine. The plugin needs a host that serves `/mcp`
([#122](https://github.com/tractat-us/bee/pull/122)). It ships no `/clear` hook, because the bridge
runs on the host.

[The Claude Code plugin](../../docs/plugin.md) covers picking the board, where to turn the plugin
on, and how to remove it.

| File | What it is |
|---|---|
| `.claude-plugin/plugin.json` | Name, version and the `board_url` setting |
| `.mcp.json` | The `bee` server: HTTP to `board_url` |
| `skills/bee/SKILL.md` | Waiting for answers, card states, what never goes on the board |
| `skills/draw/SKILL.md` | `/bee:draw`: the server's `whiteboard` prompt, copied |
| `skills/checkin/SKILL.md` | `/bee:checkin`: the server's `checkin` prompt, copied |

`PluginCommandsTest` in `bee-mcp` fails when a copied prompt no longer matches the server's.

Bump `version` in `plugin.json` with each change, or installed copies won't update.
