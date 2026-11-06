# AGENTS.md

## What this is

**Bee** is a shared, agent-drawn board: a communication medium for a person or a small team and
their coding agents. People talk; agents redraw the board as A2UI surfaces; every peer sees the
same thing, and the board outlives any one peer. See `README.md` for the plain-language picture
and `docs/superpowers/specs/` for the design.

**Status: design stage.** No build exists yet. Don't scaffold code ahead of an approved spec and
plan.

## Bee and kuilt

Bee is an application built on [kuilt](https://github.com/tractat-us/kuilt). The split:

- **Generic pieces belong in kuilt**: anything a consumer other than Bee could want. That covers
  surfaces on `JsonCrdt`, action records, exactly-once claims over `kuilt-raft`, a voter that never
  claims, and presence. Bee proves a piece here first, then it moves to kuilt through a kuilt PR.
- **Bee owns the application**: the daemon, the MCP bridge, the A2UI catalog ("Neon"), the
  sandboxed `Sketch` component, policy about who shares what, and deployment.
- **A kuilt need is a kuilt issue, not a local patch.** Never fork, vendor or monkey-patch kuilt
  here. File the gap in `tractat-us/kuilt` and hand it to the kuilt peer session, if one is
  running. Work around it in Bee only behind a comment naming that issue.
- **Consume kuilt by coordinates**, with a presence-gated composite build for side-by-side work:
  `if (file("../kuilt").exists()) includeBuild("../kuilt")`.
- Before hand-rolling networking, shared state, presence, reconnect, claims or storage, load the
  `kuilt-primitives` skill. kuilt probably ships it.

## Relation to kuilt#2869

kuilt#2869 (shared human-agent workspaces) is **paused** and stays separate. Bee borrows its
input-history contract: a result keeps the record of what it was based on. Revisit #2869 for
reusable pieces after the spec, after the plan, and during execution.

## Conventions

- Kotlin Multiplatform + Gradle, JDK 25: in a non-interactive shell, run
  `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.2-tem` first.
- Test methods: no `test` prefix; multi-assert tests use `assertAll()`.
- Changes go through PRs. Never use the word "chore" in commits or PR titles.
- `next-plan.md` and `morning-report*.md` are session scratch. They're gitignored and never
  committed.
- References to other public `tractat-us` projects are fine. Ask before citing third-party
  trackers.
