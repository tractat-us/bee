# Deploying hosted bee

Hosted bee is one board on the public internet, at `https://bee.tractat.us`. It runs on a single
Fly.io machine, app `bee-tractat-us`, and stays on around the clock: the host there is the board's
always-on copy and its one voter, so the board outlives every laptop that joins it. This page is
for whoever deploys it.

## Deploy

Run the **deploy** workflow from the Actions tab, or:

```sh
gh workflow run deploy.yml --repo tractat-us/bee
```

It builds `bee-host` on a GitHub runner, rolls the machine to the new image, then polls
`https://bee.tractat.us/board-id` until it answers 200. Before DNS points at Fly, pass
`-f base_url=https://bee-tractat-us.fly.dev`; that check passes only if the host accepts that name.

Nothing deploys on a push or a merge. Each run is a deliberate, manual step.

## One machine, one volume

The board lives on the volume `bee_data`, mounted at `/data`. A Fly volume attaches to exactly one
machine, and the host locks its data directory, so the app runs exactly one machine:

- the deploy strategy is `rolling` (`fly.toml`), never `bluegreen`, which starts a second machine
  with no volume: an empty board.
- the workflow deploys with `--ha=false`, so Fly creates no standby.
- never `flyctl scale count 2` or `flyctl machine clone`. A second machine gets its own empty
  volume, or none, and splits the board in two.

A deploy restarts the one machine, so every peer's socket drops for a few seconds and redials.

## The owner key

The hosted host refuses to start without `BEE_OWNER_KEY` in its environment. Two copies exist:

- Iain's Keychain: `security find-generic-password -s bee.tractat.us -a owner -w`.
- the Fly secret `BEE_OWNER_KEY` on `bee-tractat-us`, which Fly hands to the machine.

It never goes in this repo, a workflow, a log, or a command's arguments. To rotate it, store the
new key in the Keychain (`-w` last, with no value, prompts for it), then import it on stdin.
`printf` is a shell builtin, so the key never appears in a process's arguments:

```sh
security add-generic-password -U -s bee.tractat.us -a owner -w
printf 'BEE_OWNER_KEY=%s\n' "$(security find-generic-password -s bee.tractat.us -a owner -w)" \
  | flyctl secrets import -a bee-tractat-us
```

Importing restarts the machine with the new key. Add `--stage` to wait for the next deploy instead.

## When the deploy fails its health check

Fly checks `GET /board-id` on the machine over its private network. Like every path, it passes
bee's site gate only when the call's `Host` is a name the host goes by. `flyctl checks list -a
bee-tractat-us` shows the check's last response. A 403 there quotes the `Host` the check sent, in
the gate's own words: `This host doesn't go by "<host>"`.

To roll back, find the previous image with `flyctl releases -a bee-tractat-us --image` and
redeploy it with `flyctl deploy --image <image> --ha=false`.

## One-time setup

Done on 2026-11-08; never repeat it for a running app. The app, in org `personal`:

```sh
flyctl apps create bee-tractat-us --org personal
flyctl volumes create bee_data --size 1 --region iad -a bee-tractat-us
flyctl ips allocate-v4 --shared -a bee-tractat-us
flyctl ips allocate-v6 -a bee-tractat-us
flyctl certs create bee.tractat.us -a bee-tractat-us
```

That gave the shared IPv4 `66.241.125.173` and the IPv6 `2a09:8280:1::1f2:99b7:0`. The owner key
went to the app as a staged secret, imported on stdin as above. The repo secret `FLY_API_TOKEN` is
a deploy token scoped to this one app (`flyctl tokens create deploy -a bee-tractat-us`), so the
workflow can deploy `bee-tractat-us` and nothing else.

DNS for `tractat.us` is at Pair Networks:

| Name | Type | Value |
|------|------|-------|
| `bee` | A | `66.241.125.173` |
| `bee` | AAAA | `2a09:8280:1::1f2:99b7:0` |
| `_acme-challenge.bee` | CNAME | `bee.tractat.us.oqnrzjj.flydns.net.` |

Fly issues the certificate once these resolve; `flyctl certs show bee.tractat.us -a
bee-tractat-us` reports it.

## What ships

`Dockerfile`, `fly.toml` and `.github/workflows/deploy.yml` are the whole of it. The image holds a
Java 25 runtime and `bee-host`'s distribution, with the page it serves. It builds nothing, so no
build credential reaches Fly. The host runs as the unprivileged user `bee`, on a `shared-cpu-1x`
machine with 512 MB of memory and a 256 MB heap.
