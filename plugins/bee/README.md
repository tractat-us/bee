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
board on another machine. On the hosted board it's `https://bee.tractat.us/mcp?name=<you>&k=<key>`,
with the key from that board's **Bring your Claude** panel ([Running hosted bee](../../docs/deploy.md)).
The plugin needs a host that serves `/mcp`
([#122](https://github.com/tractat-us/bee/pull/122)). It ships no `/clear` hook, because the bridge
runs on the host.

[The Claude Code plugin](../../docs/plugin.md) covers picking the board, where to turn the plugin
on, and how to remove it. New to bee? [Try bee](../../docs/try-bee.md) puts you on a board first.
To show it to a colleague, the [demo walkthrough](../../docs/demo.md) is a five-minute script. All
the docs are at <https://tractat-us.github.io/bee/>.

Codex installs the same plugin, with `codex plugin marketplace add tractat-us/bee` and
`codex plugin add bee@bee`, and gets the same skills. It takes no server from the plugin: Codex
can't fill in `board_url`, so the board comes from `codex mcp add`
([bee with Codex](../../docs/codex.md)).

| File | What it is |
|---|---|
| `.claude-plugin/plugin.json` | Name, version and the `board_url` setting |
| `.codex-plugin/plugin.json` | Codex's manifest: the same skills, and no server |
| `.mcp.json` | The `bee` server: HTTP to `board_url` |
| `skills/bee/SKILL.md` | Waiting for answers, card states, what never goes on the board |
| `skills/draw/SKILL.md` | `/bee:draw`: the server's `whiteboard` prompt, copied |
| `skills/checkin/SKILL.md` | `/bee:checkin`: the server's `checkin` prompt, copied |

`PluginCommandsTest` in `bee-mcp` fails when a copied prompt no longer matches the server's, when
the two manifests' names or versions differ, or when a skill wouldn't load in Codex.

Bump `version` in both `plugin.json` files with each change, or installed copies won't update.
