# Publishing bee

bee's library modules go to Maven Central under the group `us.tractat.bee`. The rest of bee
(the host, the browser client, the companion, the MCP bridge) ships as applications and never
goes to Central. Nothing has been released yet: [#131](https://github.com/tractat-us/bee/issues/131)
says what has to be true first.

## What publishes

A module publishes when its build applies the `bee.publish` plugin,
[`build-logic/src/main/kotlin/bee.publish.gradle.kts`](build-logic/src/main/kotlin/bee.publish.gradle.kts).
Today that's `bee-core` and `bee-a2ui`, each for `jvm` and `wasmJs`.

To publish another module, apply the plugin and add a line to `moduleDescription()` saying what it
does. That line is what Maven Central search and klibs.io show. A module without one fails the build,
and so does one that isn't Kotlin Multiplatform.

## Versions

| Build | Version | Where |
|---|---|---|
| Any local or CI build | `<beeVersionLine>.0-dev`, such as `0.1.0-dev` | Nowhere, or your `~/.m2` |
| A release | `x.y.z` from the tag `vx.y.z` | Maven Central |

`beeVersionLine` lives in `gradle.properties`. Bump it in a PR when a release breaks the API.

## Check a publication locally

```bash
timeout 600 ./gradlew publishToMavenLocal
ls ~/.m2/repository/us/tractat/bee/
```

No credentials or signing key are needed. Without them the build signs nothing and has no Central
repository, so it can't upload by accident.

## The public API

Each published module keeps a dump of its public API under `api/`. `./gradlew check` fails when
the code's API no longer matches it. If you meant the change, run `./gradlew updateKotlinAbi` and
commit the new dump with it, so a reviewer sees the API change in the diff.

## Cutting a release

1. Make sure bee builds against a kuilt release on Maven Central, not a `-dev` build. The
   workflow refuses to release otherwise: a `-dev` kuilt is only on Tigris, so nobody could
   resolve the release's dependencies.
2. Push a tag:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

   Or run the `publish` workflow by hand with an explicit `version`.
3. The [`publish` workflow](.github/workflows/publish.yml) signs the artifacts and uploads them to
   the [Central Portal](https://central.sonatype.com) as a pending deployment. It also creates a
   GitHub Release with generated notes.
4. Log in to the Portal, check the deployment, and release it. Nothing releases automatically:
   `automaticRelease = false` in `bee.publish`.
5. Once Central has synced it, it appears at
   `https://repo1.maven.org/maven2/us/tractat/bee/bee-core/<version>/`.

The workflow needs four secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`
and `SIGNING_PASSWORD`. Its preflight step names any that are missing. They're the same ones
kuilt releases with, under the same verified `us.tractat` namespace.
