---
name: bee
description: Use when the person mentions the bee board or asks what is on it ("what's on the board", "look at bee", "did anyone answer on the board"), when a click or answer may be waiting there, or before calling any bee_* tool. Says how to wait for answers, what each card state means, and what never goes on the board. Drawing goes to the draw skill, status cards to checkin, a component the board lacks to notion.
---

# bee: sharing the board

bee is a live board that the people in the room and every attached session see at once. This
plugin's MCP server, `bee`, connects you to one. When it connects, the server sends its
**instructions**: the rules for taking part and two example surfaces. Those instructions are the
source of truth. This skill says when to reach for the board, and adds what they leave out.

The tools come from the plugin's server, so their full names start with `mcp__plugin_bee_bee__`:
`bee_board` here means `mcp__plugin_bee_bee__bee_board`. If the person also registered the bee
server by hand, the same tools also appear as `mcp__bee__bee_board`. Use one server, not both.

## When to use the board

- **Whiteboarding with the room.** `/bee:draw <topic>`, or "draw the login flow on the board", runs
  the whole loop: look, draw, keep it current, react to clicks, stop on Done.
- **Checking in while you work.** `/bee:checkin [task]`, or "check in on the board", keeps one
  status card for the session, named after its repo, and asks the person for decisions with
  buttons.
- **A piece the board doesn't have.** "Can the board show a timer?", or a chart, a QR code, a map:
  the `notion` skill looks for it, builds it now as a Sketch, and offers to request it from bee.
- **Anything else.** Look first with `bee_board`. Before you draw a component you haven't drawn
  yet, call `bee_examples`. It returns one working surface for each component the board renders.

The server's own prompts, `/mcp__plugin_bee_bee__whiteboard` and `/mcp__plugin_bee_bee__checkin`,
run the same steps as `/bee:draw` and `/bee:checkin`.

## Waiting for an answer

- People at the board can't type into your terminal. Each question you ask on the board gets a
  way to answer there: a Button per option, or a TextField and a Send Button.
- Loop `bee_wait_action` with `timeoutSeconds` 20 and the cursor it returned. `timedOut` only
  means call again. A short timeout matters: a message the person types in the terminal waits
  until your wait returns. To type, the person presses Esc to end the wait.
- Each record arrives claimed for you, with a token. Act on it once, then call `bee_action_done`
  with its `recordId` and token. Act only on records from `bee_wait_action`. Records from
  `bee_list_actions` carry no token, and another session may already be handling them.
- Answer on the board as well as here. Update the surface to show what you did with the click.

## What the person sees of you

Each card names the session that opened it and its state:

- **listening**: you are in `bee_wait_action`. An answer reaches you now.
- **working**: you are attached but busy. You read answers at your next wait.
- **left**, or gone quiet: no session will take answers on this card. The board mutes its
  actions. Any claims you held become orphans until another session takes them.

`bee_presence` sets your line under that state ("drawing the login flow"). Clear it when you stop.

## Never on the board

The board travels over plain HTTP, and everyone attached sees all of it. Never put a secret,
token, key, password or environment value on it, or the board URL itself, which can carry the
board's join key. Name a file; don't paste it. Write short summaries.
