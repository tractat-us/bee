# Access

Truth for who may write to a board and who can take that away: the keys a host holds, how a
device gets one, and what a key lets its holder do. A rule here binds the host's gate, pairing,
the MCP bridges and any page control that changes who is on the board.

## Invariants

1.  **A key admits only once the host has saved its hash, and today it stops admitting only at a
    boot.** The join link's key is kept as it is, since the host prints the link on every start;
    a paired device's key is kept as its SHA-256. `--new-link` and `--forget-devices` are the only
    revokes. Enforcer: `HostKeys`, whose constructor is private, so `boot` and `add` are the only
    ways a key gets in ([`HostKeys.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/HostKeys.kt),
    `HostKeysTest`).
2.  **Every board, claims and MCP call passes the key gate before its handler runs.** It must come
    from loopback, or hold a key the host keeps in its `?k=`. Enforcer: `SocketGate`, installed by
    `mountEndpoints` on the route nodes kuilt's own `webSocket(path)` made, whose unconfigured
    rule refuses every call ([`SocketGate.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/SocketGate.kt),
    `LanKeyGateTest`).
3.  **Any admitted person may admit a device, and only a person (Iain, [#145](https://github.com/tractat-us/bee/issues/145)
    and spec [§5.7](../superpowers/specs/2026-11-06-bee-design.md#57-letting-a-second-screen-in-iain)).**
    The person check reads the kind a record declares, so it stops a well-behaved agent only
    ([#64](https://github.com/tractat-us/bee/issues/64)). Enforcer: `Pairing.attempt`'s
    `Attempt.NotHuman` ([`Pairing.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/Pairing.kt),
    `PairingTest`).
4.  **The board carries no key.** A code a person types reaches the host inside an admit record,
    and the host checks it against its own memory; no key, hash or code is ever written to the
    board. Enforcer: `DeviceKey` and `KeyHash` live in bee-host, which bee-core, and so
    `BoardState`, can't depend on; `JoinRequestSurfaceTest` for the code.

## Current design

**Three ways onto a board, and one way off.**

- **Loopback is the owner.** A call from the host's own machine needs no key, and only it gets the
  join link to hand out ([`InviteEndpoint.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/InviteEndpoint.kt)).
  Every process on that machine counts, agents included, since the host can't tell a person's
  browser from a local process (spec §5.7).
- **The join link's key admits whoever holds it.** Everyone who came by the link, and every bridge
  on another machine started with it, shares that one key.
- **A paired device has its own key.** A keyless device shows a six-digit code, a person on the
  board types it into the join request and presses Admit, and the host mints the key
  ([`JoinRequestSurface.kt`](../../bee-host/src/main/kotlin/us/tractat/bee/host/JoinRequestSurface.kt)).
- **The way off is a restart.** `--forget-devices` cuts off every key at once, the join link's
  included; `--new-link` cuts off the link alone. A live socket isn't closed by anything else.

**Who is who is declared, not known.** A person is `human:<name>` from the page's `?name=`, an
agent's identity is minted by its bridge, and each peer posts its own presence to the board
([`Presence.kt`](../../bee-core/src/commonMain/kotlin/us/tractat/bee/core/Presence.kt)). The host
checks a key when a socket opens and never learns which identity a key carries. Nothing ties a
record's actor to the connection that wrote it (tractat-us/kuilt#3149).

## Future

### Hosted admission

[#145](https://github.com/tractat-us/bee/issues/145). On a hosted board loopback grants nothing,
the join link admits nothing, and the **owner key** from `BEE_OWNER_KEY` admits like a device key.
Its design is #145's; this doc takes only that the owner key's holder is the hosted board's root
host, below.

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

**Evict, in order:**

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

- **Re-admission.** Any admitted person may admit, so an evicted person can pair again and be let
  back in by anyone. Keep that (Iain's #145 ruling), or need a host to admit someone who was
  evicted? [#164](https://github.com/tractat-us/bee/issues/164)
- **A disguised writer.** Eviction finds a peer that stays itself. An admitted writer that declares
  one identity on its sockets and shows another on the board hides from the menu, since presence is
  self-declared. The fix is attested authors, tractat-us/kuilt#3149, and identity,
  [#149](https://github.com/tractat-us/bee/issues/149); until then `--forget-devices` is the backstop.
- **Should the strip mark hosts?** Recommendation: no, under [`product.md`](product.md)
  invariant 1; nobody has named the need. [#164](https://github.com/tractat-us/bee/issues/164)
