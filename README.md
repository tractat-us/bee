# bee

A quilting bee is a group of people gathered around one quilt. Each person works their own corner
and they talk while they sew.

bee is that, for a team and their coding agents. Everyone shares one live board. The people talk
out loud. The agents redraw the board as the conversation moves. One minute it's a poll, the next a
list of who's doing what, then a code review. After that, maybe nothing at all, because it's lunch.

The board remembers. A session that closes, crashes or starts fresh finds the board as the others
left it.

**New here?** [Try bee](docs/try-bee.md) puts you on a board in a few minutes, from a Mac with
Claude Code.

## Three ways in

**Join a coworker's board.** They send you a link. Open it in a browser and you're on the board.
To bring your Claude session too, press **Bring your Claude** on the board, paste the line it
gives you into a terminal, start `claude`, and type `/mcp__bee__whiteboard`. Nothing gets
installed.

**Run your own.** From a checkout, `bin/bee-up --name <you>` starts a board on your network, opens
it in your browser, and prints a join link to send. For someone with no checkout and no JDK,
`bin/bee-bundle` builds a zip that runs a board with nothing else installed. Building from a
checkout needs credentials for [kuilt's packages](docs/bee-and-kuilt.md#how-bee-gets-kuilt); the
zip needs none.

**Watch all your sessions at once.** Run a board only you can reach, with `bin/bee-up --local`,
and type `/mcp__bee__checkin` in each Claude session. Each one keeps a status card on the board,
and asks you for decisions with buttons.

[Setup](docs/setup.md) walks through each of these. [Tools and prompts](docs/tools.md) lists what
a session can do on the board.

<!-- plugin page: docs/plugin.md -->

## Where it stands

Early, but a board runs end to end: sessions draw, people click, sessions redraw, and the board
survives a host restart. The plan's [remaining tasks](docs/superpowers/plans/2026-11-06-bee-v1.md)
and each spike's notes in [`docs/spikes/`](docs/spikes/) say what is still open. The full docs are
at <https://tractat-us.github.io/bee/>.

bee is built on [kuilt](https://github.com/tractat-us/kuilt), which keeps every copy of the board
in step. [bee and kuilt](docs/bee-and-kuilt.md) says which part does what.

## License

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). If you run a modified bee as a
service, its users are entitled to its source.
