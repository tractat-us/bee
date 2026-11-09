// A Claude session's stand-in for the e2e suite: the real bee-mcp bridge, from
// `./gradlew :bee-mcp:installDist`, driven over its stdio MCP transport (newline-delimited
// JSON-RPC) the way Claude Code drives it. It joins the host Playwright started.
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const BRIDGE = join(dirname(fileURLToPath(import.meta.url)), '..', 'bee-mcp', 'build', 'install', 'bee-mcp', 'bin', 'bee-mcp');

/** How long a request waits for its answer: longer than `bee_wait_action`'s 60 s cap. */
const REQUEST_TIMEOUT_MS = 75_000;

/** How many of the bridge's last stderr lines a failure quotes. */
const STDERR_LINES = 40;

/**
 * Starts a bridge named `name` against the e2e host and completes the MCP handshake. Resolves to
 * `{ identity, call(tool, args), kill() }` once the bridge has said who it is: `identity` is
 * its peer identity (`agent:<name>#<hex>`), and `call` resolves to a tool's parsed JSON answer.
 *
 * A request with no answer in REQUEST_TIMEOUT_MS fails, as does one in flight when the bridge
 * exits on its own; each failure quotes the bridge's last stderr lines. A request in flight when
 * `kill` ends the bridge is dropped: the test chose to end it.
 *
 * It joins the shared host unless `port` names a spec's own (host.mjs). `server` runs the bridge
 * as an MCP config's `mcpServers.bee` entry says instead (`{ command, args, env }`, as bee-up's
 * mcp.json writes it): that entry's command, and its env over this one's, `BEE_HOST` and
 * `BEE_SESSION_NAME` included.
 */
export async function startAgent(name, { port = process.env.BEE_E2E_PORT, server } = {}) {
  const child = spawn(server?.command ?? BRIDGE, server?.args ?? [], {
    env: {
      ...process.env,
      BEE_HOST: `ws://127.0.0.1:${port}`,
      BEE_SESSION_NAME: name,
      ...server?.env,
      // Its own run dir: under a Claude session the bridge would otherwise overwrite, then delete,
      // the developer's real ~/.bee/run/<claudePid>.json.
      BEE_RUN_DIR: mkdtempSync(join(tmpdir(), 'bee-e2e-run-')),
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const stderr = [];
  const said = () => stderr.join('\n');
  const pending = new Map();
  let killed = false;
  let next = 1;
  const settle = (id) => {
    const waiting = pending.get(id);
    if (!waiting) return null;
    pending.delete(id);
    clearTimeout(waiting.timer);
    return waiting;
  };
  createInterface({ input: child.stdout }).on('line', (line) => {
    const message = JSON.parse(line);
    const waiting = message.id !== undefined && settle(message.id);
    if (!waiting) return;
    if (message.error) waiting.reject(new Error(`${name}: ${JSON.stringify(message.error)}`));
    else waiting.resolve(message.result);
  });
  const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve({ code, signal })));
  child.on('exit', (code, signal) => {
    for (const [id, { method }] of [...pending]) {
      const waiting = settle(id);
      if (!killed) waiting.reject(new Error(`${name}'s bridge exited (${code ?? signal}) during ${method}\n${said()}`));
    }
  });
  const identity = new Promise((resolve, reject) => {
    createInterface({ input: child.stderr }).on('line', (line) => {
      stderr.push(line);
      if (stderr.length > STDERR_LINES) stderr.shift();
      const starts = line.match(/starts as (agent:\S+),/);
      if (starts) resolve(starts[1]);
    });
    child.on('exit', (code) => reject(new Error(`${name}'s bridge exited (${code}) before it said who it is\n${said()}`)));
  });
  // Awaited below once the handshake is done; a bridge that dies before then fails the handshake.
  identity.catch(() => {});
  const send = (message) => child.stdin.write(`${JSON.stringify(message)}\n`);
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = next++;
    const timer = setTimeout(() => {
      if (settle(id)) reject(new Error(`${name}: no answer to ${method} in ${REQUEST_TIMEOUT_MS / 1000} s\n${said()}`));
    }, REQUEST_TIMEOUT_MS);
    timer.unref();
    pending.set(id, { method, resolve, reject, timer });
    send({ jsonrpc: '2.0', id, method, params });
  });

  await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'bee-e2e', version: '0' } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  return {
    identity: await identity,
    async call(tool, args = {}) {
      const result = await request('tools/call', { name: tool, arguments: args });
      const text = result.content?.[0]?.text ?? '';
      if (result.isError) throw new Error(`${name}'s ${tool} was refused: ${text}`);
      return JSON.parse(text);
    },
    /**
     * Ends the bridge with SIGKILL and resolves once it has exited. That is a crash: no goodbye,
     * so the host departs it after the learner grace.
     */
    async kill() {
      killed = true;
      if (child.exitCode == null && child.signalCode == null) child.kill('SIGKILL');
      await exited;
    },
    /**
     * Ends the session without a crash and resolves, once the bridge has exited, to
     * `{ code, signal, ms }`: how it exited and how long after the first signal. `how` is:
     * - `stdin`: closes the stdio transport, so the bridge's MCP session ends.
     * - a signal name such as `SIGTERM`: sends just that signal.
     * - `claude-exit`: SIGINT, then SIGKILL 400 ms later if the bridge is still running. This
     *   copies public reports of Claude Code's `/exit`, not a measurement of it: SIGINT, SIGTERM
     *   about 100 ms later, SIGKILL about 400 ms after that. It skips the SIGTERM and kills at
     *   400 ms, a little sooner than the reported ~500 ms. A `signal` of `SIGKILL` means the
     *   bridge missed that deadline.
     * The bridge departs itself as left on each way out.
     */
    async end(how = 'stdin') {
      killed = true;
      const started = performance.now();
      let deadline;
      if (how === 'stdin') child.stdin.end();
      else if (how === 'claude-exit') {
        child.kill('SIGINT');
        deadline = setTimeout(() => {
          if (child.exitCode == null && child.signalCode == null) child.kill('SIGKILL');
        }, 400);
      } else child.kill(how);
      const { code, signal } = await exited;
      clearTimeout(deadline);
      return { code, signal, ms: Math.round(performance.now() - started) };
    },
    /** The bridge's last stderr lines, for a failure message. */
    said,
  };
}
