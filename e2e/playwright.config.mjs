// bee's browser end-to-end suite. Playwright builds `./gradlew :bee-host:installDist` (and the
// bridges' installs) and starts a real bee-host from it on a free loopback port, with a throwaway data dir,
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

// The host and the specs' bridges run from installDist, so build the installs before the host
// starts, or a run after a code edit tests the last install (bee#92). It is quick when they're up
// to date. This lives in webServer's command because Playwright starts webServer before
// globalSetup. --no-watch-fs: file watching can miss an edit and leave a stale page bundle
// (bee#102). CI builds the installs in an earlier step and sets BEE_E2E_PREBUILT=1 to skip it.
const prebuilt = !!process.env.BEE_E2E_PREBUILT;
const build = prebuilt
  ? ''
  : './gradlew :bee-host:installDist :bee-mcp:installDist :bee-companion:installDist --no-watch-fs --console=plain -q && ';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  // CI's demo job runs demo-paths.spec.mjs as a merge gate; the smoke job skips it with this.
  testIgnore: process.env.BEE_E2E_SKIP_DEMO ? 'demo-paths.spec.mjs' : undefined,
  globalSetup: hasSpecs ? './global-setup.mjs' : undefined,
  fullyParallel: false,
  // Two spec files at a time. They share the host and its seeded board, and two-browsers.spec.mjs
  // counts the board's records (the basis tests), so a spec that writes records of its own runs on
  // a host of its own (host.mjs): durability, empty-board, second-screen and sketch do. (The caret
  // failure once blamed on parallel activity was Playwright's: an End key on a scrollable macOS
  // page, and a press on an unfocused field resetting the caret. See #43.)
  workers: 2,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // WebKit on demand, one project per run, since a spec's beforeAll owns the shared board:
    // `BEE_E2E_WEBKIT=1 npx playwright test --project=webkit sketch.spec.mjs`. CI installs Chromium only.
    ...(process.env.BEE_E2E_WEBKIT ? [{ name: 'webkit', use: { ...devices['Desktop Safari'] } }] : []),
  ],
  webServer: hasSpecs ? {
    command: `${build}exec bee-host/build/install/bee-host/bin/bee-host --port ${port} --data-dir ${process.env.BEE_E2E_DATA_DIR}`,
    cwd: join(here, '..'),
    url: `${baseURL}/`,
    reuseExistingServer: false,
    // The build counts against this timeout, and a cold one bundles the whole page.
    timeout: prebuilt ? 120_000 : 600_000,
    stdout: 'pipe',
    stderr: 'pipe',
  } : undefined,
});
