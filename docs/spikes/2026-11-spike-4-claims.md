# Spike 4: exactly-once claims through one durable voter

Task 6 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md). The question was whether
`kuilt-cluster` fits as-is for claims: one host voter that never claims, and learners (browsers and
bridges) that claim through it. **It does, from kuilt `0.7.0-dev.1692`.** Two clients racing for
one target get exactly one winner, and both see it. The voter is a sequencer, not consensus: it is
the whole voter set, so a claim commits once it appends it.

The first run, against `0.7.0-dev.1690`, stopped at the fit check: a second learner cut the first
off the log (bee#11). Iain ruled to fix kuilt first. kuilt#3121 (the learner reconciler) and
kuilt#3122 (learner removal after a grace) fixed it, published as `0.7.0-dev.1692` (kuilt
`7654ac4c2`). The grace itself first fires on `0.7.0-dev.1693` (kuilt `e6044eb42`, kuilt#3130).

**The in-memory results before `2d19414` ran partly in real time** (bee#25). `ClaimsRig` passed
kuilt's `identified(…)` a bare `TestCoroutineScheduler`, which isn't a dispatcher, so the seams'
read and write loops ran on `Dispatchers.Default` inside virtual-time tests. That covers every
in-memory number recorded before `2d19414`, including the 47-of-50 first run and the 46-of-50
control rerun on 1690, the 100 ms virtual-time latency, the 102- and 103-entry log counts, and the
red controls on the old client below. `2d19414` passes `StandardTestDispatcher(testScheduler)`.
Those numbers weren't rerun, except the race: rerun on `2d19414`'s rig against
`0.7.0-dev.1693`, it still comes to 0 of 50, with each client winning 25 and the log committed
through 102.

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
same target at once, each after a seeded delay of up to 200 ms, so either may reach the voter
first. Every round must come to exactly `Won(t)` for one client and `Lost(winner)` for the other,
with the mirror holding the winner under `t`. At the end, both learners' tables must equal the
voter's.

| kuilt | Rounds without exactly one `Won` |
|---|---|
| `0.7.0-dev.1690`, first run | 47 of 50 |
| `0.7.0-dev.1690`, control rerun on this branch | 46 of 50 |
| `0.7.0-dev.1692` | 0 of 50, with each client winning 25 |

The 1690 rows predate the seeded delay, when the first client won every round. On
`0.7.0-dev.1690`, every claim committed at the voter, but the first learner had been dropped from
the config. So it never saw its own wins and reported `Unavailable`. `ServerCluster.admitLearner`
built each config from the one the cluster was constructed with, so each admission kept only the
newest learner. kuilt#3121 replaced per-connection admission with one reconciler that builds from
the leader's current membership.

## A claim is judged by its own id

Review of this PR found that `ClaimsClient` told its own claims apart by `(target, by)`. A person's
tabs all claim as one `human:` identity (spec §5.1), so that pair doesn't name one proposal, and
three paths broke exactly-once:

- **Another tab's win.** Tab B timed out, tab A won, and B took A's committed claim for its own late
  win and released A's token. A held a `Won` for a free target.
- **An earlier win.** A claim that timed out released any hold by its claimant, a win from an
  earlier claim included.
- **A retry.** It forgot the claim it retried. When that first claim committed and won, the target
  stayed held under a token the caller never learned, and the retry reported `Lost`.

Now each `Claim` carries a `ProposalId`: the proposer's Raft `NodeId`, a random incarnation drawn
once per client, and a per-client serial. kuilt keeps a `NodeId` unique across the cluster and bee
mints a fresh one per dial, so ids are unique by construction. The incarnation covers a reused
`NodeId`, since a client folds the log from index 1 and meets every claim an earlier client under
that id made. That holds only if no two clients under one `NodeId` draw the same incarnation, so
their `Random`s must not be seeded from the `NodeId`. The client judges a claim only at the first
committed entry with its id, so a duplicate entry can't change the client's verdict. An entry whose
target or identity doesn't match the claim that id names is ignored too. A claim
reported `Unavailable`, or cancelled by its caller, is released only if its own entry won, with
that entry's token. The release retries, up to five times, until the table shows the target no
longer held under that token. `ClaimsClient.unreleased` names any the retries couldn't free.

kuilt's `LogEntry.dedupKey` couldn't serve as the id. `ClusterClient.propose` draws the serial
inside the call and returns it only on success, so a claim that timed out has no key its proposer
knows.

| Test (`ClaimsClientTest`) | On the old client | Now |
|---|---|---|
| `aTabThatTimedOutNeverReleasesAnotherTabsWin` | red: the target ends `Free` | green |
| `aTimeoutNeverReleasesAnEarlierWin` | red: the target ends `Free` | green |
| `aClaimThatWinsAfterItsRetryStartedIsReleased` | red: the retry got `Lost(iain)`, the target is held under token `(1, 4)` | green: `Lost(iain)`, then `Free` |
| `aCancelledClaimThatWinsLateIsReleased` | red, with cancellation not abandoning: held under `(1, 4)` | green |

The release's retry has no test that tells it from a single attempt. The in-memory rig can hold a
send but not drop it, and a held release still lands once let go. Nor does any test pin the
target-and-identity check in the client's judgement: with ids unique by construction, no rig makes
two claims share one.

**A known wart:** a caller that retries a timed-out claim can be told `Lost(iain)`, that it lost to
itself, when its first claim committed first. The client then releases that first win, so the
target ends `Free`. The caller has to claim again.

**Duplicate entries aren't handled yet** (bee#23). `ClaimTable` folds every entry, so a second
entry carrying the same `Claim`, committed after that claim's `Release`, wins again under a token
nobody holds. No duplicate arrives on `0.7.0-dev.1692` within one incarnation, but kuilt's
forwarding contract allows one: `ClientIdentity.Auto` leaves it to the consumer's
`ClientSessionTable`. Production needs that table folded on `entry.dedupKey`, and kept bounded by
dropping a session when it closes. No kuilt API exposes a session-close event yet: kuilt#3130,
which closed kuilt#3127, ends the learner's admission but adds no such hook. That gap is
tractat-us/kuilt#3129.

## Who may depart

**Only agents depart** (spec §6.4). The table rejects a `Depart` of a human, and
`ClaimsVoter.depart` refuses one with an `IllegalArgumentException`.

**v1 trusts the log on who proposed a `Depart`.** The spec says the host commits it, but any learner
can propose one, and the table can't tell. `LogEntry.dedupKey.clientId` would be the only marker,
and it isn't reliable: the proposer stamps it, and a learner can pick it. `clusterClient` takes a
`ClientIdentity`, so a learner can pass `ClientIdentity.Durable` with any id string. It also picks
the `NodeId` that an `Auto` id is built from. So v1 assumes every learner is honest about
`Depart`, as it already does about whose name a `Claim` carries. Enforcing it needs an
authenticated proposer, which the host doesn't have yet.

## Learner removal after a grace

`ClaimsVoter` builds its cluster with `learnerGrace = 30.seconds`. Every page load, bridge and
`/clear` dials under a fresh `NodeId`, so without removal the learner set grows with every
connection the host has seen. 30 s is the spec's heartbeat reconnect window (§6.4). It is well over
a reconnect: `clusterClient` re-joins at once on a tear. Removal costs a claim nothing: the table
holds claims by `PeerIdentity`, and a learner that comes back after the grace is admitted again and
catches up.

**It works from kuilt `0.7.0-dev.1693`.** On `0.7.0-dev.1692` the grace never started
([tractat-us/kuilt#3127](https://github.com/tractat-us/kuilt/issues/3127)):
`ServerCluster.admitLearner` held each admitted room in `awaitCancellation()`, and only its
`finally` withdrew the learner's spoke from `RaftRelayHub.learnersFlow`, where the grace starts.
Nothing cancelled a room's `onRoom` coroutine when its connection closed, in `KtorRoomHost` or in
bee's in-memory host. kuilt#3130 ends the admission when the learner leaves the room's roster.

`ClaimsRaceTest.aLearnerGoneForTheGraceLeavesTheConfig` cancels one learner's client scope. That
runs the client's shutdown, which sends a `Goodbye` and then closes its in-memory link: a clean
leave, not a silent death. kuilt's own tests cover the silent case. No bee code changed for the
test to pass.

| kuilt | In memory (virtual time) | Real sockets (Netty, `KtorRoomHost`, CIO) |
|---|---|---|
| `0.7.0-dev.1692` | Still in the config at 60 s; the test was `@Ignore`d | Still in the config 45 s later |
| `0.7.0-dev.1693` | Removed at 30 s; the other learner claims `Won` after | Removed after 30.08 s; the other learner claims `Won` after |

The 1692 in-memory row was rerun on `2d19414`'s rig, as a control on this pin bump. Both
real-socket rows came from a throwaway diagnostic not kept in the tree: one smoke learner's client
scope cancelled, the voter's membership watched for 60 s.

kuilt#3124 still keeps a removed peer's replication state on the leader.

## The learner's id must equal the client's `NodeId`

The server takes a learner's `NodeId` from the roster, which `KtorClientLoom` fills from its
`selfPeerId`. The client names itself in `clusterClient`'s `clientNodeId` and `clusterConfig`. If
the two differ, the voter replicates to an id the client doesn't answer to. bee has no production
`clusterClient` yet: Task 7 builds it. Today the three test sites (`ClaimsRig.client`,
`ClaimsSmokeTest.client` and `WasmClaimTest`) each build both from one `NodeId`. The membership
assertion in `aSecondLearnerLeavesTheFirstOnTheLog` pins that the voter admits the ids the clients
chose. Task 7 should build the loom and the client in one function that takes a single `NodeId`,
so the two can't differ.

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
  `ClaimsRaceTest.kt`), which gives every dial its own room, as `/ws/claims` does, and can hold
  either direction of a dial through `HoldableConnection`.

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
- Green: `ClaimTableTest` (13), `ClaimsClientTest` (7, including
  `lateCommitOfTimedOutClaimIsReleased`, which reds when the late release is disabled),
  `ClaimsRaceTest` (3, the grace test from `0.7.0-dev.1693`), `VoterRestartTest` (2), `ClaimsSmokeTest`
  (2) and `WasmClaimTest`.

### Where the code departs from the plan's interfaces

- `ClaimsClient(cluster, node, clock, scope, random)` takes a scope, because it collects the
  committed stream. It takes the cluster's `clientNodeId` and a `Random` for the incarnation, both
  for each claim's `ProposalId`.
- `ClaimCommand.Claim` carries a `ProposalId`, which the table ignores.
- `ClaimOutcome` adds `Retired`: the claimant was retired when its claim committed.
- `release` and `complete` return whether they applied, and `reassign` returns the new token or
  null.
- `ClaimsVoter.start` has an overload that takes a `DurableStore`, which the tests use with
  `InMemoryDurableStore`. The `File` overload wraps a `FileChannelDurableStore`.
- `ClaimsClient.unreleased` names late wins it couldn't release.
- A transport failure from `propose` (`LeadershipLostException`, `ForwardedProposalFailedException`,
  `NotLeaderException`) reports `Unavailable`. Anything else propagates.
