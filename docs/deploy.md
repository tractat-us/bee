# Running hosted bee

Hosted bee is one board on the public internet, at `https://bee.tractat.us`. It runs on a single
Fly.io machine, app `bee-tractat-us`, and stays on around the clock: the host there is the board's
always-on copy and its one voter, so the board outlives every laptop that joins it. This page is
for whoever runs it.

## Getting on the board

Nobody reaches a hosted board from loopback. A device gets on by an invite link someone already
on made, or by a code while someone admitted is on the board to admit it. So the first person in
is always the owner, by key. Below,
`<your owner key>` is the owner key, from wherever you keep secrets. Never paste it on the board.

1. **Open the board with your key:**

    ```sh
    open "https://bee.tractat.us/?name=<you>#k=<your owner key>"
    ```

    Without `?name=`, the board shows you as `guest`. The key rides in the fragment, after `#`, which a browser never sends, so it never reaches
    Fly's proxy or the host. The page moves it out of the address bar into that tab's storage, and
    it never appears on the board or in a log. The browser's own history may still keep the
    visited URL, key and all. A new tab or another browser needs the link again, or pairs by code.

2. **Add your Claude** over HTTP MCP, with nothing to install:

    ```sh
    claude mcp add --scope user --transport http bee \
      "https://bee.tractat.us/mcp?name=<you>&k=<your owner key>"
    ```

    Here the key is a query, which Claude Code sends with each call; the host logs no request
    URLs. The line, key included, ends up in `~/.claude.json`.

3. **Invite someone:** press **Invite** on the board and send them the link it shows, or let
   them scan its code. The link looks like `https://bee.tractat.us/#i=<secret>`. Whoever opens
   it is asked for a name, presses **Join**, and is on the board, with a key of their own. Nobody
   has to be on the board to let them in. Your phone joins the same way.

    - **Anyone with the link can join, for a day.** It works for up to 25 devices. A tab keeps
      its link; for a new one, open the board in a new tab and press Invite there. Send it only
      to people you'd let on the board.
    - Each key may have 10 links live at once, and the board 100. The board lets in at most 100
      devices a day by invite. Past a limit, Invite or the joiner's page says so.
    - The secret rides in the fragment, like your owner key, so it never reaches Fly's proxy or
      a log. The host keeps only its hash, in `invites` in the data dir, so a deploy keeps every
      live invite.
    - Add `?name=<their name>` before the `#` to fill the name in for them.
    - **Without a link**, someone opening `https://bee.tractat.us/` gets a six-digit code instead.
      Someone already on the board types it in and presses **Admit**. With nobody admitted on the
      board, that request is refused at once. An invite that lapsed or was used up offers that
      code too.

4. **Their Claude:** once admitted, they open the board's **Bring your Claude** panel. It shows
   the same `claude mcp add` line with their own key in it.

5. **The owner key** is the Fly secret `BEE_OWNER_KEY`, and the host refuses to start without it.
   Keep the readable copy in your secret store. It never goes in this repo, a workflow or a log.
   To rotate it, mint a new key, keep it in your secret store, load it into `$key`, then import it
   on stdin, never as an argument:

    ```sh
    printf 'BEE_OWNER_KEY=%s\n' "$key" | flyctl secrets import -a bee-tractat-us
    ```

    The app restarts with the new key. The old one then stops admitting, in any `claude mcp`
    config too. Rotating it leaves invites and the keys they gave alone.

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
went to the app as a staged secret, imported on stdin as above with `--stage`, so the first deploy
releases it. The repo secret `FLY_API_TOKEN` is a deploy token scoped to this one app
(`flyctl tokens create deploy -a bee-tractat-us`), so the workflow can deploy `bee-tractat-us`
and nothing else.

DNS for `tractat.us` is at Pair Networks:

| Name | Type | Value |
|------|------|-------|
| `bee` | A | `66.241.125.173` |
| `bee` | AAAA | `2a09:8280:1::1f2:99b7:0` |
| `_acme-challenge.bee` | CNAME | `bee.tractat.us.oqnrzjj.flydns.net.` |

Fly issues the certificate once these resolve; `flyctl certs show bee.tractat.us -a
bee-tractat-us` reports it.

## What ships

[`Dockerfile`](../Dockerfile), [`fly.toml`](../fly.toml) and
[`deploy.yml`](../.github/workflows/deploy.yml) are the whole of it. The image holds a Java 25
runtime and `bee-host`'s distribution, with the page it serves. It builds nothing, so no build
credential reaches Fly. The host runs as the unprivileged user `bee`, on a `shared-cpu-1x` machine
with 512 MB of memory and a 256 MB heap.

The host starts with `--client-ip-header Fly-Client-IP` because Fly's proxy puts the app's own
address rightmost in `X-Forwarded-For`: read from there, every caller shares one address, and one
device's pairing limits lock out everyone.
