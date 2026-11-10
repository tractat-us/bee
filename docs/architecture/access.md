# Access

Truth for who may write to a board and who can take that away: the keys a host holds, how a
device gets one, and what a key lets its holder do. A rule here binds the host's gate, pairing,
the MCP bridges and any page control that changes who is on the board.

## Invariants

1.  **A key admits only once the host has saved its hash, or holds it from memory, and today it
    stops admitting only at a boot.** The join link's key is kept as it is, since the host prints
    the link on every start; a device's key, paired by code or given by an invite, is kept as its
    SHA-256. The hosted owner key and the host's own bridge key are held in memory and never
    saved. `--new-link` and `--forget-devices` are the only revokes, and `--forget-devices` forgets
    every invite too. Enforcer: `HostKeys`, whose constructor is private, so `boot` and `add` are
    the only ways a key gets in ([`HostKeys.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostKeys.kt),
    `HostKeysTest`; `InvitesTest`'s `forgettingDevicesForgetsInvitesToo`).
2.  **Every board, claims and MCP call passes the key gate before its handler runs.** It must come
    from loopback on a local or LAN board, or hold a key the host admits in its `?k=`; on a hosted
    board loopback grants nothing. Enforcer: `SocketGate`, installed by
    `mountEndpoints` on the route nodes kuilt's own `webSocket(path)` made, whose unconfigured
    rule refuses every call ([`SocketGate.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/SocketGate.kt),
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
    and the host checks it against its own memory; no key, hash or code is ever written to the
    board. Enforcer: `DeviceKey` and `KeyHash` live in bee-host, which bee-core, and so
    `BoardState`, can't depend on; `JoinRequestSurfaceTest` for the code.
5.  **An invite's secret is kept nowhere but the link.** The host keeps its SHA-256, the hash of
    the key that minted it, when it lapses and how many devices it admitted, in `invites` beside
    `keys` in the data dir. The secret appears only in the mint's answer, never in a log, a file or
    on the board, and a redemption's use is saved before its key is given. Its counts are
    [`product.md`](product.md) invariant 3. Enforcer: `Invites`, whose constructor is private, so
    `boot` is the only way invites load ([`Invites.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Invites.kt),
    `InvitesTest`); `HostedInviteTest`'s `theSecretAppearsInNoLogAndInNoAnswerButTheMint`.

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

**On a hosted board, loopback grants nothing and the join link's key admits no one** (`JoinLink.Off`,
[`HostKeys.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostKeys.kt)). Three other ways on:

- **The owner key.** The host holds it from `BEE_OWNER_KEY`, in memory and never saved, and its
  holder opens the board with it ([`OwnerKey.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/OwnerKey.kt)).
- **An invite.** Any admitted page may mint one: `https://<public>/#i=<secret>`, which admits
  whoever opens it, with nobody on the board to let them in
  ([`Invites.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Invites.kt), [#215](https://github.com/tractat-us/bee/issues/215)).
  It lasts 24 hours from minting and admits at most 25 devices; one key has at most 10 live, the
  board 100, and the board admits at most 100 devices a day by invite, a count kept in memory
  that starts again at a restart. The joiner types a name, and the page trades the secret for a
  device key of its own, added as a paired key is (`HostKeys.add`), so each redemption is a
  device of its own. A tab reuses its invite while it has more than an hour left. Lapsed or
  used-up invites are remembered a day longer, so their page hears why.
- **A code, while someone is there to admit it.** As on a LAN board, but a device asking while
  nobody who can admit it is attached is refused at once ([`product.md`](product.md) invariant 2).

A guess at an invite costs the host nothing: a secret is looked up by its digest, an unknown one
leaves nothing behind, and there is no per-address limit. The 256-bit secret is the defence.

**The way off is a restart.** `--forget-devices` cuts off every key at once, the join link's
included, and forgets every invite; `--new-link` cuts off the link alone, and keeps invites. The
owner key isn't in the file, so neither touches it. A live socket isn't closed by anything else.

**Who is who is declared, not known.** A person is `human:<name>` from the page's `?name=`, an
agent's identity is minted by its bridge, and each peer posts its own presence to the board
([`Presence.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/Presence.kt)). The host
checks a key when a socket opens and never learns which identity a key carries. Nothing ties a
record's actor to the connection that wrote it (tractat-us/kuilt#3149).

## Future

### Hosts and eviction

[#164](https://github.com/tractat-us/bee/issues/164). Folds into [`setup.md` § Cutting a device off](../setup.md#cutting-a-device-off) when it lands,
and the menu into [`product.md`](product.md).

**Roles live on keys, in the host's key file, and nowhere else.** A key's holder is one of:

| Holder | Who | Host? | Can be evicted or demoted? |
|---|---|---|---|
| **Root** | loopback on a local or LAN board; the owner key on a hosted one | always | no |
| **Device** | a device's own key, paired by code or given by an invite | if a host made it one | yes, by a host |
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

1.  **Revoke, and void its invites.** `HostKeys.revoke` stops the key's hashes admitting at once.
    Under the invites' own lock, so no redemption slips between, `Invites` voids every live invite
    those keys minted and the invite each of them came in by. Then both files are saved. A save
    that fails is answered as an error: the eviction holds until the host stops, and the host
    says so.
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

**An evicted person can't redeem a link they still hold (Iain, 2026-11-09).** Evicting a key voids
every live invite that key minted, which `StoredInvite.mintedBy` already records, and the invite
the key came in by. For that, an invite records the hash of each key it gave, at most 25, written
in the same save as the use; once the invite is forgotten there is nothing left to void, so the
record never outlives the link. A voided invite admits no one from then on, and a page that opens
it hears it lapsed, so no new refusal crosses the wire. Everyone already in by it keeps their own
key. Two ways back stay open, under invariant 3: a link someone else minted that the person never
used, and a person on the board admitting them again by code or a fresh invite.

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
- An evicted person can't redeem a link they hold: a test, not yet built, that evicts a key which
  came in by one invite and minted another, then checks both secrets are refused while an invite
  another key minted still admits.
- Agents never hold the menu: the bridge exposes no access tool. That's `review`, since a peer's
  kind is self-declared and no new gate may rest on it (spec §5.7).

## Settled — don't reopen

- **A host's command as an action record on the board, the shape Admit uses.** Admit works as a
  record because any writer may admit, so a forged one gains nothing. Make host and Evict are
  privileged, and a record's actor is declared, not attested. Reopen when tractat-us/kuilt#3149
  gives records an attested author. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **A role written to the board.** Any replica writes the board, so any writer could make itself a
  host. The board carries a mirror for display only. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **A role held by name.** Anyone who opens the page with that `?name=` would hold it. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **"The last host can't be removed."** The root rule covers it, and it also stops a host someone
  made from evicting the board's owner. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **Finding a bubble's key through its presence entry's claims node.** Presence is self-declared,
  so a forged entry could aim an eviction at someone else's key. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **Refusing a revoked key's new sockets but leaving its live ones.** An evicted page would keep
  writing until it dropped. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **A menu on an agent's bubble.** An agent shares its person's key, so evicting it would evict the
  person; agents leave with the key. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-08)
- **A hosted board with no link that admits: the 2026-11-08 ruling that a hosted join link admits
  no one.** Every newcomer showed a code for someone on the board to type in, and the owner wanted
  a link to hand someone that lets them in. The bearer join key stays off; an invite is that
  link, and [`product.md`](product.md) invariant 3 holds its rule. ([#215](https://github.com/tractat-us/bee/issues/215), 2026-11-09)
- **An eviction that leaves the evicted person's invites live.** They could redeem a link they
  still hold for a new key until it lapsed or ran out. (Iain, [#173](https://github.com/tractat-us/bee/pull/173#issuecomment-6755901751), 2026-11-09)
- **Finding the invite a key came in by from its device line.** The line would keep that link for
  as long as the key lives, long after the invite stops admitting. The invite records the keys it
  gave instead, and the record goes when the invite is forgotten. ([#173](https://github.com/tractat-us/bee/pull/173), 2026-11-09)

## Open

- **A disguised writer.** Eviction finds a peer that stays itself. An admitted writer that declares
  one identity on its sockets and shows another on the board hides from the menu, since presence is
  self-declared. The fix is attested authors, tractat-us/kuilt#3149, and identity,
  [#149](https://github.com/tractat-us/bee/issues/149); until then `--forget-devices` is the backstop.
- **Should the strip mark hosts?** Recommendation: no, under [`product.md`](product.md)
  invariant 1; nobody has named the need. [#164](https://github.com/tractat-us/bee/issues/164)
