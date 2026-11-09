// A second screen pairs and joins (Task 10, spike 7; spec §5.7).
//
// The "remote" page is reached by a name that isn't loopback, `bee-lan.test`, which Chromium
// resolves to 127.0.0.1 (--host-resolver-rules). So the page takes itself for a LAN device and
// pairs, while the host is never bound off loopback: no test here opens the laptop to the network.
// The flip side is that the host sees every one of these sockets arrive from 127.0.0.1, which
// needs no key, so whether the host refuses a keyless LAN socket is LanKeyGateTest's to prove,
// not this suite's. What this suite proves is the page: it shows a code and nothing of the board
// until a person on the laptop types that code, and then joins with the key it was given.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Host, freePort } from './host.mjs';

const LAN_NAME = 'bee-lan.test';

// The tests pair devices and read the seeded fixtures back, so this spec runs a seeded host of its
// own, and it names itself `bee-lan.test`, which the host refuses as a rebinding name otherwise
// (bee#45). Its own host also keeps its pairing requests off every other spec's board (bee#74).
const host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-second-screen-')), ['--host-name', LAN_NAME]);
test.use({
  baseURL: host.url,
  launchOptions: { args: [`--host-resolver-rules=MAP ${LAN_NAME} 127.0.0.1`] },
});

let contexts = [];

test.beforeAll(async () => {
  test.setTimeout(240_000);
  await host.start();
  host.seed();
});

test.afterAll(async () => {
  if (host.process) await host.stop();
});

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

/**
 * Opens the page as a room screen named `screen`, reached by the LAN name, logging every socket it
 * opens and every request it makes.
 */
async function openRemote(browser, screen) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const sockets = [];
  const requests = [];
  page.on('websocket', (ws) => sockets.push(new URL(ws.url())));
  page.on('request', (r) => requests.push({ url: new URL(r.url()), method: r.method(), accept: r.headers()['accept'] }));
  await page.goto(`http://${LAN_NAME}:${host.port}/?screen=${screen}`);
  return { page, sockets, requests };
}

/** Opens the page on the laptop, from 127.0.0.1, as `name`. */
async function openLaptop(browser, name = 'iain') {
  return (await openTappedLaptop(browser, name)).page;
}

/**
 * Opens the page on the laptop as `name`, keeping every board frame it sends (`up`) and receives
 * (`down`) as bytes, so a test can search what crossed the wire.
 */
async function openTappedLaptop(browser, name) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const frames = { up: [], down: [] };
  await page.routeWebSocket(/\/ws\/board/, (ws) => {
    const server = ws.connectToServer();
    ws.onMessage((m) => {
      frames.up.push(Buffer.from(m));
      server.send(m);
    });
    server.onMessage((m) => {
      frames.down.push(Buffer.from(m));
      ws.send(m);
    });
  });
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return { page, frames };
}

/** How many of `frames` carry `text`. The board's wire is CBOR, which keeps a string's UTF-8 bytes whole. */
const carrying = (frames, text) => frames.filter((frame) => frame.includes(Buffer.from(text))).length;

const code = (page) => page.locator('#pairing-code');

/** The join request naming `screen`, on the laptop's board. */
const request = (page, screen) =>
  page.locator('a2ui-surface[data-surface-id^="bee-pair-"]').filter({ hasText: `"${screen}"` });

/** Types the remote page's code into its join request on the laptop, and admits it. */
async function admit(laptop, remote, screen) {
  await expect(code(remote.page)).toHaveText(/^[0-9]{6}$/, { timeout: 15_000 });
  const shown = await code(remote.page).textContent();
  const joining = request(laptop, screen);
  await joining.locator('a2ui-basic-textfield input').fill(shown);
  await joining.getByRole('button', { name: 'Admit' }).click();
  await expect(remote.page.locator('#status')).toHaveText(`online as human:${screen}`, { timeout: 15_000 });
}

test('keyless remote page shows a code and no board', async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-a');

  await expect(code(remote.page)).toHaveText(/^[0-9]{6}$/, { timeout: 15_000 });
  const shown = await code(remote.page).textContent();
  await expect(remote.page.locator('#pairing-prompt')).toHaveText('Enter this code on the board to join');
  // The laptop's board asks for the code, and names the device, without showing the code.
  await expect(request(laptop, 'room-a')).toContainText('enter the code it shows', { timeout: 10_000 });
  await expect(request(laptop, 'room-a')).toContainText('From 127.0.0.1');
  expect(await laptop.locator('body').innerText()).not.toContain(shown);

  // Nothing of the board reached the remote page: no surface, no record, no status, and no
  // socket at all. Pairing is plain requests by poll (bee#209), never a stream.
  await expect(remote.page.locator('a2ui-surface')).toHaveCount(0);
  await expect(remote.page.locator('#status')).toHaveCount(0);
  await expect(remote.page.locator('#records')).toHaveCount(0);
  expect(remote.sockets).toEqual([]);
  const pairing = remote.requests.filter((r) => r.url.pathname === '/pair');
  expect(pairing.map((r) => r.method), 'one pairing opened, never reopened').toEqual(['POST']);
  await expect.poll(() => remote.requests.filter((r) => r.url.pathname === '/pair/poll').length, { timeout: 10_000 }).toBeGreaterThan(1);
  for (const r of remote.requests) expect(r.accept ?? '').not.toContain('text/event-stream');
});

// bee#209: a TLS-inspecting proxy at work held the pairing stream back until it ended, so the page
// waited for ever. Here every event stream the page might open is held the same way, sent nothing:
// the page still shows its code and is admitted, since it pairs by plain requests.
test('a page whose event streams are held back still pairs', async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const context = await browser.newContext();
  contexts.push(context);
  await context.route('**/*', (route) =>
    route.request().headers()['accept']?.includes('text/event-stream') ? new Promise(() => {}) : route.continue(),
  );
  const page = await context.newPage();
  await page.goto(`http://${LAN_NAME}:${host.port}/?screen=room-held`);

  await admit(laptop, { page }, 'room-held');
});

test('typing the code on the laptop admits the remote page', async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-b');

  await admit(laptop, remote, 'room-b');
  // The remote page holds the board now, fixtures and all, and the request is gone.
  await expect(remote.page.locator('#pairing')).toHaveCount(0);
  await expect(remote.page.locator('a2ui-surface[data-surface-id="typing"]')).toBeVisible();
  await expect(request(laptop, 'room-b')).toHaveCount(0, { timeout: 10_000 });
});

test('page reached by a non-localhost name connects back to that name', async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-c');

  await admit(laptop, remote, 'room-c');
  const board = remote.sockets.filter((url) => url.pathname === '/ws/board');
  expect(board.length).toBeGreaterThan(0);
  for (const url of remote.sockets) {
    expect(url.hostname).toBe(LAN_NAME);
    expect(url.protocol).toBe('ws:');
  }
  // The board socket carries the key pairing gave the page.
  for (const url of board) expect(url.searchParams.get('k')).toMatch(/^[A-Za-z0-9_-]{43}$/);
});

// A person's page opens a claims link, to propose its person's answers to orphan decisions; a
// screen's page opens none (spec §6.4, A7). This pins that: a screen never dials /ws/claims or
// proposes a claim.
test('a screen peer never proposes a claim', async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-d');
  await admit(laptop, remote, 'room-d');

  // The screen acts, as anyone on the board may: its click is a record, and no claim.
  const press = remote.page.locator('a2ui-surface[data-surface-id="records"]').getByRole('button', { name: 'Press' });
  await press.click();
  await expect(laptop.locator('#records li[data-surface-id="records"]').last()).toHaveText(/^human:room-d press on press-button/, {
    timeout: 5_000,
  });
  await remote.page.waitForTimeout(1_000);
  expect(remote.sockets.map((url) => url.pathname)).not.toContain('/ws/claims');
});

// §5.7: "The code itself is never written to the board". Live typing syncs a bound field as it is
// typed, so the join request's code field must stay on the page that types it: the code leaves
// that page only inside the admit record.
test('a code typed but not yet admitted stays on the page that typed it', async ({ browser }) => {
  const typist = await openTappedLaptop(browser, 'iain');
  const watcher = await openTappedLaptop(browser, 'sam');
  const remote = await openRemote(browser, 'room-e');

  await expect(code(remote.page)).toHaveText(/^[0-9]{6}$/, { timeout: 15_000 });
  const shown = await code(remote.page).textContent();
  const joining = request(typist.page, 'room-e');
  await joining.locator('a2ui-basic-textfield input').pressSequentially(shown, { delay: 40 });
  // Well past live typing's 150 ms debounce: a synced field would have been written by now.
  await typist.page.waitForTimeout(1_500);
  const upBeforeAdmit = carrying(typist.frames.up, shown);
  const downBeforeAdmit = carrying(watcher.frames.down, shown);
  const watcherField = await request(watcher.page, 'room-e').locator('a2ui-basic-textfield input').inputValue();

  // The control: Admit sends the code inside the record, from the typing page's own data model,
  // and the watcher's tap sees it arrive.
  await joining.getByRole('button', { name: 'Admit' }).click();
  await expect(remote.page.locator('#status')).toHaveText('online as human:room-e', { timeout: 15_000 });
  await expect.poll(() => carrying(watcher.frames.down, shown), { timeout: 5_000 }).toBeGreaterThan(0);

  expect(upBeforeAdmit, 'frames the typing page sent with the code in them, before Admit').toBe(0);
  expect(downBeforeAdmit, 'frames another page received with the code in them, before Admit').toBe(0);
  expect(watcherField).toBe('');
});

// A person's page opens a claims link, to propose its person's answers to orphan decisions (spec
// §6.4). Reached over the LAN, it carries the same key and board as the board socket, or a LAN host
// would refuse it.
test("a person's page dials /ws/claims with its key and board", async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-h');
  await admit(laptop, remote, 'room-h');
  const key = await remote.page.evaluate(() => sessionStorage.getItem('bee.lan.key'));

  const context = await browser.newContext();
  contexts.push(context);
  const person = await context.newPage();
  const sockets = [];
  person.on('websocket', (ws) => sockets.push(new URL(ws.url())));
  await person.goto(`http://${LAN_NAME}:${host.port}/?name=ada&k=${key}`);
  await expect(person.locator('#status')).toHaveText('online as human:ada', { timeout: 15_000 });
  await expect.poll(() => sockets.filter((url) => url.pathname === '/ws/claims').length, { timeout: 10_000 }).toBeGreaterThan(0);

  const board = sockets.find((url) => url.pathname === '/ws/board');
  for (const claims of sockets.filter((url) => url.pathname === '/ws/claims')) {
    expect(claims.hostname).toBe(LAN_NAME);
    expect(claims.searchParams.get('k')).toBe(key);
    expect(claims.searchParams.get('board')).toMatch(/.+/);
    expect(claims.searchParams.get('board')).toBe(board.searchParams.get('board'));
  }
});

// A join link's key goes from the address bar into this tab's storage, then leaves the address
// bar, so a screenshot, a shared screen or a copied URL doesn't carry it on.
test("a join link's key leaves the address bar once the page holds it", async ({ browser }) => {
  const laptop = await openLaptop(browser);
  const remote = await openRemote(browser, 'room-f');
  await admit(laptop, remote, 'room-f');
  const key = await remote.page.evaluate(() => sessionStorage.getItem('bee.lan.key'));

  const context = await browser.newContext();
  contexts.push(context);
  const linked = await context.newPage();
  await linked.goto(`http://${LAN_NAME}:${host.port}/?screen=room-g&k=${key}`);
  await expect(linked.locator('#status')).toHaveText('online as human:room-g', { timeout: 15_000 });
  const search = await linked.evaluate(() => location.search);
  const kept = await linked.evaluate(() => sessionStorage.getItem('bee.lan.key'));

  expect(new URLSearchParams(search).has('k'), `location.search is ${search}`).toBe(false);
  expect(new URLSearchParams(search).get('screen'), 'the rest of the query stays').toBe('room-g');
  expect(kept).toBe(key);
  await expect(linked.locator('#pairing')).toHaveCount(0);
});
