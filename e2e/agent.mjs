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

/**
 * Starts a bridge named `name` against the e2e host and completes the MCP handshake. Resolves to
 * `{ identity, call(tool, args), kill() }` once the bridge has said who it is: `identity` is its
 * peer identity (`agent:<name>#<hex>`), and `call` resolves to a tool's parsed JSON answer.
 */
export async function startAgent(name) {
  const child = spawn(BRIDGE, [], {
    env: {
      ...process.env,
      BEE_HOST: `ws://127.0.0.1:${process.env.BEE_E2E_PORT}`,
      BEE_SESSION_NAME: name,
      // Its own run dir: under a Claude session the bridge would otherwise overwrite, then delete,
      // the developer's real ~/.bee/run/<claudePid>.json.
      BEE_RUN_DIR: mkdtempSync(join(tmpdir(), 'bee-e2e-run-')),
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const pending = new Map();
  let next = 1;
  createInterface({ input: child.stdout }).on('line', (line) => {
    const message = JSON.parse(line);
    const waiting = message.id !== undefined && pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(`${name}: ${JSON.stringify(message.error)}`));
    else waiting.resolve(message.result);
  });
  const identity = new Promise((resolve, reject) => {
    createInterface({ input: child.stderr }).on('line', (line) => {
      const said = line.match(/starts as (agent:\S+),/);
      if (said) resolve(said[1]);
    });
    child.on('exit', (code) => reject(new Error(`${name}'s bridge exited (${code}) before it said who it is`)));
  });
  const send = (message) => child.stdin.write(`${JSON.stringify(message)}\n`);
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = next++;
    pending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params });
  });

  await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'bee-e2e', version: '0' } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  return {
    identity: await identity,
    async call(tool, args) {
      const result = await request('tools/call', { name: tool, arguments: args });
      const text = result.content?.[0]?.text ?? '';
      if (result.isError) throw new Error(`${name}'s ${tool} was refused: ${text}`);
      return JSON.parse(text);
    },
    /** Ends the bridge as a crash does: no goodbye, so the host departs it after the learner grace. */
    kill() {
      child.kill('SIGKILL');
    },
  };
}
