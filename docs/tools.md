# Tools and prompts

You've added bee to a Claude session, and you want to know what the session can now do on the
board. This page lists all of it: two prompts you type, and the tools the session calls on its own.

## Prompts you type

| Type this | What the session does |
|---|---|
| `/mcp__bee__whiteboard [topic]` | Whiteboards with the people in the room: draws the topic, or asks on the board what to work on, keeps the board current as you talk, and reacts to clicks and typing until someone says done. |
| `/mcp__bee__checkin [focus]` | Keeps one status card for itself on the board, named after its repo, and asks you for decisions with buttons, until you press **Done** or **Pause** on the card. |

Both arguments are optional. Keep each to one word: Claude Code may pass only the first word that
follows the command. Say anything longer in your next message.
[Whiteboard with a session](setup.md#whiteboard-with-a-session) and
[A status card for every session](setup.md#a-status-card-for-every-session) say what to expect.

## Tools the session calls

You don't call these yourself. The session picks them as it works, and `/mcp` in the session lists
them under `bee`.

Each thing on the board, such as a poll, a sketch or a set of cards, is a *surface*. A click, a
vote or a typed answer becomes an *action record* that waits on the board until a session handles
it. A *claim* makes sure exactly one session acts on each one.

These only read, so nobody else sees them happen:

| Tool | What it does |
|---|---|
| `bee_board` | Reads the board: the open surfaces with their contents, the closed ones, who is attached, and the claims. |
| `bee_examples` | Reads the cheat-sheet: one small surface for each kind of component the board can show, ready to put up as it stands. |
| `bee_wait_action` | Waits up to a minute for the next click, vote, answer or chat message, on one surface or on every surface this session opened. Never one the session wrote itself, such as its own chat message. |
| `bee_list_actions` | Lists the action records on one surface, or on every surface this session opened, without waiting, leaving out the ones the session wrote itself. |

These write to the board, and everyone attached sees the result:

| Tool | What it does |
|---|---|
| `bee_surface_open` | Puts a new surface on the board. |
| `bee_surface_update` | Changes a surface: its components, its data, or new entries such as a poll's options. |
| `bee_surface_close` | Closes a surface. It moves to the history strip with its content and records. |
| `bee_board_clear` | Closes every open surface except the host's. Nothing is deleted. |
| `bee_action_done` | Marks an action record handled. |
| `bee_claim` | Claims an action record or a card for this session. Exactly one claimer wins. |
| `bee_release` | Lets go of a claim this session holds, or of one a departed session left behind, under the token the board shows for it. |
| `bee_presence` | Sets the "working on…" line beside the session's name. |

## Where these come from

The bridge serves all of this, whether it runs beside the session or, for a session that joined
by URL, inside the host, along with a short briefing every session receives as it connects:
look before drawing, one surface per topic, leave other people's surfaces alone. The briefing and
the two prompts live in
[`WhiteboardGuide.kt`](../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/WhiteboardGuide.kt) and
[`CheckinGuide.kt`](../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/CheckinGuide.kt); the tools
live in [`BeeTools.kt`](../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/BeeTools.kt) and
[`WaitTool.kt`](../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/WaitTool.kt). Each tool's full
description, the one the session reads, is there. `ToolsPageTest` fails when a tool or a prompt
is added, renamed or removed without this page following.

The design spec's [bridge section](superpowers/specs/2026-11-06-bee-design.md#52-the-mcp-bridge-bee-mcp)
says why the tools are shaped this way.
