# Access

Truth for who may write to a board and who can take that away: the keys a host holds, how a
device gets one, and what a key lets its holder do. A rule here binds the host's gate, pairing,
invites, the MCP bridges and any page control that changes who is on the board.

Its invariants and current design are what holds on `main` today. They were first written for
[#173](https://github.com/tractat-us/bee/pull/173) and checked again against the code for this
seed. The kept HTTP sessions of [#159](https://github.com/tractat-us/bee/pull/159) are added.

## Invariants

1.  **A key admits only once the host has saved its hash, or holds it from memory, and today it
    stops admitting only at a boot.** The one exception is a device key whose save fails: it is
    logged and admits until the next boot. The join link's key is kept as it is, since the host prints
    the link on every start. A device's key, paired by code or given by an invite, is kept as its
    SHA-256. The hosted owner key and the host's own bridge key are held in memory and never
    saved. `--new-link` and `--forget-devices` are the only revokes, and `--forget-devices` forgets
    every invite too. Enforcer: `HostKeys`, whose constructor is private, so `boot` and `add` are
    the only ways a key gets in ([`HostKeys.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostKeys.kt),
    `HostKeysTest`; `InvitesTest`'s `forgettingDevicesForgetsInvitesToo`).
2.  **Every board, claims and MCP call passes the key gate before its handler runs.** It must come
    from loopback on a local or LAN board, or hold a key the host admits in its `?k=`. On a hosted
    board loopback grants nothing. Enforcer: `SocketGate`, installed by `mountEndpoints` on the
    route nodes kuilt's own `webSocket(path)` made, whose unconfigured rule refuses every call
    ([`SocketGate.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/SocketGate.kt),
    `LanKeyGateTest`).
3.  **Any admitted person may admit a device, and only a person (Iain, [#145](https://github.com/tractat-us/bee/issues/145)
    and spec [§5.7](../superpowers/specs/2026-11-06-bee-design.md#57-letting-a-second-screen-in-iain)).**
    By code, the person check reads the kind a record declares, so it stops a well-behaved agent
    only ([#64](https://github.com/tractat-us/bee/issues/64)). Enforcer: `Pairing.attempt`'s
    `Attempt.NotHuman` ([`Pairing.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Pairing.kt),
    `PairingTest`). By invite, on a hosted board, `POST /invite` takes any key the host admits and
    can't tell a person's page from an agent holding the same key. That agents don't mint rests on
    no bridge offering an invite tool: `review`.
4.  **The board carries no key.** A code a person types reaches the host inside an admit record,
    and the host checks it against its own memory. No key, hash or code is ever written to the
    board. Enforcer: `DeviceKey` and `KeyHash` live in bee-host, which bee-core, and so
    `BoardState`, can't depend on; `JoinRequestSurfaceTest` for the code.
5.  **An invite's secret is kept nowhere but the link.** The host keeps its SHA-256, the hash of
    the key that minted it, when it lapses and how many devices it admitted, in `invites` beside
    `keys` in the data dir. The secret appears only in the mint's answer, never in a log, a file or
    on the board, and a redemption's use is saved before its key is given. Its counts are
    [`product.md`](product.md) invariant 3. Enforcer: `Invites`, whose constructor is private, so
    `boot` is the only way invites load ([`Invites.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Invites.kt),
    `InvitesTest`); `HostedInviteTest`'s `theSecretAppearsInNoLogAndInNoAnswerButTheMint`.
6.  **An HTTP MCP session comes back from a host restart as itself only on a key that isn't the
    join link's, or on no key from the host's own machine, under a name someone chose (Iain, [#159](https://github.com/tractat-us/bee/pull/159)).**
    The join link's key never resumes, whatever the name, since everyone holding the link holds
    it. A paired device's key qualifies, and so does the hosted owner key, though every one of the
    owner's tabs and machines may hold it. Each kept identity goes to at most one session, and two kept sessions under one name and
    key go to neither. Enforcer: `HttpBridges.resumable` and `KeptIdentities`
    ([`KeptSessions.kt`](../../bee-mcp/src/main/kotlin/us/tractat/bee/mcp/KeptSessions.kt),
    `SessionNameTest`, `KeptIdentitiesTest`, `McpHttpRestartTest`'s
    `onlyAPairedDevicesOwnKeyComesBackAsItselfNeverTheJoinLinks`).

## Current design

**On a local or LAN board, three ways on.**

- **Loopback is the owner.** A call from the host's own machine needs no key, and only it gets the
  join link to hand out ([`InviteEndpoint.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/InviteEndpoint.kt)).
  Every process on that machine counts, agents included, since the host can't tell a person's
  browser from a local process (spec §5.7).
- **The join link's key admits whoever holds it.** Everyone who came by the link, and every bridge
  on another machine started with it, shares that one key.
- **A paired device has its own key.** A keyless device shows a six-digit code, a person on the
  board types it into the join request and presses Admit, and the host mints the key
  ([`JoinRequestSurface.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/JoinRequestSurface.kt)).
  The page keeps it in its tab's `sessionStorage`, so a new tab pairs again.

**On a hosted board, loopback grants nothing and the join link's key admits no one** (`JoinLink.Off`,
[`HostKeys.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostKeys.kt)). Three other ways on:

- **The owner key.** The host holds it from `BEE_OWNER_KEY`, in memory and never saved, and its
  holder opens the board with it ([`OwnerKey.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/OwnerKey.kt)).
- **An invite.** Any admitted page may mint one, `https://<public>/#i=<secret>`, which admits
  whoever opens it with nobody on the board to let them in
  ([`Invites.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Invites.kt), [#215](https://github.com/tractat-us/bee/issues/215)).
  The joiner types a name, and the page trades the secret for a device key of its own, added as a
  paired key is (`HostKeys.add`). Its limits are [`product.md`](product.md) invariant 3.
- **A code, while someone is there to admit it.** As on a LAN board, but a device asking while
  nobody who can admit it is attached is refused at once ([`product.md`](product.md) invariant 2).

**The way off is a restart.** `--forget-devices` cuts off every key at once, the join link's
included, and forgets every invite. `--new-link` cuts off the link alone, and keeps invites. The
owner key isn't in the file, so neither touches it. A live socket isn't closed by anything else.

**A Claude on HTTP holds the URL its person copied, and the key in it.** The page's "Bring your
Claude" line carries the tab's own key ([`BringYourClaude.kt`](../../bee-web/src/wasmJsMain/kotlin/us/tractat/bee/web/BringYourClaude.kt)),
so a Claude's key is its person's. The host runs a bridge per session, which dials the board on
loopback with the host's own `bridgeKey`. It keeps each resumable session's identities in
`http-sessions.json` under the SHA-256 of its `?k=` (invariant 6).

**The claims socket is a kuilt room; the board socket isn't.** Claims run on a `KtorRoomHost`,
which makes each accepted connection a two-peer room of its own, under a `serverCluster`
([`ClaimsVoter.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/ClaimsVoter.kt)). The board
is a `KtorConnectionSource` feeding a `hostedOverlay` hub ([`BoardRelay.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/BoardRelay.kt)),
which runs no admission of its own. Both sit behind the same key gate. On kuilt `0.7.0-dev.1995`
([#280](https://github.com/tractat-us/bee/pull/280)) each claims link proves a fresh in-memory
device key, the host's key is in memory too, and the room's policy is `AdmissionPolicy.open()`
with nothing revoked, so the gate alone decides. A page with no WebCrypto, a LAN page over plain
HTTP, opens no claims link ([#282](https://github.com/tractat-us/bee/issues/282)).

**Who is who is declared, not known.** A person is `human:<name>` from the page's `?name=`, an
agent's identity is minted by its bridge, and each peer posts its own presence to the board
([`Presence.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/Presence.kt)). The host
checks a key when a socket opens and never learns which identity a key carries. Nothing ties a
record's actor to the connection that wrote it (tractat-us/kuilt#3149).

## Future

### Device keys and passes

The first step of [#149](https://github.com/tractat-us/bee/issues/149), on kuilt's identity
library (tractat-us/kuilt#3159). It folds into [`setup.md`](../setup.md) and
[`deploy.md`](../deploy.md#getting-on-the-board) when it lands. It starts from bee on kuilt
`0.7.0-dev.1995`, where `KtorRoomHost` and `clusterClient` take a `DeviceKeyStore` and the claims
room takes a `HostAdmission` whose policy admits everyone the gate let through.

It lands in nine slices, in this order: [#290](https://github.com/tractat-us/bee/issues/290),
[#283](https://github.com/tractat-us/bee/issues/283), [#284](https://github.com/tractat-us/bee/issues/284), [#291](https://github.com/tractat-us/bee/issues/291),
[#285](https://github.com/tractat-us/bee/issues/285), [#286](https://github.com/tractat-us/bee/issues/286),
[#287](https://github.com/tractat-us/bee/issues/287), [#288](https://github.com/tractat-us/bee/issues/288)
and [#289](https://github.com/tractat-us/bee/issues/289). What kuilt lacks is
tractat-us/kuilt#3749 to tractat-us/kuilt#3752.

**The move.** Today a device's way back in is a secret it carries and shows, the key in its `?k=`.
After, it is a key it holds and never shows, a kuilt `DeviceKey`, and a **pass** the host signed
for that key, a kuilt `Grant`. A pass is useless without the key it names, so the host keeps no
secret of the device's at all: its own key, which signs every pass, and a `RevocationList`.

| bee today | after | why |
|---|---|---|
| A paired device's key, minted by `Pairing`, kept as a hash in `keys` | The device's own `DeviceKey` from `DeviceKeyStore.platform`, and one pass on it, issued when the code is admitted: scope `AnyRoomOfIssuer`, no expiry | A paired key admits until `--forget-devices` today, and so does a pass with no expiry. `AnyRoomOfIssuer` because `KtorRoomHost` mints a room per connection, so a `ThisRoom` pass never brings a device back |
| The pairing code and the join request surface | Stay bee's. The pairing request carries the device's `DidKey`; Admit answers a pass, not a key | Admission answers within its timeout; a person typing a code takes minutes. Who is trusted is the app's call (Decided 3 of [the kuilt identity spec](https://github.com/tractat-us/kuilt/blob/epic/3159/docs/superpowers/specs/2026-11-12-identity-key-bound-admission-design.md)) |
| An invite | Stays bee's: secret, counts and minter. Redeeming answers a pass for the redeemer's `DidKey`, and `mintedBy` becomes the minter's `DidKey` | Invite budgets are a kuilt non-goal |
| The owner key | Stays bee's ticket, from `BEE_OWNER_KEY`. The owner's page trades it once for a pass on its own key | It is how a hosted board's first device gets in |
| The join link's key | Stays bee's: a ticket the gate checks on every dial. Nothing is ever issued on it | Everyone holding the link holds it (below) |
| Loopback | Stays bee's: the gate admits a local caller, with no pass | It is a fact about the transport, not an identity |
| The host's in-memory `bridgeKey`, and `heldKeys` | Retire. The host's key is `DeviceKeyStore.platform("identity", dataDir)`: it signs every pass and is `KtorRoomHost`'s own | One key per device (Decided 5 of the kuilt identity spec) |
| `--forget-devices` | Moves `identity/` aside, so every pass is `UntrustedIssuer`, as well as what it does today | Losing the host key is the safe direction (kuilt-identity) |
| Revoking one device ([#164](https://github.com/tractat-us/bee/issues/164)) | `HostAdmission.revocations.revoke`, over `RevocationList.open` on `dataDir/revocations/` | One list, read by the gate and the claims room alike |
| The key in the "Bring your Claude" line | A **Claude token**: a 256-bit bearer the page mints for its Claude, by proving its own key. The host keeps its hash and the `DidKey` that minted it | Claude Code holds only a URL, and a page's key can't leave the page. Stays bee's until #149's OAuth |
| Kept HTTP sessions, keyed by the hash of `?k=` | `KeptIdentities` stays bee's, keyed by the Claude token's hash. The host holds a `DeviceKey` per token, and each bridge dials with that key, holding no pass: each claims link of its is a newcomer, and revoking its key evicts it | kuilt keeps a device's key and passes, never an app's identities. A key per token makes each Claude its own device in the claims room, so #164 can evict it |
| Who is who | Stays bee's. `Member.key` gives the host a proven key per claims link | Attested authors are tractat-us/kuilt#3149 |

**The gate.** Every board, claims and MCP call shows the gate exactly one of four things, and the
gate's verdict is a sealed `Admitted`, built only by the gate:

- `Local`: it came from loopback, on a board that isn't hosted.
- `JoinLink`: it holds the join link's key, on a LAN board.
- `Token`: it holds a bearer key the host admits that isn't the join link's: a Claude token, a
  legacy token, or the owner key. It admits every path, as such a key does today, since the gate
  can't tell a bridge's dial from a page's.
- `Keyed`: it proved a `DidKey`, and `GrantTrust(hostKey, revocations)` finds the pass it shows
  `Valid`.

A browser can't set a header on a WebSocket upgrade, so a proof rides in the query. The client
fetches a nonce, signs it with `signApp("bee/socket", nonce ‖ board ‖ path)`, and dials with its
`DidKey`, the nonce, the signature and its pass. The host spends each nonce once, within a minute.
Until kuilt ships this (tractat-us/kuilt#3749), bee builds it over `signApp` and `verifyApp`, behind a comment
naming the kuilt issue.

**The claims room.** A device back on a good `AnyRoomOfIssuer` pass is let in by kuilt without
the policy. A newcomer there (a loopback page, a device the join link let in, a bridge on a token)
is admitted by `BeePolicy` on `ThisRoom` terms. Each connection is its own room, so such a pass
dies with the connection. Once kuilt's admission request carries the link's principal (tractat-us/kuilt#3750),
the policy refuses a newcomer whose proven key isn't the one the gate saw.

**Bee#159's ruling, kept whole.** Iain: "only a session on a paired device's own key, or with no
key on the host's own machine, under a name someone chose. The join link's key never resumes,
whatever the name, since everyone holding the link holds it." It now reads:

- A pass is issued **per paired device**, to that device's own key, when a person admits its
  code, an invite is redeemed or the owner key is traded. Nothing else issues one that outlives
  its link.
- The join link is **never granted**. A device the link let in holds no pass and gets in on the
  link each time, so `--new-link` still cuts off everyone on it. It mints no Claude token, and its
  Claude's URL carries the link, as today.
- A session resumes on a `Token` or a `Local` verdict, under a chosen name, and never on
  `JoinLink`. A token is minted by one device, so one device holds it.

**Where each key lives.**

| Who | Its key | Notes |
|---|---|---|
| The page (bee-web) | `DeviceKeyStore.platform("bee")`: a WebCrypto key the page can't export, in IndexedDB, with its pass | Every tab of the origin is one device, so a new tab no longer pairs again. Needs a secure context: loopback and hosted boards have one, a LAN page needs [#73](https://github.com/tractat-us/bee/issues/73) first |
| A stdio bridge or the companion | `DeviceKeyStore.platform("bee", ~/.bee/device)`, one key per machine | Never under `~/.bee/host`, which is the host's. `BEE_HOST` carries a join link or a Claude token |
| An HTTP bridge | A key the host holds for its Claude token, in `dataDir/bridges/`. It holds no pass, since each claims link is a newcomer | The Claude itself holds the URL and nothing else |
| The host | `DeviceKeyStore.platform("identity", dataDir)` | Moves with `--data-dir`, as the board does |

**Migration, from the code.** New state goes in new files: `KeyFile.load` refuses a line it
doesn't know, so one written into `keys` would stop an older host from booting.

- `keys`: the `join` line stays. Each `device <hash>` line is a SHA-256 of a 256-bit bearer, the
  same shape as a Claude token, so it carries over as a **legacy token**: it still admits as a
  `Token`, and a page holding one trades it for a pass on its first load. Trading
  doesn't spend it, since the same key may sit in that person's Claude config. Until pairing
  answers a pass ([#289](https://github.com/tractat-us/bee/issues/289)), a LAN board keeps adding
  `device` lines, since a LAN page can't hold a key before #73.
- Pages keep their key in their tab's `sessionStorage`, so a closed tab holds nothing to migrate.
  An open tab trades. Anything else pairs again, as a new tab does today.
- `http-sessions.json`: `caller` is the SHA-256 of `?k=`, which is the legacy token's hash, so
  each entry matches with no rewrite. Loopback entries are unchanged.
- `invites`: `mintedBy` holds key hashes. An invite lapses within a day and is forgotten a day
  later, so a host reads both forms for two days and converts none.
- `BEE_OWNER_KEY` is unchanged. The host's own key is made on the first boot that needs it.
  The owner key stays a `Token`, so a Claude added with it in `?k=` ([`deploy.md`](../deploy.md))
  keeps working when `heldKeys` retires, and its kept sessions still resume.

**Typed rules this adds**, each landing with its slice:

- Only `Passes.issue(reason)` issues a pass that outlives its link. `PassReason` is sealed, with
  cases for an admitted code, a redemption, the owner key and a traded legacy key, and none for
  the join link or loopback.
- `BeePolicy` answers bee's own `NewcomerTerms`, which has no reach, so it can't issue an
  `AnyRoomOfIssuer` pass. Test: a loopback newcomer's pass is refused on a second connection.
- `HttpBridges.resumable` takes the gate's `Admitted` and its `when` is exhaustive, so a new way
  on can't resume until someone says whether it may. `JoinLink` returns false. `Admitted` lives in
  bee-mcp beside `PresentedKey`, since bee-host depends on bee-mcp. Kotlin can't stop another
  file building one, so [#284](https://github.com/tractat-us/bee/issues/284) adds a guard test
  that only `SocketGate` does.
- A Claude token never reaches the board: `ClaudeToken` lives in bee-host, as bee's bearer
  `DeviceKey` does (invariant 4), and `KeptIdentities` keys on its hash.

### Hosts and eviction

[#164](https://github.com/tractat-us/bee/issues/164). Folds into [`setup.md`](../setup.md) §
pairing when it lands, and the menu into [`product.md`](product.md).

**Roles live on keys, in the host's key file, and nowhere else.** A key's holder is one of:

| Holder | Who | Host? | Can be evicted or demoted? |
|---|---|---|---|
| **Root** | loopback on a local or LAN board; the owner key on a hosted one | always | no |
| **Device** | a paired device's key | if a host made it one | yes, by a host |
| **Join link** | the bearer link, on a LAN board | never | evicting it mints a new link |

A device line in the key file gains its role: `device <hash> host`. Root isn't in the file: it's
the host's own machine, or the owner key from the environment. A role is per key, never per name,
because a name is whatever `?name=` says.

**Why a client can't make itself host.** The board is a CRDT every replica writes, so nothing on it
is authority. The host never reads a role back from the board. The page learns who is a host from
an **access mirror**, a host-written list of host identities on the board, the same pattern as the
claims mirror. Forging it only shows a menu the host then refuses; the host rewrites the mirror
when another replica changes it.

**The host learns which key each identity came in on.** Every gated socket names the identity it
serves in `?as=`, and the gate records it against the socket's key: a live table of identity to
keys, kept in memory, with each person's names saved on their device line, so a device that's
offline is still found. A bridge redials its claims link under each new identity, so a `/clear`
declares the new one. An identity maps to every key it was declared on, and a key to every
identity declared on it: those are the agents that share a person's key. A socket can add only
its own key to an identity, so no one can aim an eviction at another person's key. The MCP
bridges the host runs for HTTP sessions dial with their session's key, so their sockets are
attributed like any other ([#159](https://github.com/tractat-us/bee/pull/159)'s `KeptSessions`
already keys sessions by key hash).

**A host's command is an HTTP call the gate checks, not a record on the board.** The page calls
`POST /access` with its key in `?k=` and `{"do": "host" | "unhost" | "evict", "peer": "<identity>"}`.
The site gate checks its `Origin` first, as on every call. The handler takes a `HostCaller`, a
value only the gate's role check can build, so a command with no proven host doesn't compile.
Root has no case in the commands' target type, so root can't be evicted or demoted, and the join
link has no case in `host`'s.

An eviction takes five steps, in this order.

1.  **Revoke.** `HostKeys.revoke` stops the key's hashes admitting at once, then saves the file. A
    save that fails is answered as an error: the eviction holds until the host stops, and the
    host says so.
2.  **Close live sockets.** The gate keeps each live gated call under its key's hash and ends
    them. kuilt can't close one principal's links (tractat-us/kuilt#3158), so until it can, bee
    cancels the Ktor call behind a comment naming that issue. The slice proves which handle closes
    a WebSocket.
3.  **End HTTP MCP sessions.** `HttpBridges` closes every session on that key.
4.  **Depart its agents.** The voter departs each agent identity declared on that key, with a new
    `DepartReason.Evicted`, so their later claims are refused; that reason is a wire change.
5.  **Clear the strip.** The host drops the person's presence entries and updates the mirror. The
    strip leaves out an `Evicted` departure, so the person and their agents leave it at once.

What they wrote stays on the board. Their page sees its socket drop and a reconnect refused, so it
goes offline and offers to pair again, as it does today for a forgotten key. The menu on a peer
who came by the join link says it mints a new link, since that cuts off everyone on it. A
revocation survives a restart, because only a saved hash admits (invariant 1).

**Make host** sets the role on every device key the identity was declared on, saves, and updates
the mirror. When a name is on more than one key, the menu says how many devices it covers. **Not
host** takes the same slot on a host's bubble and clears the role.

**Typed rules this adds**, each landing with its slice:

- A role is read only from the key file: the role type is `internal` to bee-host, so `BoardState`
  can't hold one.
- A command needs a proven host: `HostCaller`'s constructor is `internal` to the gate.
- Root can't be removed: the command target is a sealed type with no root case.
- A revoke closes live sockets within one round trip and survives a restart: a test that evicts,
  checks the socket closes and the reconnect is refused, restarts, and checks again.
- Agents never hold the menu: the bridge exposes no access tool. That's `review`, since a peer's
  kind is self-declared and no new gate may rest on it (spec §5.7).

## Settled — don't reopen

- **Granting the join link: issuing a pass to one key every holder of the link shares.** Everyone
  holding the link would hold the pass, and `--new-link` could no longer cut them off.
  (Iain, [#159](https://github.com/tractat-us/bee/pull/159); the kuilt identity spec's Decided 4)
- **The board on a kuilt room, so `HostAdmission` decides the board socket too.** The board is a
  `hostedOverlay` hub, and each `KtorRoomHost` connection is a two-peer room. Rewriting the relay
  costs far more than a proof at the gate. Reopen when kuilt's hub links can prove a key,
  tractat-us/kuilt#3751. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **Pairing by code inside the room's admission.** `AdmissionPolicy.decide` must answer within
  the host's admit timeout, and a person typing a code takes minutes. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **The pairing code, invite secret or join link carried in the room's Hello.** The Hello's only
  free fields are the display name and device id. Both are printed by `AdmissionRequest.toString`,
  so a secret there leaks into logs. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **A Claude on HTTP that shares its person's page key.** The page's key can't leave the page, and
  a key the host holds for a page would be a bearer secret the page shows. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **One host-held key shared by every HTTP bridge, today's `bridgeKey`.** Every Claude would show
  the host's own key in the claims room, so [#164](https://github.com/tractat-us/bee/issues/164)
  couldn't tell one person's Claude from another's. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **Passes that run out and are renewed while connected.** A screen opens no claims link, so its
  pass would never be renewed and its screen would stop. Today a paired key never runs out. ([#281](https://github.com/tractat-us/bee/pull/281), 2026-11-26)
- **Pairing a device the join link let in, on its own key, in this migration.** It would come
  back as itself, and its Claude too, but `--new-link` would no longer cut it off. Ask again with
  [#149](https://github.com/tractat-us/bee/issues/149). (Iain, "#281 seeds; file the work",
  2026-11-26)
- **Pairing's pass before [#73](https://github.com/tractat-us/bee/issues/73).** A LAN page over
  plain HTTP has no WebCrypto, so it can't hold a key. Pairing's pass lands after #73
  ([#289](https://github.com/tractat-us/bee/issues/289)), and LAN keeps minting bearer keys until
  then, so there is one path at a time. (Iain, 2026-11-26)
- **A claims link from a LAN page before #73.** On kuilt `0.7.0-dev.1995`, `clusterClient` needs a
  `DeviceKeyStore`, and a page with no WebCrypto can't make even an in-memory key. Such a page
  opens no claims link, as a screen opens none, so it can't answer an orphan decision meanwhile
  ([#282](https://github.com/tractat-us/bee/issues/282)). (Iain, "Accept, track in issue", on
  [#280](https://github.com/tractat-us/bee/pull/280), 2026-11-26)
- **A clock on legacy tokens.** They stop at the next `--forget-devices`, and at no set time
  ([#288](https://github.com/tractat-us/bee/issues/288)). (Iain, 2026-11-26)
- **Closing the disguised writer in this migration.** An admitted writer can declare one identity
  and show another, since presence is self-declared. It waits on attested authors,
  tractat-us/kuilt#3149, and `--forget-devices` is the backstop. (Iain, 2026-11-26)
- **A host's command as an action record on the board, the shape Admit uses.** Admit works as a
  record because any writer may admit, so a forged one gains nothing. Make host and Evict are
  privileged, and a record's actor is declared, not attested. Reopen when tractat-us/kuilt#3149
  gives records an attested author. (#164, this PR)
- **A role written to the board.** Any replica writes the board, so any writer could make itself a
  host. The board carries a mirror for display only. (#164, this PR)
- **A role held by name.** Anyone who opens the page with that `?name=` would hold it. (#164, this PR)
- **"The last host can't be removed."** The root rule covers it, and it also stops a host someone
  made from evicting the board's owner. (#164, this PR)
- **Finding a bubble's key through its presence entry's claims node.** Presence is self-declared,
  so a forged entry could aim an eviction at someone else's key. (#164, this PR)
- **Refusing a revoked key's new sockets but leaving its live ones.** An evicted page would keep
  writing until it dropped. (#164, this PR)
- **A menu on an agent's bubble.** An agent shares its person's key, so evicting it would evict the
  person; agents leave with the key. (#164, this PR)

## Open

- **Narrowing a `Token` off the board.** A Claude token admits every path, as its person's key
  does today. Recommendation: keep it so until the gate can tell a bridge's dial from a page's;
  ask again with [#149](https://github.com/tractat-us/bee/issues/149)'s OAuth.
- **When the owner key stops admitting `/mcp`.** It stays a `Token`, so the owner's Claude keeps
  working through the migration. Recommendation: not in this migration; once the owner's page
  can mint a Claude token ([#287](https://github.com/tractat-us/bee/issues/287)),
  [`deploy.md`](../deploy.md) tells the owner to use one instead.
- **Re-admission.** Any admitted person may admit, so an evicted person can pair again and be let
  back in by anyone. Keep that (Iain's ruling on [#145](https://github.com/tractat-us/bee/issues/145)), or need a host to admit someone who was
  evicted? [#164](https://github.com/tractat-us/bee/issues/164)
- **A disguised writer.** Eviction finds a peer that stays itself. An admitted writer that declares
  one identity on its sockets and shows another on the board hides from the menu, since presence is
  self-declared. The fix is attested authors, tractat-us/kuilt#3149, and identity,
  [#149](https://github.com/tractat-us/bee/issues/149); until then `--forget-devices` is the backstop.
- **Should the strip mark hosts?** Recommendation: no, under [`product.md`](product.md)
  invariant 1; nobody has named the need. [#164](https://github.com/tractat-us/bee/issues/164)
