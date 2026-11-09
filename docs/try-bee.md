# Try bee

You've seen bee, or someone sent you a link to their board, and you want to try it yourself. This
page gets you onto a board in a few minutes, from a Mac with Claude Code.

bee is one live board that a few people and their Claude sessions share. You talk and click; the
sessions draw on the board, read your clicks back and redraw. The board lives on one laptop, not
in the cloud, and it outlasts every session that touches it.

There are two ways in. **Joining** someone's board needs nothing installed. **Your own board**
needs Homebrew, or a zip and an Apple-silicon Mac.

## Join someone's board

You need the link the board's owner sent you. It looks like `http://192.168.1.5:8233/?k=…`.

1. Add your name to the end of the link, `&name=<you>`, and open it in your browser. The board
   opens, and shows who else is on it. Without a name, you appear as `you`.

2. Bring your Claude. Press **Bring your Claude** in the board's header, then **Copy**, and
   paste the line into a terminal. It looks like this:

    ```sh
    claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee 'http://192.168.1.5:8233/mcp?k=…&name=<you>'
    ```

    It adds bee to Claude Code for every folder, replacing any bee you added before, so it's safe
    to run again with a new link. Nothing else gets installed.

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
board goes offline for a moment, the owner probably restarted it. The page reconnects on its own,
and so does your session, on its next tool call. Cards your session drew before the restart then
read as muted, because it comes back as a new participant ([#126](https://github.com/tractat-us/bee/issues/126)).

## Your own board

1. Install bee with [Homebrew](https://brew.sh), on a Mac or on Linux:

    ```sh
    brew install tractat-us/tap/bee
    ```

    Type the whole name: plain `brew install bee` installs an unrelated tool. Homebrew brings the
    Java bee needs.

    Without Homebrew, use the kit, `bee-kit-darwin-arm64.zip` (about 113 MB), from whoever sent
    you here. It runs on Apple-silicon Macs only and carries its own Java. Unzip it and clear
    macOS's download quarantine, so the scripts may run:

    ```sh
    cd ~/Downloads                     # or wherever you saved it
    unzip bee-kit-darwin-arm64.zip
    cd bee-kit-darwin-arm64
    xattr -dr com.apple.quarantine .
    ```

    From the kit's folder, type `./bee-up` wherever this page says `bee-up`.

2. Start your board:

    ```sh
    bee-up --name <you>
    ```

    It opens the board in your browser, prints a join link and copies it to your clipboard. Send
    that link to anyone you want on the board; they follow [Join someone's board](#join-someones-board).
    The first time, macOS asks whether `java` may accept incoming connections. Press **Allow**,
    or nobody else can reach you.

3. Start a Claude session on your board. `bee-up` prints the line; it looks like this:

    ```sh
    claude --mcp-config ~/.bee/mcp.json --allowedTools 'mcp__bee__*'
    ```

    Then type `/mcp__bee__whiteboard <topic>`, as in [Join someone's board](#join-someones-board).
    A session started this way keeps its place on the board through a restart.

4. Optional: to have bee in **every** Claude session you start, in any folder, add it once:

    ```sh
    claude mcp add --scope user --transport http bee 'http://127.0.0.1:8233/mcp?name=<you>'
    ```

    Every `claude` you start then joins your board, including one-off `claude -p` runs and any
    sessions your tools start for you, and each stays listed for two minutes after it exits.
    That suits a dashboard (next section); leave it out if you run many sessions at once.

The board is a folder, `~/.bee/host`. Stop it with `bee-up --stop`; `bee-up` brings it back,
with the same link, and everyone on it reconnects by themselves.

## Use it every day: one board for all your sessions

With bee in every session, the board becomes a dashboard for all the agents you have running. In
each repo's Claude session, type:

```text
/mcp__bee__checkin
```

Each session keeps one card on the board: what it's working on, how far it has got, and any
decision it needs from you, as buttons. Click an answer and that session acts on it. Press
**Done** or **Pause** on a card and that session stops checking in. Each card also says whether
its session is **listening** for your click, **working**, or has left; a card whose session has
left is greyed out.

For a design conversation, `/mcp__bee__whiteboard <topic>` draws a diagram and questions you
work through together.

One habit helps: when a session is waiting on the board, press **Esc** before you type to it.
Otherwise it may hold your message for up to 20 seconds.

## When it doesn't work

**The link won't open from another laptop.** Many office Wi-Fi networks stop laptops reaching
each other, even on the same network. bee needs one thing only: the other laptop must reach port
8233 on the board's laptop. The quickest fix is a phone hotspot that both laptops join. The owner
then runs `bee-up` again, which prints a new address, and sends the new link. If that fails,
each person runs [their own board](#your-own-board) instead.

**Nobody can reach your board.** macOS's firewall may be blocking `java`. Open System Settings →
Network → Firewall → Options and allow `java` incoming connections.

**The link has a name like `mylaptop.local` in it.** Use the one with numbers,
`http://192.168.…:8233/?k=…`. Some networks don't pass `.local` names between laptops.
`bee-up` prints both, and copies the numeric one.

**The page says it's offline and stays that way.** Reload it.

**Claude's bee tools stop working, and `/mcp` shows bee as failed.** The board was wiped or
moved, and your session still remembers the old one. Type `/mcp` in the session, pick `bee` and
reconnect it, or restart Claude.

**You want to start again from nothing.** On the board's laptop:

```sh
bee-up --stop
rm -rf ~/.bee/host
bee-up --name <you>
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
    | Make the old link stop working | `bee-up --new-link` |
    | Cut off every device and session | `bee-up --forget-devices` |

    Either one prints a new link, and the board survives it. Send the new link to the people you
    still want on the board. To bring their Claude back, they open the new link and paste
    its **Bring your Claude** line, which replaces the old one.

## Stop or uninstall

```sh
bee-up --stop                        # stop your board; it is kept for next time
claude mcp remove --scope user bee   # take bee out of Claude Code
rm -rf ~/.bee                        # delete your board, its keys and its logs
```

Then run `brew uninstall bee`, or delete the `bee-kit-darwin-arm64` folder. If you only joined
someone else's board, the `claude mcp remove` line is all you need.

## Going further

[Setting up bee in Claude Code](setup.md) covers running bee from a checkout, the `/clear` hook,
showing the board on a phone or a room screen, and how pairing and keys work.
[The design](superpowers/specs/2026-11-06-bee-design.md) says why bee is built the way it is.
