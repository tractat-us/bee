# Spike 4: exactly-once claims through one durable voter

Task 6 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md). The question was whether
`kuilt-cluster` fits as-is for claims: one host voter that never claims, and learners (browsers and
bridges) that claim through it. **It does, from kuilt `0.7.0-dev.1692`.** Two clients racing for
one target get exactly one winner, and both see it. The voter is a sequencer, not consensus: it is
the whole voter set, so a claim commits once it appends it.

The first run, against `0.7.0-dev.1690`, stopped at the fit check: a second learner cut the first
off the log (bee#11). Iain ruled to fix kuilt first. kuilt#3121 (the learner reconciler) and
kuilt#3122 (learner removal after a grace) fixed it, published as `0.7.0-dev.1692` (kuilt
`7654ac4c2`).

## Fit-check answers

Measured against kuilt `0.7.0-dev.1692`.

| Question | Answer | Evidence |
|---|---|---|
| (a) A real WebSocket from wasm | **Yes.** `clusterClient` over `KtorClientLoom` on the browser's WebSocket claims `Won` through the fixture voter. | `WasmClaimTest.browserClaimsThroughTheVoter`, with `-Pbee.realsocket.tests=true` |
| (b) The server proposes its own `Depart` | **Yes.** `voterNodes[HOST_NODE].propose(…)` commits inline on the single voter. | `ClaimsClientTest.aLearnerFoldsTheLogFromIndexOneIncludingTheVotersDepart`, `VoterRestartTest` |
| (c) Learners receive committed entries from index 1 or an `Install` | **From index 1, as entries.** The voter publishes no snapshot, so raft has no `Install` to send. | `aLearnerFoldsTheLogFromIndexOneIncludingTheVotersDepart` |
| A second learner stays on the log | **Yes, from `0.7.0-dev.1692`.** Both learners stay in the config and fold the voter's later `Depart`. | `ClaimsRaceTest.aSecondLearnerLeavesTheFirstOnTheLog`, `ClaimsSmokeTest.aSecondLearnerLeavesTheFirstOnTheLogOverRealSockets` |

## The race

`ClaimsRaceTest.twoClientsRaceAndExactlyOneWins` runs 50 rounds. In each, two learners claim the
same target at once. It asserts exactly one `Won` per round, and that the mirror names the winner
with the winner's token.

| kuilt | Rounds without exactly one `Won` |
|---|---|
| `0.7.0-dev.1690`, first run | 47 of 50 |
| `0.7.0-dev.1690`, control rerun on this branch | 46 of 50 |
| `0.7.0-dev.1692` | 0 of 50 |

On `0.7.0-dev.1692` both learners folded the whole log, through index 102. In this virtual-time
schedule the first client wins every round, so the test pins exactly-once and agreement, not
fairness.

On `0.7.0-dev.1690`, every claim committed at the voter, but the first learner had been dropped from
the config, so it never saw its own wins and reported `Unavailable`. The cause was
`ServerCluster.admitLearner` building each config from the one the cluster was constructed with, so
each admission kept only the newest learner. kuilt#3121 replaced per-connection admission with one
reconciler that builds from the leader's current membership.

The two-learner tests now wait for the `Depart`'s own index before checking that both learners
folded it. Before, they read the voter's index straight after `depart` returned, ahead of the fold.

## Learner removal: wired, but it never fires

`ClaimsVoter` builds its cluster with `learnerGrace = 30.seconds`. Every page load, bridge and
`/clear` dials under a fresh `NodeId`, so without removal the learner set grows with every
connection the host has seen. 30 s is the spec's heartbeat reconnect window (§6.4). It is well over
a reconnect: `clusterClient` re-joins at once on a tear. Removal costs a claim nothing: the table
holds claims by `PeerIdentity`, and a learner that comes back after the grace is admitted again and
catches up.

**A kuilt gap stops the grace from ever starting.** `ServerCluster.admitLearner` holds each admitted
room in `awaitCancellation()`, and only its `finally` withdraws the learner's spoke from
`RaftRelayHub.learnersFlow`. The grace starts from that withdrawal. Neither `KtorRoomHost` nor bee's
in-memory host cancels a room's `onRoom` coroutine when its connection closes. `RaftRelayHub`'s
spoke collector ends on a closed seam, but it doesn't withdraw the spoke. So a learner that
disconnects stays in the config until the relay itself stops. kuilt's removal tests disconnect by
cancelling the server-side `onRoom` job in `ServerClusterSim`, which no production host does.

- In memory: `ClaimsRaceTest.aLearnerGoneForTheGraceLeavesTheConfig`. One learner's scope is
  cancelled, and its id is still in the config 60 s of virtual time later. The test is `@Ignore`d,
  naming the gap.
- On real sockets (Netty, `KtorRoomHost`, CIO), with a throwaway diagnostic not kept in the tree:
  `smoke-b`'s client scope was cancelled, and 45 s later the config still listed both learners.

This bears only on the config's size, not on correctness. A dead learner's id stays in the config,
and the leader keeps replicating to it.

## The learner's id must equal the client's `NodeId`

The server takes a learner's `NodeId` from the roster, which `KtorClientLoom` fills from its
`selfPeerId`. The client names itself in `clusterClient`'s `clientNodeId` and `clusterConfig`. If
the two differ, the voter replicates to an id the client doesn't answer to. bee has no production
`clusterClient` yet: Task 7 builds it. Today the three test sites (`ClaimsRig.client`,
`ClaimsSmokeTest.client` and `WasmClaimTest`) each build both from one `NodeId`. The membership
assertion in `aSecondLearnerLeavesTheFirstOnTheLog` pins that the voter admits the ids the clients
chose. Task 7
should build the loom and the client in one function that takes a single `NodeId`, so the two can't
differ.

## Other kuilt findings

These were found on `0.7.0-dev.1690`. They weren't rechecked on `0.7.0-dev.1692`.

- **`ServerCluster.close()` leaves the relay running, and the relay then throws.** `start()` runs in
  the caller's scope, not the cluster's. When that coroutine ends after `close()`,
  `admitLearner`'s `finally` calls `overlay.evict`, and the overlay's `Quilter` is already closed:
  `IllegalStateException: Quilter(…#overlay-dir) is closed`. `ClaimsVoter.close()` cancels and
  joins the relay before it closes the cluster.
- **A refused dial ends a `clusterClient`'s reconnect loop with an uncaught exception.** With the
  voter stopped, the loop's next `SeamRoomFactory.join` throws `ConnectException: Connection
  refused`, and nothing retries. This bears on Review Focus 4, where the host is down when someone
  acts. The smoke closes its clients before it stops the voter.
- **No in-memory multi-connection `RoomHost`.** `LoomRoomHost` hosts one room, and `KtorRoomHost`'s
  constructor over a `Loom` is internal. The deterministic tests carry `InMemoryClaimsHost` (in
  `ClaimsRaceTest.kt`), which gives every dial its own room, as `/ws/claims` does, and holds a
  dial's server-to-client sends through `HoldableConnection`.

## A bee build finding

A local run with `-Pbee.realsocket.tests=true` leaves `bee-web/build/karma-config.d/fixture.js`
behind, and `:bee-web:karmaConfig` stays `UP-TO-DATE` on the next run without the flag. So the next
`check` loads a dead voter's port, and `WasmClaimTest` fails with `Unavailable` instead of skipping.
CI starts clean, so it doesn't see this. Deleting the file works around it. The fix belongs in
`bee-web/build.gradle.kts`, which Task 6 doesn't own.

## Measurements

These are first-claim latencies, so each includes the join and the learner's admission. They are
single runs, not steady-state numbers.

| Where | `0.7.0-dev.1690` | `0.7.0-dev.1692` |
|---|---|---|
| Virtual time, in memory, one learner | 100 ms | 100 ms |
| Real loopback sockets, JVM (CIO to Netty) | 265 ms | 189 ms |
| Real loopback socket, wasm in headless Chrome | 174 ms | 217 ms |

**The log after the race:** 102 committed entries, with nothing compacted. That's 100 claims, the
election no-op and one membership change. The reconciler admitted both learners in that one change,
where `0.7.0-dev.1690` committed one per admission (103 entries).

## What was built

- `bee-core`: `ClaimCommand` (JSON on the wire, identities in their `toString()` form),
  `ClaimTable` (immutable, folds `Entry`, `Internal` and `Install`), `ClaimsClient`.
- `bee-host`: `ClaimsVoter` over `DurableStoreRaftStorage`, and a real `FixtureVoterMain`.
- Green: `ClaimTableTest` (10), `ClaimsClientTest` (3, including
  `lateCommitOfTimedOutClaimIsReleased`, which reds when the late release is disabled),
  `ClaimsRaceTest` (2 of 3, the third `@Ignore`d above), `VoterRestartTest` (2), `ClaimsSmokeTest`
  (2) and `WasmClaimTest`.

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
