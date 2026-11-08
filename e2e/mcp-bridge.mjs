// A real bee-mcp bridge, the one a Claude session runs, driven over its stdio MCP transport
// (newline-delimited JSON-RPC). It is started from `./gradlew :bee-mcp:installDist`, joined to the
// host Playwright started, and stands in for an agent on the board.
import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const BRIDGE = join(dirname(fileURLToPath(import.meta.url)), '..', 'bee-mcp', 'build', 'install', 'bee-mcp', 'bin', 'bee-mcp');

/**
 * Starts a bridge named `session` on the host at `port`, and returns once it has answered
 * `initialize`. `call(tool, args)` calls a tool and returns its JSON answer, throwing on a tool
 * error; `kill(signal)` ends the bridge.
 */
export async function startBridge(port, session, bridgePath = BRIDGE) {
  const child = spawn(bridgePath, [], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${port}`, BEE_SESSION_NAME: session },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const stderr = [];
  child.stderr.on('data', (chunk) => stderr.push(chunk));
  const pending = new Map();
  let buffered = '';
  child.stdout.on('data', (chunk) => {
    buffered += chunk;
    let newline;
    while ((newline = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, newline).trim();
      buffered = buffered.slice(newline + 1);
      if (!line) continue;
      const message = JSON.parse(line);
      const waiter = message.id != null && pending.get(message.id);
      if (waiter) {
        pending.delete(message.id);
        waiter(message);
      }
    }
  });
  const exited = new Promise((resolve) => child.on('exit', resolve));
  let nextId = 1;
  const send = (message) => child.stdin.write(`${JSON.stringify(message)}\n`);
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`bridge ${session}: no answer to ${method} in 30 s\n${Buffer.concat(stderr).toString()}`));
    }, 30_000);
    pending.set(id, (message) => {
      clearTimeout(timer);
      if (message.error) reject(new Error(`bridge ${session}: ${method} failed: ${JSON.stringify(message.error)}`));
      else resolve(message.result);
    });
    send({ jsonrpc: '2.0', id, method, params });
  });

  await request('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'bee-e2e', version: '0' },
  });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });

  return {
    async call(name, args = {}) {
      const result = await request('tools/call', { name, arguments: args });
      const text = result.content?.map((c) => c.text).join('') ?? '';
      if (result.isError) throw new Error(`bridge ${session}: ${name} refused: ${text}`);
      return text ? JSON.parse(text) : null;
    },
    async kill(signal = 'SIGTERM') {
      if (child.exitCode == null && child.signalCode == null) child.kill(signal);
      await exited;
    },
  };
}
