// The working-day Notions (plan Task 11): `Poll`, `Board` and the history strip. Each test has its
// own surface, which global-setup.mjs seeded through E2eSeederMain (NotionsFixtures.kt). A test
// that needs another peer to act on the board mid-test (append an option, clear a surface) runs
// the seeder again, as that peer: the browser has no such control, and the host no write route.
//
// Every page's board socket goes through page.routeWebSocket, so a test can cut it and restore it
// to force concurrent edits. context.setOffline leaves WebSockets open (microsoft/playwright#4912).
import { test, expect } from '@playwright/test';
import { execFile } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * Runs the seeder once more against this run's host, as another peer, with `args` (for example
 * `--close poll-history`). It returns once a fresh client reads the change back from the host.
 * Asynchronous on purpose: a blocked event loop would stall every routed socket meanwhile.
 */
async function seeder(...args) {
  const host = `http://127.0.0.1:${process.env.BEE_E2E_PORT}`;
  await promisify(execFile)(
    join(repo, 'gradlew'),
    [':bee-host:e2eSeed', '--console=plain', `--args=--host ${host} ${args.join(' ')}`],
    { cwd: repo, timeout: 120_000 },
  );
}

/**
 * Opens `/` as `name` in `context` (a fresh one when omitted), with its board socket routed through
 * a switch: cutting closes the live socket, and while the switch is off a redial is held
 * unanswered until restored. The same shape as two-browsers.spec.mjs's `openPeer`.
 */
async function openPeer(browser, name, context) {
  const owned = !context;
  context ??= await browser.newContext();
  const page = await context.newPage();
  const link = { online: true, sockets: [], waiting: [] };
  const connect = (ws, onServer = () => {}) => {
    const server = ws.connectToServer();
    onServer(server);
    ws.onMessage((m) => server.send(m));
    server.onMessage((m) => ws.send(m));
    link.sockets.push({ ws, server });
  };
  await page.routeWebSocket(/\/ws\/board/, (ws) => {
    if (link.online) return connect(ws);
    const parked = { ws, closed: false, server: null };
    ws.onClose((code, reason) => {
      parked.closed = true;
      if (parked.server) parked.server.close({ code, reason }).catch(() => {});
    });
    link.waiting.push(parked);
  });
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return {
    page,
    context,
    owned,
    async cut() {
      link.online = false;
      for (const { ws, server } of link.sockets.splice(0)) {
        await server.close().catch(() => {});
        await ws.close().catch(() => {});
      }
      await expect(page.locator('#status')).toHaveText(/^offline/, { timeout: 10_000 });
    },
    async restore() {
      link.online = true;
      for (const parked of link.waiting.splice(0)) {
        if (!parked.closed) connect(parked.ws, (server) => { parked.server = server; });
      }
      await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    },
  };
}

const surface = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"]`);
const option = (page, id, value) => surface(page, id).locator(`bee-poll li[data-option="${value}"]`);
const vote = (page, id, value) => option(page, id, value).locator('button').click();
const total = (page, id) => surface(page, id).locator('bee-poll .total');
const column = (page, id, value) => surface(page, id).locator(`bee-board [data-column="${value}"]`);
const card = (page, id, value) => surface(page, id).locator(`bee-board [data-card="${value}"]`);
const records = (page, id) => page.locator(`#records li[data-surface-id="${id}"]`);

let peers = [];

test.afterEach(async () => {
  for (const peer of peers) if (peer.owned) await peer.context.close();
  for (const peer of peers) if (!peer.owned) await peer.context.close().catch(() => {});
  peers = [];
});

test('twoTabsOfOnePersonCountOnce', async ({ browser }) => {
  const alexTab = await openPeer(browser, 'alex');
  const alexOtherTab = await openPeer(browser, 'alex', alexTab.context);
  const sam = await openPeer(browser, 'sam');
  peers = [alexTab, alexOtherTab, sam];
  await expect(total(sam.page, 'poll-tabs')).toHaveText('0 answers');

  // Both of alex's tabs answer at once, each on its own replica: two concurrent answers by one person.
  await alexTab.cut();
  await alexOtherTab.cut();
  await vote(alexTab.page, 'poll-tabs', 'pizza');
  await vote(alexOtherTab.page, 'poll-tabs', 'salad');
  await alexTab.restore();
  await alexOtherTab.restore();

  // One person counts once. Their answer shows one value on every peer: the projection's winner.
  for (const peer of peers) {
    await expect(records(peer.page, 'poll-tabs')).toHaveCount(2, { timeout: 10_000 });
    await expect(total(peer.page, 'poll-tabs')).toHaveText('1 answer');
    await expect(option(peer.page, 'poll-tabs', 'salad').locator('.count')).toHaveText('1');
    await expect(option(peer.page, 'poll-tabs', 'pizza').locator('.count')).toHaveText('0');
  }

  await vote(sam.page, 'poll-tabs', 'pizza');
  for (const peer of peers) {
    await expect(total(peer.page, 'poll-tabs')).toHaveText('2 answers', { timeout: 5_000 });
    await expect(option(peer.page, 'poll-tabs', 'pizza').locator('.voters')).toHaveText('human:sam');
  }
});

test('a vote cast before an option was added says so in its basis', async ({ browser }) => {
  test.setTimeout(120_000);
  const alex = await openPeer(browser, 'alex');
  peers = [alex];
  await expect(surface(alex.page, 'poll-basis').locator('bee-poll li')).toHaveCount(2);

  await vote(alex.page, 'poll-basis', 'tea');
  await expect(records(alex.page, 'poll-basis')).toHaveCount(1, { timeout: 5_000 });
  await expect(records(alex.page, 'poll-basis').last()).toContainText('human:alex answer on poll, having seen /options 2,');

  // Another peer adds an option after alex voted. alex's record keeps what alex had seen.
  await seeder('--append-option', 'poll-basis=cocoa');
  await expect(surface(alex.page, 'poll-basis').locator('bee-poll li')).toHaveCount(3, { timeout: 10_000 });
  await expect(records(alex.page, 'poll-basis').first()).toContainText('having seen /options 2,');

  await vote(alex.page, 'poll-basis', 'cocoa');
  await expect(records(alex.page, 'poll-basis')).toHaveCount(2, { timeout: 5_000 });
  await expect(records(alex.page, 'poll-basis').last()).toContainText('having seen /options 3,');
  await expect(total(alex.page, 'poll-basis')).toHaveText('1 answer');
});

test('dropping a card moves it in every browser', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  await expect(column(sam.page, 'board-drop', 'todo').locator('[data-card="c1"]')).toBeVisible();

  await card(alex.page, 'board-drop', 'c1').dragTo(column(alex.page, 'board-drop', 'doing'));

  for (const peer of peers) {
    await expect(column(peer.page, 'board-drop', 'doing').locator('[data-card="c1"]')).toBeVisible({ timeout: 5_000 });
    await expect(column(peer.page, 'board-drop', 'todo').locator('[data-card="c1"]')).toHaveCount(0);
    await expect(records(peer.page, 'board-drop').last()).toContainText('human:alex drop on board');
  }
});

test('concurrent drops never duplicate a card', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  await expect(card(sam.page, 'board-race', 'c1')).toBeVisible();

  await alex.cut();
  await sam.cut();
  await card(alex.page, 'board-race', 'c1').dragTo(column(alex.page, 'board-race', 'doing'));
  await card(sam.page, 'board-race', 'c1').dragTo(column(sam.page, 'board-race', 'done'));
  await expect(column(alex.page, 'board-race', 'doing').locator('[data-card="c1"]')).toBeVisible();
  await expect(column(sam.page, 'board-race', 'done').locator('[data-card="c1"]')).toBeVisible();
  await alex.restore();
  await sam.restore();

  // Each card shows once, in one column, the same on both: the greater column id wins.
  for (const peer of peers) {
    await expect(records(peer.page, 'board-race')).toHaveCount(2, { timeout: 10_000 });
    await expect(column(peer.page, 'board-race', 'done').locator('[data-card="c1"]')).toBeVisible();
    await expect(card(peer.page, 'board-race', 'c1')).toHaveCount(1);
    await expect(card(peer.page, 'board-race', 'c2')).toHaveCount(1);
  }
  // And it stays: showing the winner writes nothing back.
  await alex.page.waitForTimeout(1_500);
  for (const peer of peers) await expect(card(peer.page, 'board-race', 'c1')).toHaveCount(1);
});

test('clear the board then reopen from history keeps the votes', async ({ browser }) => {
  test.setTimeout(120_000);
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  await vote(alex.page, 'poll-history', 'pizza');
  await vote(sam.page, 'poll-history', 'salad');
  await expect(total(alex.page, 'poll-history')).toHaveText('2 answers', { timeout: 5_000 });

  await seeder('--close', 'poll-history');
  const strip = (page) => page.locator('#history');
  const entry = (page) => strip(page).locator('button[data-surface-id="poll-history"]');
  for (const peer of peers) {
    await expect(surface(peer.page, 'poll-history')).toHaveCount(0, { timeout: 10_000 });
    // Collapsed: listed, but not shown until opened.
    await expect(entry(peer.page)).toHaveCount(1);
    await expect(entry(peer.page)).toBeHidden();
  }

  await strip(alex.page).locator('summary').click();
  await expect(entry(alex.page)).toHaveText('Fixture poll-history');
  await entry(alex.page).click();

  for (const peer of peers) {
    await expect(total(peer.page, 'poll-history')).toHaveText('2 answers', { timeout: 10_000 });
    await expect(option(peer.page, 'poll-history', 'pizza').locator('.voters')).toHaveText('human:alex');
    await expect(entry(peer.page)).toHaveCount(0);
  }
});

test('markup in labels renders as text', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  peers = [alex];
  const markup = '<img src=x onerror=window.pwned=1>';
  const shown = surface(alex.page, 'markup');
  await expect(shown.locator('bee-poll li button')).toHaveText(markup);
  await expect(shown.locator('bee-poll .question')).toHaveText(markup);
  await expect(shown.locator('bee-board [data-card="c1"]')).toHaveText(markup);
  await expect(shown.locator('bee-board [data-column="todo"] h3')).toHaveText(markup);

  // Answering shows the option once more, as the voter's choice: still text.
  await shown.locator('bee-poll li button').click();
  await expect(shown.locator('bee-poll .total')).toHaveText('1 answer', { timeout: 5_000 });
  await expect(shown.locator('img')).toHaveCount(0);
  expect(await alex.page.evaluate(() => window.pwned)).toBeUndefined();
});
