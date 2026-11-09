# Board state

Truth for how a board's state is held and replicated: each surface's document, the claims beside
it, and who is present. The design and its reasons are in
[spec §6](../superpowers/specs/2026-11-06-bee-design.md#6-the-boards-state); this page records
what the code holds today.

## Invariants

1.  **A surface whose open hasn't arrived is no surface yet.** kuilt orders deltas per origin,
    never across origins, so another peer's write to a surface can reach a page before the open
    that created it ([#171](https://github.com/tractat-us/bee/issues/171)). `SurfaceDoc.meta`
    returns null until the open's meta has every field an open writes, and the board's reads
    (`openSurfaces`, `closedSurfaces`, `meta`) leave that surface out until it does. Meta of the
    wrong shape is corrupt, not early, and throws. The guard is permanent unless kuilt adds causal
    delivery across origins. Enforcer: the nullable return of `SurfaceDoc.meta` (compiler), with
    `BoardStateTest.aWriteThatArrivesBeforeItsSurfacesOpenWaitsUnshownUntilTheOpenArrives` and
    `SurfaceDocMetaTest`.
2.  **A surface write never changes a path's type and never leaves a phantom conflict.** Every leaf
    write starts from the register it read, appended entries are never edited, and a write that
    would turn an object, an append-only array, a whole array or a scalar into another refuses.
    Enforcer: `SurfaceDoc` is `internal`, so `BoardState` is the only way to write;
    `BoardStateTest.pathTypeChangeIsRefused`.
3.  **A claim target has at most one holder, and a stale command does nothing.** Every peer that
    folds the same committed log holds the same `ClaimTable`. A held claim carries the `(term,
    index)` of the entry that granted it, and a `Release` or `Complete` with another token applies
    as rejected. A `Reassign` or a token-less `Release` decides one orphaning, named by the
    orphaned hold's token, and is rejected once the target is orphaned again
    ([#83](https://github.com/tractat-us/bee/issues/83), [#182](https://github.com/tractat-us/bee/pull/182)).
    Enforcer: `ClaimTable`'s private constructor and `fold`; `ClaimTableTest`.
4.  **Only the host writes the claims mirror and the departed list.** Enforcer: `review`
    (`BoardState.mirrorClaims` is public; its one caller is `boardMirror` in `BeeHost.kt`).
5.  **A peer's presence never posts a stale `waiting`.** `Presence` owns a count of the waits in
    progress, and each post reads it under the lock that hands out the post's clock
    ([#184](https://github.com/tractat-us/bee/pull/184)). Enforcer: `Presence.update` takes no
    `waiting` parameter (compiler); `PresenceWaitingTest`.

## Current design

- **One `JsonCrdt` document per surface**, under its id in
  [`BoardState`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/BoardState.kt), written
  through [`SurfaceDoc`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/SurfaceDoc.kt).
  Meta, components and data are kept apart; action records sit in the board's own `Rga`, outside
  any surface ([spec §6.1](../superpowers/specs/2026-11-06-bee-design.md#61-surfaces)).
- **An object write merges.** It adds and updates keys and never removes one. The ruling on
  [#14](https://github.com/tractat-us/bee/issues/14) is that it replaces; see § Future.
- **A card order is stored whole, as one leaf.** Concurrent reorders conflict as values and the
  projection shows the greater one (`BoardStateTest.reorderingCannotDuplicateAnItem`). The
  `Board` Notion already renders by #14's rule: the order's ids at first occurrence, then any item
  the order lacks, sorted by id (`ordered` in
  [`board.js`](../../bee-notions/src/board.js)). So a card a concurrent reorder drops still shows,
  at the end.
- **A structured leaf is canonical JSON behind the `"\u0000json:"` prefix**
  (`SurfaceFormat.STRUCTURED_PREFIX`), and writers refuse data strings that start with it. #14
  confirmed it as v1's persisted format.
- **Claims commit through `kuilt-raft`, with the host as the one voter**
  ([spec §6.3](../superpowers/specs/2026-11-06-bee-design.md#63-claims-exactly-one)).
  [`ClaimTable`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/ClaimTable.kt) folds the
  committed log; an orphan decision is a `Poll` surface whose answerer's own client proposes
  ([`OrphanDecision.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/OrphanDecision.kt)).
  The table assumes each proposal reaches the log once, which kuilt doesn't yet promise
  ([#23](https://github.com/tractat-us/bee/issues/23)).
- **Changing the claims command format needs every replica restarted onto one version.** An older
  reader folds a fenced `Reassign` or `Release` as rejected while the voter applies it
  ([spec §6.4](../superpowers/specs/2026-11-06-bee-design.md#64-presence-and-failure)). Nothing in
  the code checks versions.
- **Presence is an `EphemeralMap` entry per peer**, re-posted every `Presence.EVERY` and aged out
  by `LivePresence` ([`Presence.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/Presence.kt)).

## Future

- **Object writes replace, and card order is an append-only `Rga` of ids:** the #14 ruling,
  implemented in Draft PR [#19](https://github.com/tractat-us/bee/pull/19), not merged. When it
  merges, it replaces the merge and whole-leaf order lines in § Current design, and this entry
  goes.
- **Dedup of claims log entries before production:** [#23](https://github.com/tractat-us/bee/issues/23).

## Settled — don't reopen

- **The open-before-write guard stays after kuilt fixes its gossip join.** Rejected: removing it
  once [kuilt#2916](https://github.com/tractat-us/kuilt/issues/2916) and
  [kuilt#3148](https://github.com/tractat-us/kuilt/issues/3148) are fixed. Those fixes make the
  open usually arrive first, but nothing guarantees it. ([#175](https://github.com/tractat-us/bee/pull/175),
  [#187](https://github.com/tractat-us/bee/pull/187), 2026-11-09)
- **Object writes merge.** Rejected for replace: a merge leaves stale keys behind, and its only
  delete locks the path to a scalar. ([#14](https://github.com/tractat-us/bee/issues/14), Iain, 2026-11-07)
- **A card order as one array that a concurrent add can lose.** Rejected for an `Rga` of ids.
  ([#14](https://github.com/tractat-us/bee/issues/14), Iain, 2026-11-07)

## Open

None yet.
