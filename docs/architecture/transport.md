# Transport

Truth for how every peer reaches a bee host: the URLs it hands out, the schemes they use, and what
a client has to trust before it can join. A rule here binds the host, the page, the bridge, the
plugin, the scripts that print links, and the docs that teach them.

## Invariants

1.  **A host binds loopback alone unless it's started with `--lan`.** Enforcer: the `Reach` enum
    ([`HostConfig.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostConfig.kt)), whose
    `bind` is the only address the server is given.
2.  **Every call passes the site gate before any route runs.** Its `Host` names the host and the
    port it arrived on, and its `Origin`, if any, is the host's own. Enforcer: `installSiteGate`,
    installed on the application, not on chosen routes
    ([`SiteGate.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/SiteGate.kt)).
3.  **Off loopback, bee speaks only TLS: `https://` and `wss://` (Iain, 2026-11-08: "get entirely
    off http"), unless its owner starts it `--unencrypted` (Iain, 2026-11-09).** Plain HTTP is
    loopback's alone, by a loopback name. The one exception is a board its owner opts out of TLS,
    for a space they already trust: it serves plain `http://` and `ws://` on the LAN, and its page
    and every join line it prints call it **unencrypted**. TLS is the default; nothing turns it off
    but the flag. **This doesn't hold yet:** a `--lan` host serves plain HTTP today, with no flag.
    Enforcer: not built. It's the sealed listener type, whose one plain network variant takes a
    value only the flag's parse makes, in
    [§ Future, TLS off loopback](#tls-off-loopback-with-a-certificate-the-host-mints).
4.  **A client that can pin the host's key joins with no trust step.** The bridge and the bee app
    take the pin from the join link. Only a browser, which can't pin, trusts the host once. An
    unencrypted board has nothing to pin and asks no step of anyone. Enforcer: not built. It's the
    required pin on a TLS network `HostAddress` in
    [§ Future](#tls-off-loopback-with-a-certificate-the-host-mints).

## Current design

**Everything is plain HTTP and `ws://`.** On loopback that costs nothing: browsers treat
`http://localhost` and `http://127.0.0.1` as secure contexts. On the LAN it costs three things:

- **The wire is readable.** Anyone on the network can read the board, and the join key in every
  `?k=`. `bin/bee-up` says so when it prints the links: "Plain HTTP on your network: don't put on
  the board what you wouldn't say in the room."
- **A LAN page isn't a secure context.** It has no Web Lock, so it keeps no local copy of the board
  ([`BrowserStore.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BrowserStore.kt)).
  Copy falls back from the async clipboard
  ([`BringYourClaude.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BringYourClaude.kt)),
  and the microphone, Screen Wake Lock and share fail
  ([#73](https://github.com/tractat-us/bee/issues/73), [#156](https://github.com/tractat-us/bee/issues/156)).
- **A hosted bee can't sit behind an HTTPS proxy.** The site gate refuses any `Origin` but
  `http://` ([#145](https://github.com/tractat-us/bee/issues/145), § Hosted bee).

Where plain HTTP is assumed today, in the order a request meets it:

| Where | What it assumes |
|---|---|
| `BeeHost.start` ([`BeeHost.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/BeeHost.kt)) | one plain Netty connector on `config.bind` |
| `SiteRule.refusal` ([`SiteGate.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/SiteGate.kt)) | the page's origin is `http://` and the call's `Host`; the default port is 80 |
| `inviteEndpoint` ([`InviteEndpoint.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/InviteEndpoint.kt)) | join links, and so the invite QR, are `http://<authority>:<port>/?k=…` |
| host `main` ([`Main.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Main.kt)) | logs `bee host on http://…` and the `http://` join link |
| [`bin/bee-up`](../../bin/bee-up), [`bin/bee-join`](../../bin/bee-join), [`bin/bee-bundle`](../../bin/bee-bundle) | scrape `bee host on http`; print `http://` links and the `claude mcp add` lines; write `BEE_HOST ws://127.0.0.1:<port>` |
| `DEFAULT_HOST` ([`BeeHostVariable.kt`](../../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/BeeHostVariable.kt)) | `ws://127.0.0.1:8233` |
| `HttpBridges` ([`HttpBridges.kt`](../../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/HttpBridges.kt)) | the host's own `/mcp` bridge dials `ws://127.0.0.1` |
| `McpCommand` ([`BringYourClaude.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BringYourClaude.kt)) | a teammate's Claude joins by `claude mcp add --transport http` at the page's origin |
| the plugin's `board_url` ([`plugin.json`](../../plugins/bee/.claude-plugin/plugin.json)) | defaults to `http://127.0.0.1:8233/mcp` |
| [`setup.md`](../setup.md), [`try-bee.md`](../try-bee.md), [`plugin.md`](../plugin.md) | teach `http://` links and `/mcp` URLs |
| e2e (`playwright.config.mjs`, `host.mjs`, the LAN specs) and `brew-release.yml`'s smoke test | `http://` base URLs |

Two pieces are already scheme-agnostic. `HostAddress` parses `https://` and `wss://` and maps
one to the other
([`HostAddress.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/HostAddress.kt)).
The page dials its sockets at the scheme and host it was loaded from
([`Main.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/Main.kt)).

## Future

### TLS off loopback, with a certificate the host mints

[#73](https://github.com/tractat-us/bee/issues/73). **Iain, 2026-11-08, quoted as said:**
<!-- vale House.Filler = NO -->"Here I basically want something self-minted."<!-- vale House.Filler = YES -->
No domain, no issuing service, nothing that needs the internet. Folds
into [`setup.md`](../setup.md) and [`try-bee.md`](../try-bee.md) when it lands.

**The host is its own certificate authority.** On first start a `--lan` host mints two keys and
keeps them in its data dir beside its pairing keys:

- **A host CA**, ECDSA P-256, long-lived, and **name-constrained** to what a LAN host can be:
  `localhost`, `.local`, loopback, and the private ranges (10/8, 172.16/12, 192.168/16,
  fc00::/7). A device that trusts it trusts it for those names alone, so a stolen host key can't
  pose as any public site. Which platforms honour name constraints on a root someone added is
  checked in slice 1, on hardware.
- **A leaf**, signed by that CA, for `localhost`, `127.0.0.1`, `::1`, the host's `.local` name
  and every LAN address it has. It lives at most 825 days, Apple's cap for any TLS server
  certificate. The host re-signs it, same key, whenever its addresses change. That's why there's
  a CA at all: a self-signed leaf would change with the laptop's address, and every device would
  have to trust it again.

**The pin is the CA's key.** It's the SHA-256 of the CA's public key, in base64url, carried in the join link
and the QR beside the key: `https://192.168.1.20:8233/?k=<key>&ca=<pin>`. Pairing already hands a
device a secret out of band; it now hands over the host's identity on the same trip.

**What each client does, and what it costs.**

| Client | How it trusts the host | Its step |
|---|---|---|
| the owner's page and Claude, on the host's machine | plain HTTP on loopback, a secure context already | none |
| the bridge, `bee-mcp` over stdio, on another machine | pins `ca=` from the join link `bee-join` was given | none |
| the bee app ([#156](https://github.com/tractat-us/bee/issues/156)) | pins `ca=` from the QR it scanned | none |
| a teammate's Claude Code | through the stdio bridge; on a TLS board the HTTP MCP line is retired off loopback | none |
| a browser on another laptop | the host CA in the OS store: macOS Keychain "Always Trust"; Windows Trusted Root; Chrome on Linux, its NSS store; Firefox, its own store | once per device, per host |
| Safari on an iPhone | the CA as a profile: download, Settings → Profile Downloaded → Install, then General → About → Certificate Trust Settings → on ([Apple](https://support.apple.com/en-us/102390)) | once per device, per host |
| Chrome on Android | Settings → Security → Encryption & credentials → Install a certificate → CA certificate. Chrome is reported to honour user CAs, though Android apps don't by default; slice 6 checks it on a phone | once per device, per host |
| any client of an `--unencrypted` board | trusts nothing: plain `http://` and `ws://`, as today | none, and nothing on the wire is encrypted |

The browser rows are the honest cost under [`product.md`](product.md) invariant 1, and the
design keeps them as small as it can:

- **The agent paths never touch browser trust.** The bridge is the main way a Claude joins
  another machine's board, and it pins. Claude Code's own HTTP client could trust the host only through
  `NODE_EXTRA_CA_CERTS`, set before it starts: setup, so it's not offered. A teammate's Claude
  joins through the brew-installed bridge ([#148](https://github.com/tractat-us/bee/pull/148)),
  which is #145's one-click join anyway.
- **A phone gets the app.** Once #156 ships, the QR opens the app, which pins with no OS step.
  Safari and Chrome on a phone still work, at the cost in the table.
- **A browser's step is one page, served by the host.** The join link's first visit shows the
  browser's own warning, since nothing trusts the host yet. Behind it the host serves a short
  "Trust this board" page with the one download for that platform and its steps, then sends the
  browser on to the board. On a TLS board every listener beyond loopback stays TLS: the warning
  is the trust-on-first-use moment, and the wire is encrypted even before it.
- **Room screens trust once.** A screen joins one host for months.
- **A board that needs no encryption skips the step entirely**, by the opt-out below.

**The opt-out: an unencrypted board.** Iain, 2026-11-09: a fully local board, in a space its
people already trust, should just connect. `bee-up --unencrypted` starts the host
`--lan --unencrypted`. It mints no CA and serves no trust page. It binds one plain listener on
every interface and prints plain `http://…/?k=…` links with no `ca=`, so every device joins the
way it does today. The flag names what the board becomes, in the same word the page shows. It's
the need invariant 1 asks a second path to name: the trust step buys nothing where the room is
already private. A board without it stays TLS, so the common case meets no setting.

- **It's labelled.** The page's status in the bar reads "unencrypted" when it was loaded by plain
  HTTP from an address that isn't loopback. That's a word in a status already there, not a new
  control. The host's join line in its log, `bee-up`'s links, and the invite the page shows each
  say "unencrypted". `bee-up` keeps today's line under the flag: don't put on the board what you
  wouldn't say in the room.
- **It costs what plain HTTP costs today.** The page isn't a secure context, so it keeps no local
  copy, and it gets no microphone, Screen Wake Lock or async clipboard (§ Current design).
- **A teammate's Claude keeps the HTTP MCP line.** It needs no CA here, so `bee-up` and the page
  go on offering `claude mcp add --transport http` at the board's `http://` origin, as today.
- **The flag fits one place.** The host refuses `--unencrypted` without `--lan`: loopback is plain
  already. It also refuses it on a hosted board (#145), whose TLS is the platform's. `bee-up`
  refuses it beside `--local`.
- **A link says which board it is.** A join link to a network address is `https://…&ca=…` for a
  TLS board and `http://…` for an unencrypted one. The bridge and the app read the scheme. They
  dial an unencrypted board plainly and say so where they report the board. They never fall back
  from one to the other.

**Where it lives.** kuilt ships the pieces as a self-signed leaf pinned by its SHA-256
(`generateDevTlsIdentity`, `fingerprintPinningTrustManager`, `pinnedTlsHttpClient` in
`kuilt-websocket`, from tractat-us/kuilt#1073). bee needs three things it lacks, each a kuilt
issue, and bee works around them only behind a comment naming that issue:

- an identity loaded from disk, since today it's minted in memory per run;
- a CA and leaf pair, with the leaf re-signed under the same CA;
- a pin on the CA's public key that checks the chain, instead of on the leaf's whole certificate.

kuilt-nw's TLS-PSK link isn't used: browsers can't speak TLS-PSK, the JDK has no external PSK, and
Network.framework does PSK over TLS 1.2 alone (tractat-us/kuilt#1560).

**One port, two listeners.** Plain HTTP on `127.0.0.1` and TLS on every other address, both on
8233, so the owner's URL and the join link name the same port. If the OS won't bind both,
Netty's `OptionalSslHandler` takes TLS and plain on one socket, and the host refuses plain from any
peer that isn't loopback. Slice 2 proves which one works on macOS and Linux. An unencrypted
board has one plain listener on every interface, as every `--lan` host has today.

**Slices, in order.** Each is one PR; the guard lands with the behaviour it guards.

1.  **Mint and keep the host's identity.** The CA and leaf in the data dir; the leaf re-signed when
    addresses change; the name constraint and the 825-day cap tested. File the three kuilt issues
    first. Check name-constraint support on an iPhone, an Android phone, and a Mac.
2.  **Serve TLS off loopback, and the `--unencrypted` opt-out.** A sealed `Listener`: `Loopback`
    takes no identity and binds only a loopback address; `Network` requires a `HostIdentity`;
    `Unencrypted` binds plain on a network address and requires an `UnencryptedOptIn`. That
    value's constructor is private to `HostConfig.kt`, whose parse makes one from
    `--lan --unencrypted` and nowhere else, so a test reaches it through `HostConfig.fromArgs`
    too. Any other plain listener on a network address doesn't compile, which is invariant 3's
    enforcer. The site gate takes `https://` from a TLS listener and `http://` from loopback or
    an `Unencrypted` listener, and the default port follows the scheme. `bee-up` gains
    `--unencrypted`; the host's join line and the page's status say "unencrypted" under it. This
    slice lands both together, so no `--lan` board loses plain HTTP before the flag exists.
3.  **Join links carry the pin.** `inviteEndpoint`, the host's log line, the QR (a longer link
    may bump its version) and `bin/bee-up` print `https://…?k=…&ca=…`. An unencrypted board
    still prints `http://…?k=…`. `HostAddress` gains a sealed trust: `Pinned(pin)` for a network
    address, `Loopback`, `Unencrypted` for a plain network address, and `Public` for #145. An
    `https://` or `wss://` network address with no pin doesn't parse, which is invariant 4's
    enforcer. A plain one parses only to `Unencrypted`. Only an `--unencrypted` host prints such
    a link, and every client that holds one has to handle it as unencrypted, since the `when`
    over the trust is exhaustive.
4.  **The bridge pins.** `bee-mcp` dials `wss://` through the pinning trust manager; `bee-join`
    passes the whole link through. An `Unencrypted` address dials plain `ws://`, and the bridge
    reports the board as unencrypted. `DEFAULT_HOST` and the loopback `HttpBridges` stay
    `ws://127.0.0.1`.
5.  **A teammate's Claude joins by the bridge.** `McpCommand` stops offering the HTTP MCP line off
    loopback on a TLS board and offers the bridge instead; an unencrypted board keeps the line. The
    plugin's `board_url` stays loopback-only until #145's `bee_join(link)` lands.
6.  **The browser's trust page.** `/ca.crt`, `/ca.mobileconfig`, and the page that serves them.
    Needs real-iPhone and Android validation.
7.  **Docs and e2e.** `setup.md`, `try-bee.md`, `plugin.md` and `bin/bee-bundle`'s help, which
    teach `--unencrypted` for a room whose people already trust each other and the network. A TLS
    `--lan` e2e project goes beside the loopback one. Today's plain LAN specs become the
    `--unencrypted` project and check the label. `bee-up` drops its plain-HTTP warning for a TLS board and keeps it under
    `--unencrypted`.

### Hosted bee gets its certificate from the platform

[#145](https://github.com/tractat-us/bee/issues/145) § Hosted bee, step 1. A hosted board sits
behind the platform's TLS, with a publicly trusted certificate, as fireworks-compose's server does
on Fly.io (`flyctl certs create`, its `docs/ops-deploy-server.md`). Every client, Claude Code's
HTTP MCP and claude.ai connectors included, trusts it with no step. The design is #145's;
this doc only fixes that it's `HostAddress`'s `Public` trust, and the site gate takes the public
URL's `https://` origin.

## Settled — don't reopen

- **A wildcard certificate under a bee-owned domain, the Plex model, isn't the LAN design.** It's
  the only option found that gives browsers no step. Plex runs it: each server gets
  `*.<hash>.plex.direct` from DigiCert, and Plex's DNS answers `1-2-3-4.<hash>.plex.direct` with
  `1.2.3.4` ([Valsorda](https://words.filippo.io/how-plex-is-doing-https-for-all-its-users/)).
  For bee that means a domain, an always-on issuing service holding DNS credentials and an ACME
  account, and a Public Suffix List entry so hosts don't share one rate-limit bucket of 50
  certificates a week ([Let's Encrypt](https://letsencrypt.org/docs/rate-limits/)). Every
  device then needs public DNS to resolve the name, so a LAN with no internet breaks. Routers and
  company resolvers with DNS-rebinding protection drop the answer, a private address, as
  [Plex documents](https://support.plex.tv/articles/206225077-how-to-use-secure-server-connections/).
  Rejected: Iain wants it self-minted. Reopen if the per-device trust step keeps people off the
  board. (Iain, 2026-11-08, this PR)
- **`sslip.io` or `nip.io` with Let's Encrypt.** A private address can't pass HTTP-01, and their
  zones aren't ours for DNS-01; `sslip.io` shares one rate-limit bucket among all its users. (this PR)
- **Tailscale's `ts.net` certificates.** They need every device on a tailnet, and every
  certificate's name goes into the public Certificate Transparency logs. (this PR)
- **Let's Encrypt certificates for IP addresses.** Public addresses only; a LAN address can't be
  validated. (this PR)
- **No loopback exception: the owner's page at the host's `https://` name too.** One URL shape,
  but the owner's own board would then depend on the LAN address it's on, and plain HTTP on
  loopback never crosses a network and is a secure context already. Recommended: keep the
  exception. Iain rules on it in this PR. (this PR)
- **TLS with no way out, off loopback.** A fully local board would pay the per-device trust step
  for nothing. Iain's reasoning: in a space that's already secure, things should just connect.
  Rejected for the `--unencrypted` opt-out, which keeps TLS the default and labels the board.
  (Iain, 2026-11-09, [this PR](https://github.com/tractat-us/bee/pull/163#issuecomment-6755901421))
- **Unencrypted as the default, with TLS the opt-in.** It's the zero-step path, but a board would be
  readable on the wire unless its owner thought to ask, and "get entirely off http" stands.
  (Iain, 2026-11-09, this PR)
- **`--insecure` as the flag's name.** curl, wget and git use it to mean "encrypt, but don't check
  the certificate", and this mode doesn't encrypt at all. `--unencrypted` says what it does, in
  the word the page shows. (this PR)
- **An unencrypted opt-in on the joining side too, such as `bee-join --unencrypted`.** Each
  joiner would take a step, which is what the opt-out exists to remove. The link's `http://`
  scheme already says which board it is, and only a host started with the flag prints one. (this PR)
- **An unlabelled unencrypted board.** Whoever joins can't tell their board is readable on the
  wire. The label is a word in the status the bar already has. (Iain, 2026-11-09, this PR)
- **A plain-HTTP bootstrap page that serves only the CA.** It keeps an HTTP listener beyond
  loopback on a TLS board, which invariant 3 forbids, and it sends the join key in clear. It's no safer than the
  browser's warning: on first contact, an attacker could swap the CA either way. (this PR)
- **A self-signed leaf, with no CA.** Its names change with the laptop's address, and each change
  would cost every device a fresh trust step. (this PR)
- **kuilt-nw's TLS-PSK for the app and the bridge.** Browsers can't speak it, the JDK has no
  external PSK, and Network.framework does it over TLS 1.2 alone (tractat-us/kuilt#1560). (this PR)
- **mkcert on the host.** The same per-device step as the host CA, plus a tool to install. (this PR)

## Open

- **Can a browser skip the trust step, through WebTransport?** A page from a publicly trusted
  origin (bee's docs site) can open a WebTransport session to a host whose self-signed certificate
  it pins by hash (`serverCertificateHashes`), so a browser would pin the way the bridge does.
  Costs: Chrome's local-network permission prompt, unconfirmed Safari support for the option,
  certificates of 14 days at most, a WebTransport fabric kuilt doesn't have, and an HTTP/3 server
  Ktor doesn't ship. Recommendation: a spike after slice 3, since it's the only route found that
  gives browsers no step with a self-minted certificate. [#73](https://github.com/tractat-us/bee/issues/73)
- **Keep phone browsers after the app ships?** Recommendation: keep them for a person who won't
  install an app, at the cost in the table, and make the QR open the app when it's installed.
  [#156](https://github.com/tractat-us/bee/issues/156)
