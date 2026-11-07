# Spike 4: exactly-once claims through one durable voter

Task 6 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md). The question was whether
`kuilt-cluster` fits as-is for claims: one host voter that never claims, and learners (browsers and
bridges) that claim through it. **It doesn't, yet.** A single learner works end to end, in the JVM
and in the browser. A second learner cuts the first one off the log. Step 1's rule applies: the
spike stops here, and A4's fallback is Iain's call.

## Fit-check answers

Measured against kuilt `0.7.0-dev.1690` (kuilt `280de6b49`).

| Question | Answer | Evidence |
|---|---|---|
| (a) A real WebSocket from wasm | **Yes.** `clusterClient` over `KtorClientLoom` on the browser's WebSocket claims `Won` through the fixture voter. | `WasmClaimTest.browserClaimsThroughTheVoter`, with `-Pbee.realsocket.tests=true` |
| (b) The server proposes its own `Depart` | **Yes.** `voterNodes[HOST_NODE].propose(…)` commits inline on the single voter. | `ClaimsClientTest.aLearnerFoldsTheLogFromIndexOneIncludingTheVotersDepart`, `VoterRestartTest` |
| (c) Learners receive committed entries from index 1 or an `Install` | **From index 1, as entries.** The voter publishes no snapshot, so raft has no `Install` to send. A learner admitted after the voter's `Depart` folds that `Depart`. | `aLearnerFoldsTheLogFromIndexOneIncludingTheVotersDepart` |
| A second learner stays on the log | **No. This one fails.** | below |

### The failure: admitting a learner drops the one before it

`ServerCluster.admitLearner` builds the new configuration from the configuration the cluster was
**constructed** with, not from the current one (`ServerCluster.kt:185-190` at `280de6b49`, and
unchanged at kuilt `f83d01b43`):

```kotlin
val withLearner = ClusterConfig(
    voters = voterConfig.voters,
    learners = voterConfig.learners + learnerId,   // voterConfig.learners is always empty
)
```

A learner-set-only change commits as `Simple(target)`, which replaces the learner set. So each
admission leaves exactly one learner: the newest. The voter stops replicating to every earlier one.
An earlier learner's proposals still forward and commit, but it never sees them, so its claims time
out as `Unavailable` while the voter holds them for it.

The same result holds in memory and on real sockets:

- `ClaimsRaceTest.aSecondLearnerLeavesTheFirstOnTheLog` (virtual time) and
  `ClaimsSmokeTest.aSecondLearnerLeavesTheFirstOnTheLogOverRealSockets` (Netty, `KtorRoomHost`,
  CIO). After `a` claims, then `b` joins and claims, then the voter departs `b`: the voter is
  through index 6, `a` folded through 3, `b` through 6. The voter's membership is
  `learners=[claims-b]` (`[smoke-b]` on sockets).
- `ClaimsRaceTest.twoClientsRaceAndExactlyOneWins`: all 100 claims commit, through index 103. The
  first client wins every round at the voter, but it folded only through index 1, so it reports
  `Unavailable` 50 times, and the second client reports `Lost`. Exactly one `Won` per round fails
  in 47 of 50 rounds. The 3 that pass are rounds the second client happened to win.

These three tests are `@Ignore`d, with a comment, so `check` stays green. Each turns green, with no
change to bee, once kuilt admits learners from the current membership, as `CoreLearnerAdmission`
already does (`current.learners + next`). Remove the `@Ignore`s then.

**The tracking issue misreads this.** kuilt#3119's third defect says a learner is "never removed
from membership" and the config grows without bound. In fact each admission removes all the others.
The two fixes interact: once admission keeps the current learners, the unbounded growth that entry
describes becomes real.

## Other kuilt findings

- **`ServerCluster.close()` leaves the relay running, and the relay then throws.** `start()` runs in
  the caller's scope, not the cluster's. When that coroutine ends after `close()`,
  `admitLearner`'s `finally` calls `overlay.evict`, and the overlay's `Quilter` is already closed:
  `IllegalStateException: Quilter(…#overlay-dir) is closed`. Under `runTest` that fails the test.
  `ClaimsVoter.close()` cancels and joins the relay before it closes the cluster.
- **No in-memory multi-connection `RoomHost`.** `LoomRoomHost` hosts one room, and `KtorRoomHost`'s
  constructor over a `Loom` is internal. The deterministic tests carry `InMemoryClaimsHost`
  (in `ClaimsRaceTest.kt`), which gives every dial its own room, as `/ws/claims` does, and holds a
  dial's server-to-client sends through `HoldableConnection`.

## Measurements

These are first-claim latencies, so each includes the join and the learner's admission. They are
not steady-state numbers.

| Where | Claim latency |
|---|---|
| Virtual time, in memory, one learner | 100 ms |
| Real loopback sockets, JVM (CIO to Netty) | 265 ms |
| Real loopback socket, wasm in headless Chrome | 174 ms |

**The log after the race:** 103 committed entries. That's 100 claims, the election no-op and two
learner-admission config entries, with nothing compacted. The race ran against the defect, but
every claim committed at the voter, so the count stands.

## What was built

- `bee-core`: `ClaimCommand` (JSON on the wire, identities in their `toString()` form),
  `ClaimTable` (immutable, folds `Entry`, `Internal` and `Install`), `ClaimsClient`.
- `bee-host`: `ClaimsVoter` over `DurableStoreRaftStorage`, and a real `FixtureVoterMain`.
- Green: `ClaimTableTest` (10), `ClaimsClientTest` (3, including
  `lateCommitOfTimedOutClaimIsReleased`, which reds when the late release is disabled),
  `VoterRestartTest` (2), `ClaimsSmokeTest.claimOverRealSockets` and `WasmClaimTest`.

### Where the code departs from the plan's interfaces

- `ClaimsClient(cluster, clock, scope)` takes a scope. It collects the committed stream, which
  takes a coroutine.
- `ClaimOutcome.Lost(holder)` has a nullable holder: null when the target was free but the
  claimant is retired.
- `release` and `complete` return whether they applied, and `reassign` returns the new token or
  null.
- `ClaimsVoter.start` has an overload that takes a `DurableStore`, which the tests use with
  `InMemoryDurableStore`. The `File` overload wraps a `FileChannelDurableStore`.
- A claim's verdict is read at its own index: the client keeps the target's status right after each
  of the last 256 entries. A claim whose verdict is evicted before its proposer reads it reports
  `Unavailable`, and if it won, the client releases it.
