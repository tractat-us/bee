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

2.  **On a hosted board, nobody waits to be let in by someone who isn't there (Iain,
    2026-11-08).** A device asking to join while no person who can admit it is attached is
    refused at once, and no join request goes on the board. One that asked while someone was
    there closes when the last of them leaves. Iain can't approve someone while he isn't there.
    A person is a peer whose presence says it's human; that kind is declared, not attested
    ([bee#64](https://github.com/tractat-us/bee/issues/64)). A LAN board is unchanged: its
    request waits for whoever comes. Enforcer: `PairingDeskTest`'s
    `withNobodyHereAPairingIsRefusedAtOnceAndLeavesNoRequest`, with its control: one person
    attached, and the same dial gets a code. `PublicHostTest` holds it on a real host.

3.  **On a hosted board, an invite link admits whoever opens it, for a day, and asks their name
    ([#215](https://github.com/tractat-us/bee/issues/215), ruling of 2026-11-09).** Anyone on
    the board may press Invite and share the link. Whoever opens it types a name and joins, with
    a key of their own, and nobody on the board has to let them in. The link is reusable until it
    lapses, 24 hours after it was made. Every count is bounded: 25 devices per link, 10 live
    links per key, 100 live on the board, and 100 devices a day by invite. Without a link, a
    device pairs by code, as in invariant 2. Enforcer: `InvitesTest` and `HostedInviteTest`, and
    `hosted.spec.mjs` in a browser.

## Current design

- **The board is one page.** Open surfaces are cards in one column, newest first, and a card
  never moves once it's placed ([`BoardView.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BoardView.kt),
  [`BoardChrome.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BoardChrome.kt)).
  An empty board shows one quiet hint on how to start.
- **Clearing the board closes surfaces, it doesn't delete them.** Closed surfaces wait in a
  collapsed history strip ([`HistoryStrip.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/HistoryStrip.kt),
  [spec §6.1](../superpowers/specs/2026-11-06-bee-design.md#61-surfaces)).
- **Three ways onto a hosted board.** The owner opens it with the owner key. Anyone on it
  presses Invite for a link, and whoever opens that link types a name and presses Join
  ([`JoinByInvite.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/JoinByInvite.kt),
  [`Invites.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Invites.kt)). Without a
  link, the page shows a code for someone on the board to type in. The runbook is
  [Running hosted bee](../deploy.md#getting-on-the-board).
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
- **A hosted board's invite link admits whoever opens it.** Rejected: a link that admits no one,
  so every newcomer shows a code for someone on the board to type in. That was the 2026-11-08
  ruling; the owner wanted a link to hand someone that just lets them in. ([#215](https://github.com/tractat-us/bee/issues/215), 2026-11-09)
- **An invite is reusable for its day.** Rejected: a link that admits one device, then stops. One
  link should do for a group, and the per-link cap bounds a forwarded one.
  ([#215](https://github.com/tractat-us/bee/issues/215), 2026-11-09)
- **An invite needs nobody on the board to let its holder in.** Rejected: a link that still waits
  for someone on the board to admit. That's pairing by code again, and invariant 2 forbids waiting
  on someone who isn't there. ([#215](https://github.com/tractat-us/bee/issues/215), 2026-11-09)
- **A guess at an invite costs the host nothing.** Rejected: a per-address limit on unknown
  secrets. An IPv6 caller sidesteps it with a fresh address per guess, and the table it keeps
  grows without bound; the 256-bit secret is the defence. ([#218](https://github.com/tractat-us/bee/pull/218), 2026-11-09)

## Open

- **Does "infinite" mean a pan-and-zoom canvas?** Today the board is a page of cards. Whether an
  endless 2D canvas is what Iain means, or a page that never runs out of room meets it:
  [#150](https://github.com/tractat-us/bee/issues/150).
