# Try bee

You've seen bee, or someone sent you a link to their board, and you want to try it yourself. This
page gets you onto a board in a few minutes, from a Mac with Claude Code.

bee is one live board that a few people and their Claude sessions share. You talk and click; the
sessions draw on the board, read your clicks back and redraw. The board lives on one laptop, not
in the cloud, and it outlasts every session that touches it.

There are two ways in. **Joining** someone's board needs nothing installed. **Your own board**
needs a zip and an Apple-silicon Mac.

## Join someone's board

You need the link the board's owner sent you. It looks like `http://192.168.1.5:8233/?k=…`.

1. Open the link in your browser. The board opens, and shows who else is on it.
   <!-- verify: does the page ask for your name, or must the link carry &name=<you>? -->

2. Bring your Claude. Press **Bring your Claude** in the board's header. It shows one command;
   copy it. It looks like this:
   <!-- verify: the button label "Bring your Claude" and the exact command it shows -->

    ```sh
    claude mcp add --transport http bee 'http://192.168.1.5:8233/mcp?k=…&name=<you>'
    ```

    Run it in a terminal, in the folder you'll start Claude from. As shown, it adds bee for that
    folder only. To have bee in every folder, put `--scope user` after `add`.

3. Start Claude and give it something to draw:

    ```sh
    claude
    ```

    ```text
    /mcp__bee__whiteboard code-review
    ```

    Within half a minute your session puts a drawing and some questions on the board, on every
    screen at once. Click an answer and your session reacts. Talk to it as usual and it keeps the
    board current. Keep the topic to one word, or one hyphenated word: Claude Code may pass only
    the first word.

The link admits whoever holds it, across the owner's restarts, until they make a new one. If the
board goes offline for a moment, the owner probably restarted it; the page and your session
reconnect on their own.

## Your own board

You need the kit, `bee-kit-darwin-arm64.zip` (about 113 MB), from whoever sent you here. It runs
on Apple-silicon Macs only and carries its own Java, so there is nothing else to install.

1. Unzip it and clear macOS's download quarantine, so the scripts may run:

    ```sh
    cd ~/Downloads                     # or wherever you saved it
    unzip bee-kit-darwin-arm64.zip
    cd bee-kit-darwin-arm64
    xattr -dr com.apple.quarantine .
    ```

2. Start your board:

    ```sh
    ./bee-up --name <you>
    ```

    It opens the board in your browser, prints a join link and copies it to your clipboard. Send
    that link to anyone you want on the board; they follow [Join someone's board](#join-someones-board).
    The first time, macOS asks whether `java` may accept incoming connections. Press **Allow**,
    or nobody else can reach you.

3. Add bee to your own Claude Code. `bee-up` prints a `claude mcp add` line for that. Run it with
   `--scope user`, so every session in every repo has bee:
   <!-- verify: the exact `claude mcp add` line bee-up prints, and whether it already carries --scope user -->

    ```sh
    claude mcp add --scope user bee …
    ```

4. Start `claude` anywhere and type `/mcp__bee__whiteboard <topic>`, as in
   [Join someone's board](#join-someones-board).

The board is a folder, `~/.bee/host`. Stop it with `./bee-up --stop`; `./bee-up` brings it back,
with the same link, and everyone on it reconnects by themselves.

## Use it every day: one board for all your sessions

With bee in every session, the board becomes a dashboard for all the agents you have running. In
each repo's Claude session, type:

```text
/mcp__bee__checkin
```

Each session keeps one card on the board: what it's working on, how far it has got, and any
decision it needs from you, as buttons. Click an answer and that session acts on it. Press
**Done** on a card and that session stops checking in.
<!-- verify: the card's contents and the "Done" label, once feat/checkin-prompt lands -->

For a design conversation, `/mcp__bee__whiteboard <topic>` draws a diagram and questions you
work through together.

One habit helps: when a session is waiting on the board, press **Esc** before you type to it.
Otherwise it may hold your message for up to 20 seconds.

## When it doesn't work

**The link won't open from another laptop.** Many office Wi-Fi networks stop laptops reaching
each other, even on the same network. bee needs one thing only: the other laptop must reach port
8233 on the board's laptop. The quickest fix is a phone hotspot that both laptops join. The owner
then runs `./bee-up` again, which prints a new address, and sends the new link. If that fails,
each person runs [their own board](#your-own-board) instead.

**Nobody can reach your board.** macOS's firewall may be blocking `java`. Open System Settings →
Network → Firewall → Options and allow `java` incoming connections.

**The link has a name like `mylaptop.local` in it.** Use the one with numbers,
`http://192.168.…:8233/?k=…`. Some networks don't pass `.local` names between laptops.
`bee-up` prints both, and copies the numeric one.

**The page says it's offline and stays that way.** Reload it.

**Claude's bee tools fail with "another board".** The board was wiped or moved, and your session
still remembers the old one. Type `/mcp` in the session, pick `bee` and restart it, or restart
Claude.
<!-- verify: the exact wording of the "another board" error -->

**You want to start again from nothing.** On the board's laptop:

```sh
./bee-up --stop
rm -rf ~/.bee/host
./bee-up --name <you>
```

A fresh board has a fresh link: send it again, and restart each Claude session joined to the old
one.

## Safety

- **Nothing is encrypted.** The board travels over plain HTTP on your network, so anyone on that
  network who watches the traffic can read it. Don't put secrets on the board, or anything you
  wouldn't say out loud in the room.
- **The link is a key.** Anyone holding it gets onto the board, and so does anyone with the
  `claude mcp add` command, which contains it. Treat both like a password.
- **Cutting someone off** is one command on the board's laptop:

    | To | Run |
    |---|---|
    | Make the old link stop working | `./bee-up --new-link` |
    | Cut off every device and session | `./bee-up --forget-devices` |

    Either one prints a new link, and the board survives it. Send the new link to the people you
    still want on the board. To bring their Claude back, they run `claude mcp remove bee`, then
    the new command from **Bring your Claude**.

## Stop or uninstall

```sh
./bee-up --stop                   # stop your board; it is kept for next time
claude mcp remove bee             # take bee out of Claude Code; add --scope user if you added it that way
rm -rf ~/.bee                     # delete your board, its keys and its logs
```

Then delete the `bee-kit-darwin-arm64` folder. If you only joined someone else's board, the
`claude mcp remove bee` line is all you need.

## Going further

[Setting up bee in Claude Code](setup.md) covers running bee from a checkout, the `/clear` hook,
showing the board on a phone or a room screen, and how pairing and keys work.
[The design](superpowers/specs/2026-11-06-bee-design.md) says why bee is built the way it is.
