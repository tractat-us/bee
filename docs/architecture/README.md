# Architecture

These are bee's living documents. Each is **truth for how its part of bee is today**, and each has
the same five sections: *Invariants* (numbered, every one naming its enforcer or saying `review`),
*Current design* (as built), *Future* (decided but not built, each with its issue), *Settled —
don't reopen* (one line per rejected option), and *Open* (undecided, each with its issue).

**A design decision is a PR that edits the living doc.** Its body carries Context, Decision,
Rejected and Consequences, and the rejected options land in that doc's Settled ledger in the same
diff. An open draft is the proposal. There are no ADRs. Read the doc and its Settled ledger before
proposing; re-raising a Settled option means saying why its recorded reason no longer holds.

The spec under `docs/superpowers/specs/` is a plan. As it ships, what it decided lands here.

| Doc | Truth for |
|---|---|
| [`product.md`](product.md) | what a person meets: the board, what they do on it, and what it takes to get onto it |
| [`board-state.md`](board-state.md) | how a board's state is held and replicated: surface documents, claims and presence |

## Census

`drift-audit` appends one line per census: the date, the numbers that moved, and whether a full
audit was due. None has run yet.
