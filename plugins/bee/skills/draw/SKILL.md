---
name: draw
description: Draws on the bee board. Use BEFORE putting anything on the board, whenever the person asks for it in plain words or types /bee:draw <topic> ($bee:draw in Codex). Fires on "put it on the board", "draw this on bee", "sketch the login flow on the board", "show it on the board", "whiteboard this", "let's whiteboard". Draws the topic, then keeps it current and reacts to clicks until someone says done.
argument-hint: "[topic]"
---

The steps below are the bee server's `whiteboard` prompt (`/mcp__plugin_bee_bee__whiteboard`). The
topic in step 2 is what followed `/bee:draw`, or `$bee:draw` in Codex. Codex fills nothing into
step 2's quotes, so there, take the topic from the person's message. If nothing followed, the topic
is what the person asked to have drawn. If they named nothing, step 2 is instead: ask, on the
board, what to work on, with a field and a Send button, wait for the answer, then draw it.

Join the bee board as a whiteboarding partner for the people in the room. The bee server's instructions say how to take part; follow them.

1. Call bee_board to see what is up and who is here, set your bee_presence status, and call bee_examples for every component that renders.
2. The topic is what the person typed after the command: "$ARGUMENTS", though that may be only its first word, so take the whole of what they typed. Start with one small surface that frames it, then build on it as people talk. If the topic is unclear, ask on the board.
3. Keep the board current as the conversation moves: update your surfaces in place as things are decided, open a new surface for a new topic, close the ones that are settled. When you ask a question, put the way to answer on the board too: Buttons for the options, or a field and a Send button.
4. Put a Done button on your main surface. Then loop: bee_wait_action with no surfaceId (all your surfaces) and timeoutSeconds 20, passing back the cursor it returned, act on each record, bee_action_done it, and redraw. Re-read bee_board now and then for what others changed.
5. Stop when someone clicks Done or says, here or on the board, that they are done; when bee_board shows no person attached; or when no record has arrived for about 10 minutes. Leave a short summary surface of what was decided, clear your bee_presence status, and say here that you have stopped.
