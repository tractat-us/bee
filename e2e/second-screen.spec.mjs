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

const LAN_NAME = 'bee-lan.test';
test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${LAN_NAME} 127.0.0.1`] } });

let contexts = [];

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

/** Opens the page as a room screen named `screen`, reached by the LAN name, logging every socket it opens. */
async function openRemote(browser, screen) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const sockets = [];
  page.on('websocket', (ws) => sockets.push(new URL(ws.url())));
  await page.goto(`http://${LAN_NAME}:${process.env.BEE_E2E_PORT}/?screen=${screen}`);
  return { page, sockets };
}

/** Opens the page on the laptop, from 127.0.0.1, as `name`. */
async function openLaptop(browser, name = 'iain') {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return page;
}

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
  // socket but the pairing one.
  await expect(remote.page.locator('a2ui-surface')).toHaveCount(0);
  await expect(remote.page.locator('#status')).toHaveCount(0);
  await expect(remote.page.locator('#records')).toHaveCount(0);
  expect(remote.sockets.map((url) => url.pathname)).toEqual(['/ws/pair']);
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

// The page has no claims client yet, so this pins the absence for a screen: the day the page gains
// one, a screen must still never dial /ws/claims or propose a claim.
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
