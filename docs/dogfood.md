# bee's own board

You work on bee, and you want its plan, last night's report and the questions waiting on Iain on
one board, live, instead of in three files and a GitHub search. This page sets that board up on
your Mac from a bee checkout. It runs apart from any board you use day to day, on port 8240 with
its own folder, so the two never mix.

## Start it, draw it, open it

From the root of your main bee checkout, not a worktree: the seeder draws the `plan.yaml` of the
checkout it runs from, so a worktree would put its branch's plan on the shared board.

```sh
bin/bee-dogfood --no-open      # starts the board, or reprints its links if it's running
bin/bee-dogfood-seed           # draws the plan, the morning report and the decisions
open http://localhost:8240/
```

The seeder prints what it did to each of the three surfaces:

```text
dogfood-plan: Opened
dogfood-morning: Opened
dogfood-decisions: Opened
Board: http://127.0.0.1:8240/
```

Run `bin/bee-dogfood-seed` again whenever the files change. Each surface is redrawn in place, under
the same id, so a second run never adds a copy; a surface whose files haven't changed says
`Unchanged`. `bin/bee-dogfood --stop` ends the host and keeps the board for next time.

## What the three surfaces show

- **Plan** is [`docs/project/plan.yaml`](project/schema.md) as a board: one column per status,
  one card per item. Each card names its issue or PR and its title, linked to that issue or PR,
  with the first sentence of its summary on a quieter line below. Only the five most recently landed items show under Done, with one card counting the
  rest, so the board stays readable.
- **Morning report** is the newest `morning-report*.md` in the main checkout: its title, its TL;DR
  (or first paragraph) and its section headings. Before the first overnight run it says there's
  none yet.
- **Decisions** is every open issue and PR labelled `decision-needed` or `needs-design`, one card
  each, with a link and the question its description asks. It asks GitHub through `gh`, so `gh`
  must be signed in; `--no-github` leaves this surface saying so.

The files stay the source of truth ([bee#21](https://github.com/tractat-us/bee/issues/21)'s ruling): a card dragged to another column on the
board moves back at the next seed.

## Where things live

| What | Where | Change it with |
|---|---|---|
| The board, its keys and the host's log | `~/.bee-dogfood/` | `BEE_DOGFOOD_HOME` |
| The port | 8240 | `BEE_DOGFOOD_PORT`, or `--port` |
| The seeder's name on the board | `dogfood-agent` | `bin/bee-dogfood-seed --name <agent>` |

`bin/bee-dogfood` is [`bee-up`](setup.md) with those defaults, kept to this machine; it takes the
same flags, and rebuilds the checkout's host first when the sources are newer than the build. The
seeder joins over HTTP MCP, the way a Claude session with nothing installed does, and lives in
[`bee-dogfood/`](../bee-dogfood/src/main/kotlin/us/tractat/bee/dogfood/Main.kt).
