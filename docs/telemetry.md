# Seeing what bee did

Something went wrong in a session, and you want to know what happened. A surface didn't show up,
a phone dropped off, or a Claude session's tool call failed. The host and every bridge keep a
record of what they did, on your machine, so you can read it back afterwards.

## Read the record

From a bee checkout or an unzipped kit:

```sh
bin/bee-telemetry --since 10m
```

That prints every process's record as one timeline, oldest first: the host's and each bridge's,
one line per log line, event or tool call.

```
20:26:16.702  bridge agent:iain-claude#0528e6aa  event  socket.connected  url.path=/ws/board
20:26:16.711  host   host                        event  presence.attach  bee.subject=agent:iain-claude#0528e6aa
20:26:19.705  bridge agent:iain-claude#0528e6aa  span   mcp.tool (12 ms)  bee.surface=lunch  bee.tool=bee_surface_open  bee.outcome=ok
20:26:19.725  host   host                        event  surface.open  bee.surface=lunch  bee.opened.by=agent:iain-claude#0528e6aa
```

Narrow it down with `--role host` (or `bridge`), `--peer <name>` (part of a peer's name, or a
bridge's session name) and `--tail <n>`. `--json` prints one JSON object per line, for `jq`.
`bee-telemetry` reads `$BEE_HOME`, or `~/.bee` without it, the same as `bee-up`. It only reads: it
copies each record before it opens it, so a running host or bridge is never disturbed.

## What is in it

- **Every log line** the host and the bridges write: what `host.log` shows, and the lines a bridge
  writes to Claude Code's MCP log.
- **What the host saw happen on the board**: a surface opened, updated, closed or reopened, a
  button pressed, a peer attaching and departing (and why), a claim committed, a socket dialling,
  joining, leaving or being refused.
- **What each bridge did**: every tool call, with how long it took and how it ended, and its link
  to the host connecting, dropping and failing to dial.

Each line names the process that wrote it: its role, its peer, the board and the bee commit.

What it never holds: a key (it scrubs every `?k=` and anything shaped like one), what a surface
says, or a tool call's arguments beyond the surface, record or claim it names.

## Where it lives, and how much

| Process | Its record |
|---|---|
| The host | `<data dir>/telemetry/`, which is `$BEE_HOME/host/telemetry/` under `bee-up` |
| Each stdio bridge | `$BEE_HOME/telemetry/bridge-<session>/` |
| A Claude joined over `/mcp` | inside the host's record, its tool calls marked as its bridge's |

Each record keeps its newest 10,000 log lines and 1,000 tool calls, and drops the oldest first. A
failure to write never stops the host or a bridge: telemetry logs it once and carries on.

To turn it off, start the host or the bridges with `BEE_TELEMETRY=off`. `bee-up` and `bee-join`
pass that setting to the bridges they configure.

## Look at it in Grafana

To browse a session's tool calls as traces and filter its lines by peer, send the record to an
OpenTelemetry collector. `bee-otel-viewer` runs one locally, in Docker, with Grafana behind it:

```sh
bin/bee-otel-viewer
BEE_OTLP_ENDPOINT=http://127.0.0.1:4318 bin/bee-up
```

Then open `http://127.0.0.1:3000`, go to **Explore**, and pick **Loki** for log lines and events,
or **Tempo** for tool calls. Each host and bridge shows as its own service, `bee-host` or
`bee-mcp`, labelled with its peer. `bin/bee-otel-viewer --stop` stops it.

`BEE_OTLP_ENDPOINT` takes any OTLP/HTTP collector, and `BEE_OTLP_PROTOCOL=protobuf` switches the
encoding from JSON. Sending is off unless it is set. A collector that is down costs nothing: the
local record stands whatever happens to the send. bee offers the collector what it hasn't had
every 10 seconds, and once more as a process ends.

### Why the viewer has a collector in front

kuilt's OTLP sender doesn't yet name the process a record came from ([kuilt#3153](https://github.com/tractat-us/kuilt/issues/3153)),
so bee stamps every record with it instead. The collector in `otel/collector.yaml` lifts those
attributes back to where Grafana looks for them. A collector of your own needs the same
`groupbyattrs` step, or every record shows as one unknown service.
