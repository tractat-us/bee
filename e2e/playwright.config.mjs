// bee's browser end-to-end suite. Playwright starts a real bee-host from
// `./gradlew :bee-host:installDist` on a free loopback port, with a throwaway data dir,
// and global-setup.mjs seeds fixture surfaces onto it through `:bee-host:e2eSeed`.
//
// The host's command line (`--port <n> --data-dir <dir> --host-name <name>`) is fixed here, for
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
  // Every spec shares one host and its seeded fixtures, so spec files run one at a time too.
  // In parallel, a second file's board activity broke two-browsers' caret test (PR #42).
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
    // `--host-name bee-lan.test`: second-screen.spec.mjs reaches the host by that name, which the
    // host refuses as a rebinding name otherwise (bee#45).
    command: `../bee-host/build/install/bee-host/bin/bee-host --port ${port} --data-dir ${process.env.BEE_E2E_DATA_DIR} --host-name bee-lan.test`,
    url: `${baseURL}/`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  } : undefined,
});
