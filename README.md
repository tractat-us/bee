# bee

A quilting bee is a group of people gathered around one quilt. Each person works their own corner
and they talk while they sew.

bee is that, for a team and their coding agents. Everyone shares one live board. The people talk
out loud. The agents redraw the board as the conversation moves. One minute it's a poll, the next a
list of who's doing what, then a code review. After that, maybe nothing at all, because it's lunch.

The board remembers. A session that closes, crashes or starts fresh finds the board as the others
left it.

**Status:** early, but a board runs end to end. A Claude session draws a question on the board,
you click it in a browser, and the session reads the click and redraws. The board survives a host
restart. [Run a board](docs/setup.md#run-a-board) is the shortest way to try it. The plan's
[remaining tasks](docs/superpowers/plans/2026-11-06-bee-v1.md) and each spike's notes in
[`docs/spikes/`](docs/spikes/) say what is still open.

bee is built on [kuilt](https://github.com/tractat-us/kuilt), which keeps every copy of the board
in step. [bee and kuilt](docs/bee-and-kuilt.md) says which part does what.

## License

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). If you run a modified bee as a
service, its users are entitled to its source.
