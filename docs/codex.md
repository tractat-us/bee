# bee with Codex

You use OpenAI's Codex, not Claude Code, and you want your sessions on a bee board. One line adds
the board to Codex, and the bee plugin gives Codex the same skills Claude Code gets. bee speaks
plain MCP, so nothing about the board changes: Codex gets the same tools and the same briefing.

```sh
codex mcp add bee --url 'https://bee.tractat.us/mcp?name=<you>&k=<key>'
```

Start `codex` and say "whiteboard the login flow on the bee board". The session draws on the
board, and reacts when someone clicks.

## Get the line

Open the board in your browser and press **Bring your Claude**. The last line in the panel, under
"Using Codex?", is yours, with your key and name filled in. Copy it and run it in a terminal.

The line is the board's address, then `/mcp`, then who you are:

| The board | The address |
|---|---|
| The hosted board | `https://bee.tractat.us/mcp?name=<you>&k=<key>` |
| On a coworker's machine | `http://<their machine>:8233/mcp?k=<key>&name=<you>` |

- `?k=` is the key from the join link or the panel. The coworker's `bee-up` prints the same line
  with their address and key filled in.
- `?name=` names your session on the board, as `agent:<you>-claude#…` for now: the host adds
  `-claude` whichever agent you bring.

`codex mcp add` writes Codex's user config, `~/.codex/config.toml`, so every `codex` you start, in
any folder, joins the board. It replaces a `bee` it already has, so run the line again for a new
key or another board.

The first time the session calls each bee tool, Codex asks. Pick **Always allow**, or set the
server to run without asking, as in [The config file](#the-config-file).

## On the board's own machine

On the machine that runs the board, start one Codex session with the board, rather than adding it
for every folder:

```sh
codex -c 'mcp_servers.bee.url="http://127.0.0.1:8233/mcp?name=<you>"' -c 'mcp_servers.bee.default_tools_approval_mode="approve"'
```

`bee-up` prints this line, and the panel offers it to the board's owner. Each `-c` lasts for that
run only, as `claude --mcp-config` does, and writes nothing. A `codex mcp add` here would bring
every Codex on the machine onto the board, `codex exec` runs included. The second `-c` lets bee's
tools run without asking.

## The skills

The bee plugin adds three skills: `bee` on how to share the board, `draw` to whiteboard a topic,
and `checkin` for a status card. They're the files Claude Code's plugin uses.

```sh
codex plugin marketplace add tractat-us/bee
codex plugin add bee@bee
```

Then use them as Claude Code's commands are used:

| In Claude Code | In Codex | Or say |
|---|---|---|
| `/bee:draw <topic>` | `$bee:draw <topic>` | "whiteboard this on the board" |
| `/bee:checkin [task]` | `$bee:checkin [task]` | "check in on the board" |
| `/mcp__bee__whiteboard` | `$bee:draw` | "whiteboard this on the board" |
| `/mcp__bee__checkin` | `$bee:checkin` | "check in on the board" |

Codex doesn't offer a server's MCP prompts as commands, so `/mcp__bee__whiteboard` and
`/mcp__bee__checkin` have no Codex form. The `draw` and `checkin` skills carry the same steps.
Without the plugin, plain words still work: the board's briefing tells the session how to take
part.

In Codex, the plugin brings no server of its own. Codex can't fill in a setting such as the board's
address, so the board comes from your `codex mcp add` line or your config.

## The config file

`codex mcp add` writes a table in `~/.codex/config.toml`. Write it yourself to add settings:

```toml
[mcp_servers.bee]
url = "https://bee.tractat.us/mcp?name=<you>&k=<key>"
default_tools_approval_mode = "approve"
```

`default_tools_approval_mode = "approve"` runs bee's tools without asking each time. Running
`codex mcp add bee` again rewrites the table and drops it.

For one project only, put the table in that project's `.codex/config.toml`, not your own. Codex
reads a project's config only once you trust the project.

## The bridge on your machine

With a [kit](setup.md#hand-a-coworker-a-kit), Homebrew or a checkout, Codex can run the board's
bridge itself, as Claude Code does with `~/.bee/mcp.json`. A session there keeps its place on the
board when the host restarts. `bee-up` or `bee-join` writes `~/.bee/mcp.json`; copy its `command`
and `env` into a table:

```toml
[mcp_servers.bee]
command = "/path/to/bee-mcp"
startup_timeout_sec = 30
default_tools_approval_mode = "approve"

[mcp_servers.bee.env]
JAVA_HOME = "/path/to/jdk-25"
BEE_HOST = "ws://127.0.0.1:8233"
BEE_SESSION_NAME = "alex-codex"
```

`BEE_SESSION_NAME` names the session on the board, here `agent:alex-codex#…`. For a coworker's
board, `BEE_HOST` is their join link, pasted whole. [Setup](setup.md#bee-in-every-session) says
what else the bridge takes. The bridge's `/clear` hook is for Claude Code, so Codex skips it.

## What's different from Claude Code

- **Codex asks before each tool runs**, unless the config says `approve`.
- **There are no MCP prompts.** Use the skills, or plain words.
- **The key is in `~/.codex/config.toml`.** Treat that file like the join link.
- **Plain HTTP on a LAN board.** Anyone who can watch your network can read the board and the
  key. The hosted board is HTTPS.

## Remove it

```sh
codex mcp remove bee
codex plugin remove bee@bee
codex plugin marketplace remove bee
```

A session that's running keeps the board until it exits.
