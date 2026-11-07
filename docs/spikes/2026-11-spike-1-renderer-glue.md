# Spike 1: Kotlin/wasm drives the A2UI Lit renderer

Task 1 of the [bee v1 plan](../superpowers/plans/2026-11-06-bee-v1.md). The question was whether a
Kotlin/wasm page can hand the stock A2UI Lit renderer its messages and get user actions back. It
can, with no workaround.

## What is pinned

- **A2UI:** `@a2ui/lit` `0.15.0` and `@a2ui/web_core` `0.15.0`, exact, in
  `bee-notions/package.json`. They implement **v0.9.1**, with envelope `"version": "v0.9"`, from the
  `v0_9` entry points. On 2026-11-06, `web_core` exports `./v1_0`, but `lit` exports only `.`,
  `./ui`, `./0.8`, `./v0_8` and `./v0_9`. So v1.0 is out (amendment A9).
- **kuilt:** `kuilt-bom` `0.7.0-dev.1690`, which is publish run 1690 at kuilt `280de6b49`. It
  resolved from Tigris as planned.
- **Toolchain:** Gradle 9.4.1, Kotlin 2.4.20, JDK 25 (temurin 25.0.2 in CI), Ktor 3.5.2,
  coroutines 1.11.0, serialization 1.12.0, `@playwright/test` 1.64.1.

## The bundling route

`bee-web` depends on the local package as `npm(project.file("../bee-notions"))`. Kotlin's Gradle
plugin installs it with Yarn into the wasm npm project, and webpack bundles it with the Kotlin
output. `A2uiRenderer.kt` binds `@bee/notions` with `@JsModule` on an `external object`, and only
strings cross: `process(messagesJson)` returns a JSON array of errors, and `onAction` passes each
action as JSON.

Lit's `accessor` fields needed nothing. The published packages ship compiled ES2022, with the
decorators already lowered to `__esDecorate` helpers and `#private` storage. Webpack 5.108 parses
that unchanged. The generated lockfile is `kotlin-js-store/wasm/yarn.lock`, the plugin's default
path for wasm.

`<a2ui-surface>` renders into light DOM. The basic catalog's own elements may use shadow roots, so
the tests search shadow roots too.

## Bundle size

This is the production distribution packed into `bee-host` under `web/`, for the spike page:

| File | Raw | gzip -9 |
|---|---|---|
| `bee.js` (renderer, Lit, zod, Kotlin glue) | 501,252 B | 134,739 B |
| `*.wasm` (Kotlin, kuilt not yet linked in) | 258,096 B | 96,893 B |

Webpack reports three warnings on the production build. I didn't triage them; they look like the
default asset-size hints.

## Integrity and delete behaviour

- **Create on an existing id** is refused. `processMessagesWithErrors` returns one
  `{"version":"v0.9","error":{"code":"INTEGRITY_ERROR","surfaceId":…,"message":"Surface <id> already exists."}}`,
  and the surface already shown stays as it was. `A2uiRenderer` keeps track of which surface ids are
  open, and sends a reopen as `deleteSurface` and then `createSurface`. Tests:
  `createOnAnExistingIdIsAnIntegrityError` and `reopenWithoutDeleteReplacesTheSurface`.
- **`deleteSurface`** removes the surface model, and the host removes its `<a2ui-surface>`. A later
  create with the same id renders fresh (`reopenAfterDeleteRendersAgain`).
- **`updateDataModel` with `value` omitted** deletes the value at the path, and a `Text` bound to it
  renders empty (`dataModelDeleteRemovesTheValue`).
- An action reaches the processor's listener as web_core's `ActionPayload`:
  `{name, surfaceId, sourceComponentId, timestamp, context, catalogId?}`. `A2uiJson.decodeAction`
  ignores the extra fields.

The browser console shows one warning: `[MarkdownDirective] can't render markdown because no
markdown renderer is configured`. `@a2ui/markdown-it` is an optional peer of `@a2ui/lit`, and it
isn't installed. Text renders as plain text.

## Workarounds

None against kuilt or A2UI. Three choices in the build stand in for things the plan names:

- **The Karma config directory is generated.** `useConfigDirectory` takes one directory. So
  `build/karma-config.d/` gets a copy of `karma.config.d/`, plus `fixture.js` when the real-socket
  flag is set.
- **The fixture voter's classpath goes through a file.** A task in `bee-web` can't resolve
  `bee-host`'s configuration while it runs. So `:bee-host:fixtureVoterClasspath` writes the path
  to `bee-host/build/fixture-voter-classpath.txt`. The voter reports its port on stdout as
  `BEE_FIXTURE_VOTER_PORT=<n>`, and the build injects it as `window.__BEE_FIXTURE_VOTER_PORT__`.
- **Playwright starts the host only once a spec exists.** Task 1 has no spec and no host `Main`.
  CI runs `npx playwright test --pass-with-no-tests` until Task 4. The host command line,
  `--port <n> --data-dir <dir>`, is fixed in `e2e/playwright.config.mjs` for Task 3 to read.

## Hand check

The production page was served statically and driven with Playwright's browser. "Hello from
Kotlin" and a Press button rendered. Two clicks showed "Kotlin received press from press-button
(2)", so the action reached Kotlin through the `actions` flow.
