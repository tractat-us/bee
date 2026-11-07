// Two browsers on one board (Task 4, spike 2 part 3). Each test opens its own surface, which
// global-setup.mjs seeded through a JVM BoardClient (E2eSeederMain): a TextField bound to
// /note, a Text echoing /note, a caption bound to /note's conflict set, and a Press button.
//
// Every page's board socket goes through page.routeWebSocket, so a test can cut it and
// restore it. context.setOffline leaves WebSockets open (microsoft/playwright#4912).
import { test, expect } from '@playwright/test';

/**
 * Opens `/` as `name` with its board socket routed through a switch, and returns the page,
 * the switch and a frame log. Cutting closes the live socket. While the switch is off, a
 * redial is held unanswered, never connected to the host, and restoring connects it.
 */
async function openPeer(browser, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const link = { online: true, sockets: [], waiting: [], frames: [] };
  const connect = (ws) => {
    const server = ws.connectToServer();
    ws.onMessage((m) => {
      link.frames.push({ dir: 'up', bytes: byteLength(m), at: Date.now() });
      server.send(m);
    });
    server.onMessage((m) => {
      link.frames.push({ dir: 'down', bytes: byteLength(m), at: Date.now() });
      ws.send(m);
    });
    link.sockets.push({ ws, server });
  };
  await page.routeWebSocket(/\/ws\/board/, (ws) => {
    if (link.online) connect(ws);
    else link.waiting.push(ws);
  });
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return {
    page,
    context,
    link,
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
      for (const ws of link.waiting.splice(0)) connect(ws);
      await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    },
  };
}

function byteLength(m) {
  return typeof m === 'string' ? Buffer.byteLength(m) : m.length;
}

const surface = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"]`);
const field = (page, id) => surface(page, id).locator('a2ui-basic-textfield input');
/** The Text echoing /note: the only `h5` in the surface (Text variant "h5"). */
const echo = (page, id) => surface(page, id).locator('a2ui-basic-text h5');

/** The conflict caption: the only `em` in the surface (Text variant "caption"). */
const conflict = (page, id) => surface(page, id).locator('a2ui-basic-text em');

/** Moves focus off the field, so the page lets held remote changes through. */
async function blur(page) {
  await page.locator('#status').click();
}

let peers = [];

test.afterEach(async () => {
  for (const peer of peers) await peer.context.close();
  peers = [];
});

test('typing in one browser appears in the other', async ({ browser }, testInfo) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  await expect(field(sam.page, 'typing')).toHaveValue('');

  await field(alex.page, 'typing').pressSequentially('hello', { delay: 40 });
  await expect(field(sam.page, 'typing')).toHaveValue('hello', { timeout: 2_000 });
  await expect(echo(sam.page, 'typing')).toHaveText('hello');

  // The frame size of a one-field edit (kuilt#2469): one more keystroke, then every frame on
  // alex's board socket for the next second, both ways.
  await blur(alex.page);
  await alex.page.waitForTimeout(1_500);
  const from = Date.now();
  await field(alex.page, 'typing').press('End');
  await field(alex.page, 'typing').press('!');
  await expect(field(sam.page, 'typing')).toHaveValue('hello!', { timeout: 2_000 });
  await alex.page.waitForTimeout(1_000);
  const frames = alex.link.frames.filter((f) => f.at >= from);
  const report = {
    frames: frames.length,
    up: frames.filter((f) => f.dir === 'up').map((f) => f.bytes),
    down: frames.filter((f) => f.dir === 'down').map((f) => f.bytes),
  };
  console.log(`BEE_FRAME_SIZES ${JSON.stringify(report)}`);
  testInfo.annotations.push({ type: 'frame sizes of a one-field edit', description: JSON.stringify(report) });
});

test('concurrent typing shows one value and a conflict marker in both', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];

  await alex.cut();
  await sam.cut();
  await field(alex.page, 'conflict').fill('apple');
  await field(sam.page, 'conflict').fill('banana');
  // Past the 150 ms debounce, so each edit is on its page's own replica before it reconnects.
  await alex.page.waitForTimeout(500);
  await blur(alex.page);
  await blur(sam.page);
  await alex.restore();
  await sam.restore();

  // The projection's winner is the greatest string, the same on both, and the caption bound to
  // /_bee/conflicts/~1note lists both values, winner last.
  for (const peer of [alex, sam]) {
    await expect(field(peer.page, 'conflict')).toHaveValue('banana', { timeout: 10_000 });
    await expect(conflict(peer.page, 'conflict')).toHaveText('apple,banana', { timeout: 10_000 });
  }
});

test('a click becomes a record with a basis', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  const records = (page) => page.locator('#records li[data-surface-id="records"]');

  await surface(alex.page, 'records').getByRole('button', { name: 'Press' }).click();
  // The basis names what alex's replica had delivered: the one seeded /options entry, no records.
  for (const peer of [alex, sam]) {
    await expect(records(peer.page)).toHaveCount(1, { timeout: 5_000 });
    await expect(records(peer.page).first()).toHaveText(
      'human:alex press on press-button, having seen /options 1, records 0',
    );
  }

  await surface(sam.page, 'records').getByRole('button', { name: 'Press' }).click();
  // sam's basis names alex's record, because sam's replica had it when sam clicked.
  for (const peer of [alex, sam]) {
    await expect(records(peer.page)).toHaveCount(2, { timeout: 5_000 });
    await expect(records(peer.page).nth(1)).toHaveText(
      'human:sam press on press-button, having seen /options 1, records 1',
    );
  }
});

test('a remote edit never overwrites the field being typed in', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  // A third browser, never focused, shows when sam's edit has reached the board's peers.
  const witness = await openPeer(browser, 'kim');
  peers = [alex, sam, witness];

  await field(alex.page, 'hold').click();
  await field(alex.page, 'hold').pressSequentially('mine', { delay: 40 });
  await expect(field(sam.page, 'hold')).toHaveValue('mine', { timeout: 2_000 });

  await field(sam.page, 'hold').fill('theirs');
  await blur(sam.page);
  await expect(field(witness.page, 'hold')).toHaveValue('theirs', { timeout: 2_000 });
  await alex.page.waitForTimeout(1_000);

  // alex is still in the field: sam's edit is held, in the field and everywhere /note shows.
  await expect(field(alex.page, 'hold')).toBeFocused();
  await expect(field(alex.page, 'hold')).toHaveValue('mine');
  await expect(echo(alex.page, 'hold')).toHaveText('mine');

  // Leaving the field lets the held edit through. sam's write saw alex's, so it supersedes it,
  // with no conflict.
  await blur(alex.page);
  await expect(field(alex.page, 'hold')).toHaveValue('theirs', { timeout: 2_000 });
  await expect(conflict(alex.page, 'hold')).toHaveText('');
});
