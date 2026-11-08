// The board survives host restarts and closed browsers (Task 9, spike 6).
//
// These tests stop and start a host, so they run their own: a bee-host from the same installDist
// as Playwright's, on its own port and data dir, seeded once through `:bee-host:e2eSeed`. The
// shared host stays up for the other specs. Stopping it is SIGTERM to the process, never
// setOffline: a host that is down has to look down to the page, sockets and all.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Host, freePort } from './host.mjs';

/**
 * Remembers every file the host serves [page], and returns a switch that serves the page from
 * those while the host is down. This is a browser HTTP cache that serves the page anyway, which
 * bee doesn't ask for: it has no service worker, and in production a reload with the host down
 * shows the browser's error page. The host's board id is never served from here, as its
 * `no-store` asks, so the page falls back to the board it kept last. Nothing is routed while the
 * host is up: a page with any route installed never connected its board socket.
 */
function rememberThePage(page) {
  const files = new Map();
  page.on('response', async (response) => {
    if (response.request().resourceType() === 'websocket') return;
    try {
      files.set(response.url(), { status: response.status(), headers: response.headers(), body: await response.body() });
    } catch {
      // A response without a body (a redirect, or one the page abandoned) is not worth keeping.
    }
  });
  return async function serveFromMemory() {
    await page.route((url) => !url.pathname.startsWith('/ws/'), async (route) => {
      const file = new URL(route.request().url()).pathname === '/board-id' ? undefined : files.get(route.request().url());
      if (file) await route.fulfill(file);
      else await route.abort();
    });
  };
}

/** Counts this page's IndexedDB puts in `window.__beePuts`: how often it wrote the board's copy. */
async function countWrites(context) {
  await context.addInitScript(() => {
    window.__beePuts = 0;
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (...args) {
      window.__beePuts += 1;
      return put.apply(this, args);
    };
  });
}

/**
 * This page's IndexedDB copy, as `{ keys, board, copy }`: every key the store holds, the board it
 * says it keeps (`board/id`), and that board's copy as text (its JSON), each null when absent.
 */
async function storeContents(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('bee-board', 1);
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const kv = open.result.transaction('kv', 'readonly').objectStore('kv');
      const text = (bytes) => (bytes ? new TextDecoder().decode(bytes) : null);
      const keys = kv.getAllKeys();
      const id = kv.get('board/id');
      keys.onerror = id.onerror = () => reject(keys.error ?? id.error);
      id.onsuccess = () => {
        const board = text(id.result);
        if (board === null) {
          open.result.close();
          resolve({ keys: keys.result ?? [], board, copy: null });
          return;
        }
        const copy = kv.get(`board/${board}/state.v1`);
        copy.onerror = () => reject(copy.error);
        copy.onsuccess = () => {
          open.result.close();
          resolve({ keys: keys.result, board, copy: text(copy.result) });
        };
      };
    };
  }));
}

/** The kept board's copy in this page's IndexedDB, as text, or null if there is none yet. */
async function savedCopy(page) {
  return (await storeContents(page)).copy;
}

const surface = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"]`);
const field = (page, id) => surface(page, id).locator('a2ui-basic-textfield input');
const press = (page, id) => surface(page, id).getByRole('button', { name: 'Press' });
const records = (page, id) => page.locator(`#records li[data-surface-id="${id}"]`);
const writer = (page) => page.locator('#store');

async function open(page, url, name) {
  await page.goto(`${url}/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

test.describe.configure({ mode: 'serial' });

let host;

test.beforeAll(async () => {
  test.setTimeout(240_000);
  host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-durability-')));
  await host.start();
  host.seed();
});

test.afterAll(async () => {
  if (host?.process) await host.stop();
});

// What production does: no page loads while the host is down, but the tab that stays open keeps
// what it does in this browser's copy, and the next page, once the host is back, sends it on.
test('an edit made while the host is down reaches it from a closed tab', async ({ browser }) => {
  test.setTimeout(120_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  await open(page, host.url, 'alex');
  await expect(writer(page)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });

  await host.stop();
  await expect(page.locator('#status')).toHaveText(/^offline/, { timeout: 15_000 });
  await field(page, 'typing').fill('made while the host was down');
  await expect.poll(() => savedCopy(page), { timeout: 10_000 }).toContain('made while the host was down');
  await page.close();
  await host.start();

  const next = await context.newPage();
  await open(next, host.url, 'alex');
  await expect(field(next, 'typing')).toHaveValue('made while the host was down', { timeout: 10_000 });
  // Another browser, with no copy of its own, sees it: the edit reached the host.
  const sam = await browser.newContext();
  const samPage = await sam.newPage();
  await open(samPage, host.url, 'sam');
  await expect(field(samPage, 'typing')).toHaveValue('made while the host was down', { timeout: 10_000 });
  await sam.close();
  await context.close();
});

// Stands in for a browser cache that serves the page with the host down, which bee doesn't
// provide (see rememberThePage): the page then starts from the board this browser kept last.
test('a page served from the browser cache with the host down shows its copy', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const serveFromMemory = rememberThePage(page);
  await open(page, host.url, 'alex');
  await expect(writer(page)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });
  await field(page, 'typing').fill('kept in this browser');
  await expect.poll(() => savedCopy(page), { timeout: 10_000 }).toContain('kept in this browser');

  await host.stop();
  await serveFromMemory();
  await page.reload();

  await expect(page.locator('#status')).toHaveText(/^offline/, { timeout: 15_000 });
  await expect(field(page, 'typing')).toHaveValue('kept in this browser', { timeout: 10_000 });
  await context.close();
  await host.start();
});

test('closing every browser loses nothing', async ({ browser }) => {
  test.setTimeout(120_000);
  const alex = await browser.newContext();
  const sam = await browser.newContext();
  const alexPage = await alex.newPage();
  const samPage = await sam.newPage();
  await open(alexPage, host.url, 'alex');
  await open(samPage, host.url, 'sam');
  const before = await records(alexPage, 'hold').count();

  await field(alexPage, 'hold').fill('outlives every browser');
  await press(alexPage, 'hold').click();
  // sam has both, so the host has them: everything on the board went through it.
  await expect(field(samPage, 'hold')).toHaveValue('outlives every browser', { timeout: 5_000 });
  await expect(records(samPage, 'hold')).toHaveCount(before + 1, { timeout: 5_000 });
  await alex.close();
  await sam.close();
  // And the host goes down and comes back while no browser holds the board.
  await host.stop();
  await host.start();

  const kim = await browser.newContext();
  const kimPage = await kim.newPage();
  await open(kimPage, host.url, 'kim');
  await expect(field(kimPage, 'hold')).toHaveValue('outlives every browser', { timeout: 10_000 });
  await expect(records(kimPage, 'hold')).toHaveCount(before + 1, { timeout: 10_000 });
  await expect(records(kimPage, 'hold').last()).toHaveText(/^human:alex press on press-button/);
  await kim.close();
});

test('only one tab writes the snapshot', async ({ browser }) => {
  test.setTimeout(60_000);
  const context = await browser.newContext();
  await countWrites(context);
  const first = await context.newPage();
  await open(first, host.url, 'alex');
  await expect(writer(first)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });
  const second = await context.newPage();
  await open(second, host.url, 'alex');
  await expect(writer(second)).toHaveAttribute('data-writer', 'false');

  // An edit in the second tab reaches the first through the host; only the first writes it down.
  const puts = (page) => page.evaluate(() => window.__beePuts);
  const before = { first: await puts(first), second: await puts(second) };
  await field(second, 'conflict').fill('from the second tab');
  await expect(field(first, 'conflict')).toHaveValue('from the second tab', { timeout: 5_000 });
  await expect.poll(() => savedCopy(second), { timeout: 10_000 }).toContain('from the second tab');
  await second.waitForTimeout(2_500);
  expect(await puts(first)).toBeGreaterThan(before.first);
  expect(await puts(second)).toBe(before.second);

  // The lock passes when its holder goes, and the second tab takes over the writing.
  await first.close();
  await expect(writer(second)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });
  await field(second, 'conflict').fill('after the first tab closed');
  await expect.poll(() => savedCopy(second), { timeout: 10_000 }).toContain('after the first tab closed');
  expect(await puts(second)).toBeGreaterThan(before.second);
  await context.close();
});

// Last: it wipes the host. A copy belongs to one board, so a new host never takes in an old one.
test('a wiped host never takes in an old copy', async ({ browser }) => {
  test.setTimeout(240_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  await open(page, host.url, 'alex');
  await expect(writer(page)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });
  await field(page, 'typing').fill('from the old board');
  await expect.poll(() => savedCopy(page), { timeout: 10_000 }).toContain('from the old board');
  const old = (await storeContents(page)).board;
  await page.close();

  await host.stop();
  host.wipe();
  await host.start();
  host.seed();

  const next = await context.newPage();
  await open(next, host.url, 'alex');
  await expect(writer(next)).toHaveAttribute('data-writer', 'true', { timeout: 10_000 });
  await expect(field(next, 'typing')).toHaveValue('');
  // Nor did the host take the old edit in from this page.
  const sam = await browser.newContext();
  const samPage = await sam.newPage();
  await open(samPage, host.url, 'sam');
  await expect(surface(samPage, 'typing')).toBeVisible();
  await expect(field(samPage, 'typing')).toHaveValue('');
  // And the writer dropped the old board's copy for the new one's.
  await expect.poll(async () => {
    const { keys, board } = await storeContents(next);
    return board !== old && keys.length === 2 && keys.includes(`board/${board}/state.v1`);
  }, { timeout: 10_000 }).toBe(true);
  expect(await savedCopy(next)).not.toContain('from the old board');
  await sam.close();
  await context.close();
});

// A tab left open across a wipe (bee#46). Its sockets name the board it holds, the new host refuses
// them, and the tab reloads onto the new board rather than push the old one in. Before, it pushed
// its whole board at first contact: the seed below then found its fixtures already there.
test('a tab left open across a wipe never pushes its old board into the new host', async ({ browser }) => {
  test.setTimeout(240_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  await open(page, host.url, 'alex');
  await field(page, 'typing').fill('from before the wipe');
  const old = await (await fetch(`${host.url}/board-id`)).text();

  await host.stop();
  await expect(page.locator('#status')).toHaveText(/^offline/, { timeout: 15_000 });
  host.wipe();
  await host.start();
  host.seed();
  const fresh = await (await fetch(`${host.url}/board-id`)).text();

  // The tab came back on its own, on the new board, with nothing of the old one.
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 30_000 });
  await expect(surface(page, 'typing')).toBeVisible();
  await expect(field(page, 'typing')).toHaveValue('');
  // A browser with no copy of its own sees what the host holds: nothing of the old board.
  const sam = await browser.newContext();
  const samPage = await sam.newPage();
  await open(samPage, host.url, 'sam');
  await expect(surface(samPage, 'typing')).toBeVisible();
  await expect(field(samPage, 'typing')).toHaveValue('');
  expect(fresh).not.toBe(old);
  await sam.close();
  await context.close();
});
