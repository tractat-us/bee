// A bee-host of a spec's own, beside the shared one Playwright starts: from the same installDist,
// on its own port and data dir, started and stopped as a process. For a spec that stops the host
// (durability) or needs a board no other spec has seeded (empty-board).
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

/** A port free on loopback now. The host binds it a moment later. */
export async function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** A bee-host on [port] with its data in [dataDir], started and stopped as a process. */
export class Host {
  constructor(port, dataDir) {
    this.port = port;
    this.dataDir = dataDir;
    this.url = `http://127.0.0.1:${port}`;
    this.process = null;
    this.log = [];
  }

  async start() {
    const bin = join(repo, 'bee-host/build/install/bee-host/bin/bee-host');
    this.process = spawn(bin, ['--port', String(this.port), '--data-dir', this.dataDir], { stdio: 'pipe' });
    for (const stream of [this.process.stdout, this.process.stderr]) {
      stream.on('data', (chunk) => this.log.push(chunk.toString()));
    }
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
      try {
        if ((await fetch(`${this.url}/`)).ok) return;
      } catch {
        // Not listening yet.
      }
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    throw new Error(`host on ${this.port} didn't come up:\n${this.log.join('')}`);
  }

  /** SIGTERM, as a shutdown would: the host saves its board on the way down. */
  async stop() {
    const exited = new Promise((resolve) => this.process.once('exit', resolve));
    this.process.kill('SIGTERM');
    await exited;
    this.process = null;
  }

  /** A wiped host: the next start begins from an empty data dir, so a new board. */
  wipe() {
    this.dataDir = mkdtempSync(join(tmpdir(), 'bee-durability-wiped-'));
  }

  seed() {
    execFileSync(join(repo, 'gradlew'), [':bee-host:e2eSeed', `-Pbee.e2e.host=${this.url}`, '--console=plain'], {
      timeout: 120_000,
      cwd: repo,
      stdio: 'inherit',
    });
  }
}
