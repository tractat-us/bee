// bee's browser end-to-end suite. Playwright starts a real bee-host from
// `./gradlew :bee-host:installDist` on a free loopback port, with a throwaway data dir,
// and global-setup.mjs seeds fixture surfaces onto it through `:bee-host:e2eSeed`.
//
// The host's command line (`--port <n> --data-dir <dir>`) is fixed here, for Task 3's
// HostConfig to read.
import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync, readdirSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** A port free on loopback now. The host binds it a moment later. */
async function freePort() {
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

// The config is evaluated in the runner and again in each worker. Pick the port once and
// hand it to the workers through the environment.
process.env.BEE_E2E_PORT ??= String(await freePort());
process.env.BEE_E2E_DATA_DIR ??= mkdtempSync(join(tmpdir(), 'bee-e2e-'));
const port = Number(process.env.BEE_E2E_PORT);
const baseURL = `http://127.0.0.1:${port}`;

// Until Task 4 adds the first spec there is nothing to test, and until Task 3 there is no
// host Main to start, so the host and the seeder stay off while no spec exists.
const here = dirname(fileURLToPath(import.meta.url));
const hasSpecs = readdirSync(here).some((f) => f.endsWith('.spec.mjs'));

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  globalSetup: hasSpecs ? './global-setup.mjs' : undefined,
  fullyParallel: false,
  // Every spec shares the one host and its board, and a test that counts the board's records (the
  // basis tests) reads another file's writes as its own. One worker runs the files one at a time.
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: hasSpecs ? {
    command: `../bee-host/build/install/bee-host/bin/bee-host --port ${port} --data-dir ${process.env.BEE_E2E_DATA_DIR}`,
    url: `${baseURL}/`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  } : undefined,
});
