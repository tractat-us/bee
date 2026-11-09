# Demo walkthrough

You've tried bee, and now you want to show it to a colleague. This page is a five-minute script
for that: two people, two laptops with Claude Code, and a phone if you have one. You talk and
click; your two Claude sessions draw on one board that every screen shows. Each step says what to
do and what it shows.

Never used bee yourself? Spend ten minutes with [Try bee](try-bee.md) first. It walks the same
paths, on your own.

## Before the meeting

Allow five minutes, and do these with your teammate in the room, on the laptops you'll demo on.

1. **Install bee on your laptop.** Your teammate installs nothing.

    ```sh
    brew install tractat-us/tap/bee
    ```

    Without Homebrew, use the zip kit instead. [Setup](setup.md#run-a-board) covers both.

2. **Start a board.**

    ```sh
    bee-up --name <you>
    ```

    It opens the board in your browser and copies a join link to your clipboard. Send that link
    to your teammate. The first time, macOS asks whether `java` may accept incoming connections:
    press **Allow**. This shows the first point of the demo: the board is a folder on your laptop,
    with no server in the cloud.

3. **Your teammate joins.** They open the link in a browser, with `&name=<them>` added to the end.
   Then they press **Bring your Claude**, copy its one line, paste it into a terminal, and start
   `claude`. A link and one pasted line put a person and their Claude on the board, with no
   account and nothing installed.

4. **Start your own Claude.** `bee-up` prints the line:

    ```sh
    claude --mcp-config ~/.bee/mcp.json --allowedTools 'mcp__bee__*'
    ```

    Or use [the Claude Code plugin](plugin.md). For this demo the line above is the better pick:
    a session started with it keeps its place on the board through the restart beat. A plugin
    session has to reconnect from `/mcp`.

5. **Warm up.** Each of you asks your Claude "what's on the bee board?" once.

If you have a phone, press **Invite** on your board and scan its QR code with the phone. The
phone joins the board, with nothing installed. Anyone who scans the code gets on, so show it only
to the people you mean to invite.

Two habits for the demo itself. Press **Esc** before you type to a session that's waiting on the
board, or it may hold your message for up to 20 seconds. And close **Bring your Claude** before
your teammate's screen is shown to the room: the panel shows their key.

## The demo

Pick a topic everyone in the room knows, such as how your team does code review. Keep it to one
word, or one hyphenated word.

1. **Draw a topic.** In your Claude, type:

    ```text
    /mcp__bee__whiteboard code-review
    ```

    With the plugin, it's `/bee:draw code-review`. Within half a minute, a diagram and some
    questions with buttons appear on both screens. A session draws for the room, not into its own
    terminal.

2. **A click reaches an agent.** Your teammate clicks an answer on your agent's question. Your
   agent reacts and redraws. People answer on the board, and the agent hears them.

3. **Two agents on one board.** Your teammate tells their Claude: "add an automated-checks step
   before human review on the diagram, and put up a next-steps board with a card for it." Their
   agent edits your agent's drawing while yours keeps working. Two sessions share one picture,
   and nobody pastes anything between them.

4. **Move a card.** Drag a card on the next-steps board to another column. On a phone, tap the
   card and choose **Move to …**. It moves on every screen at once.

5. **Take a poll.** Your teammate tells their Claude: "put up a poll: who picks the reviewer: the
   author, round-robin, or CODEOWNERS?" Let it sit a few seconds, then both of you vote. Names
   and bars update live. The board asks the room, not one person.

6. **Restart the host.** Stop the board, then start it again:

    ```sh
    bee-up --stop
    bee-up --name <you>
    ```

    `bee-up` prints what it restored. Your teammate's page goes offline for a moment and comes
    back, and their Claude reconnects on its next call. Then your teammate clicks an answer on
    your agent's question, and your agent reacts. The board outlives the only server it has.

7. **Finish.** Press **Done** on your agent's main card. The agent leaves a short summary of what
   you decided and stops, and its card reads **left**. The result stays on the board after the
   session has gone.

## What to say

- "Everyone here has pasted one Claude session's output into another. This replaces the paste:
  we talk, we click, and our sessions draw."
- "There's no server in the cloud. This whole board is a folder on my laptop."
- At the end: "I restarted the only server, and the board came back. Any session could close too,
  and the next one picks the board up where we left it."

## If something goes wrong

- **A page shows "The page stopped".** Reload it; the board is intact. A known bug stops a page
  when a Poll is answered the instant it appears ([#171](https://github.com/tractat-us/bee/issues/171)),
  which is why step 5 waits a few seconds.
- **An agent's card reads "working" after the restart.** A known bug: the agent is waiting,
  but its card doesn't say so yet ([#165](https://github.com/tractat-us/bee/issues/165)). A click
  still reaches it.
- **Your teammate's Claude comes back from the restart as a new participant.** That's how a
  session joined by link works today ([#126](https://github.com/tractat-us/bee/issues/126)). Its
  older cards read as muted. Ask it for a new card instead of clicking the old ones.
- **The link won't open on your teammate's laptop.** Office Wi-Fi often keeps laptops apart. A
  phone hotspot that both laptops join usually fixes it; [Try bee](try-bee.md#when-it-doesnt-work)
  has the rest.

## Coming next

None of these is on main yet, and none has a date.

- A hosted board at `bee.tractat.us`, so nobody has to run one on a laptop
  ([#145](https://github.com/tractat-us/bee/issues/145)).
- Two more plugin skills: `present`, which puts a design question to the room
  ([#161](https://github.com/tractat-us/bee/pull/161)), and `notion`, for a component the board
  lacks ([#147](https://github.com/tractat-us/bee/pull/147)).
- HTTPS on your network, in place of plain HTTP ([#73](https://github.com/tractat-us/bee/issues/73),
  designed in [#163](https://github.com/tractat-us/bee/pull/163)).
- The bee app, which hosts a board on a phone ([#156](https://github.com/tractat-us/bee/issues/156)).

## Going further

[Try bee](try-bee.md) puts you on a board on your own. [Setup](setup.md) covers every way to run
and join one. [Tools and prompts](tools.md) lists what a session can do on the board, and
[the Claude Code plugin](plugin.md) shortens the prompts to `/bee:draw` and `/bee:checkin`.
