# Product

Truth for what a person meets in bee: the board they see, what they do on it, and what it takes to
get onto it. A rule here binds every subsystem that puts something in front of a person.

## Invariants

1.  **bee acts like a physical, infinite whiteboard: simplicity first, no clutter (Iain,
    2026-11-08).** In his words, quoted as said: <!-- vale House.Filler = NO -->"The point is to
    have something that really really acts like a physical, infinite whiteboard. We don't want to
    have any clutter."<!-- vale House.Filler = YES --> And: "We should always value simplicity."
    Enforcer: `review`. For every change a person can see or has to do, the reviewer
    checks:

    - Each new UI element, prompt, setup step, install path or required choice names the need it
      meets, and why nothing already there meets it. One that can't doesn't merge.
    - The least visible way to meet a need wins. Something an agent draws on the board when asked
      beats a permanent control, a default beats a setting, and one path beats two.
    - The board, not chrome, is the screen. Chrome stays small and quiet, and nothing joins it
      without the justification above.

    It's `review` because no compiler, Konsist rule or text scan can tell clutter from a need.

## Current design

- **The board is one page.** Open surfaces are cards in one column, newest first, and a card
  never moves once it's placed ([`BoardView.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BoardView.kt),
  [`BoardChrome.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BoardChrome.kt)).
  An empty board shows one quiet hint on how to start.
- **Clearing the board closes surfaces, it doesn't delete them.** Closed surfaces wait in a
  collapsed history strip ([`HistoryStrip.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/HistoryStrip.kt),
  [spec §6.1](../superpowers/specs/2026-11-06-bee-design.md#61-surfaces)).
- **The chrome is a bar and a collapsed "Details".** The bar holds the bee mark, the attached
  peers and whether the page is online. "Details" sits after the board, closed, with what the page
  knows for debugging ([`PageShell.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/PageShell.kt)).

Iain's product rulings in the spec stand beside invariant 1 and aren't restated here: bee is a
medium, not an app with screens ([§1](../superpowers/specs/2026-11-06-bee-design.md#1-the-idea)),
and building the tool instead of the work is an anti-goal
([§2](../superpowers/specs/2026-11-06-bee-design.md#2-goals-non-goals-anti-goals)).

## Future

- **Bring-your-own identity**, anonymous by default, or SSO or OAuth:
  [#149](https://github.com/tractat-us/bee/issues/149). Under invariant 1 the anonymous path stays
  the default and adds no step to joining.
- **Every way to install bee, and a one-click join from the board:** [#145](https://github.com/tractat-us/bee/issues/145).
  Under invariant 1, one path beats many, and the join adds no board chrome.
- **Hosted bee on a tractat.us subdomain, with admission by a person:** [#145](https://github.com/tractat-us/bee/issues/145)
  (a peer session is building it). Adds a step for the joiner, so it needs its justification.
- **The bee app, a Kotlin Multiplatform kuilt app that hosts a board on a phone:**
  [#156](https://github.com/tractat-us/bee/issues/156). Adds an install path.
- **A Compose A2UI renderer, in the app and maybe browsers:** [#158](https://github.com/tractat-us/bee/issues/158).
  No new chrome.
- **Make host / Evict on the peer bubbles:** [#164](https://github.com/tractat-us/bee/issues/164).
  Adds controls, so each must name its need.
- **A board-only view for projectors and the app:** [#168](https://github.com/tractat-us/bee/issues/168).
  Removes clutter.
- **HTTPS off loopback with a self-minted certificate:** [#73](https://github.com/tractat-us/bee/issues/73);
  the design is PR [#163](https://github.com/tractat-us/bee/pull/163) (`transport.md`, not yet merged).
  Adds a certificate prompt for the joiner unless trust is handled quietly.

## Settled — don't reopen

- **Simplicity is an invariant, not one goal weighed against others feature by feature.**
  Rejected: keeping it a spec goal traded off case by case. Iain made it fundamental. ([#152](https://github.com/tractat-us/bee/pull/152), 2026-11-08)
- **bee is a medium, not an app with screens.** Rejected: a fixed layout with a screen per task.
  (Iain, [spec §1](../superpowers/specs/2026-11-06-bee-design.md#1-the-idea), 2026-11-06)

## Open

- **Does "infinite" mean a pan-and-zoom canvas?** Today the board is a page of cards. Whether an
  endless 2D canvas is what Iain means, or a page that never runs out of room meets it:
  [#150](https://github.com/tractat-us/bee/issues/150).
