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
 * redial is held unanswered, never connected to the host, and restoring connects each one the
 * page hasn't given up on.
 */
async function openPeer(browser, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const link = { online: true, sockets: [], waiting: [], frames: [] };
  const connect = (ws, onServer = () => {}) => {
    const server = ws.connectToServer();
    onServer(server);
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
    if (link.online) return connect(ws);
    // Parked. Setting onClose stops Playwright forwarding the page's close to the host, so the
    // handler forwards it itself once this socket is connected.
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
      for (const parked of link.waiting.splice(0)) {
        if (!parked.closed) connect(parked.ws, (server) => { parked.server = server; });
      }
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

/** The renderer's root. LiveInputs marks it `data-pending` while a debounced write is outstanding. */
const board = (page) => page.locator('#board');

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
  // Each edit is on its page's own replica before it reconnects: no debounced write is pending.
  for (const peer of [alex, sam]) await expect(board(peer.page)).not.toHaveAttribute('data-pending');
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
  // And it stays: showing the winner is the driver's echo, never an edit, so neither page
  // writes it back and resolves the conflict nobody decided.
  await alex.page.waitForTimeout(1_500);
  for (const peer of [alex, sam]) {
    await expect(conflict(peer.page, 'conflict')).toHaveText('apple,banana');
  }
});

test('a click becomes a record with a basis', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];
  const records = (page) => page.locator('#records li[data-surface-id="records"]');
  const press = (peer) => surface(peer.page, 'records').getByRole('button', { name: 'Press' });

  // The surface and the records on the board arrive in one FullState, so once the button shows,
  // the record list is the board's: whatever other tests left there is the baseline.
  await expect(press(alex)).toBeVisible();
  const onBoard = await alex.page.locator('#records li').count();
  const onSurface = await records(alex.page).count();

  await press(alex).click();
  // The basis names what alex's replica had delivered: the one seeded /options entry, and every
  // record already on the board.
  for (const peer of [alex, sam]) {
    await expect(records(peer.page)).toHaveCount(onSurface + 1, { timeout: 5_000 });
    await expect(records(peer.page).last()).toHaveText(
      `human:alex press on press-button, having seen /options 1, records ${onBoard}`,
    );
  }

  await press(sam).click();
  // sam's basis names alex's record too, because sam's replica had it when sam clicked.
  for (const peer of [alex, sam]) {
    await expect(records(peer.page)).toHaveCount(onSurface + 2, { timeout: 5_000 });
    await expect(records(peer.page).last()).toHaveText(
      `human:sam press on press-button, having seen /options 1, records ${onBoard + 1}`,
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

test('a field left before its edit is written keeps the edit', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  peers = [alex, sam];

  await field(alex.page, 'dirty').fill('quick');
  // Leave the field inside the 150 ms debounce, and look 20 ms later, after the driver has
  // re-rendered for the blur. The path is dirty, so it is still held: without that, the driver
  // would show the board's old value over the person's unwritten edit.
  const seen = await alex.page.evaluate(async () => {
    const input = document.querySelector('a2ui-surface[data-surface-id="dirty"] a2ui-basic-textfield input');
    input.blur();
    await new Promise((resolve) => setTimeout(resolve, 20));
    return { value: input.value, pending: document.getElementById('board').hasAttribute('data-pending') };
  });
  expect(seen).toEqual({ value: 'quick', pending: true });
  await expect(field(sam.page, 'dirty')).toHaveValue('quick', { timeout: 2_000 });
  await expect(field(alex.page, 'dirty')).toHaveValue('quick');
});

test('an invalid surface shows a placeholder naming its errors', async ({ browser }) => {
  const alex = await openPeer(browser, 'alex');
  peers = [alex];
  const placeholder = alex.page.locator('.bee-invalid-surface[data-surface-id="malformed"]');
  await expect(placeholder).toContainText('unknown type "Marquee"');
  await expect(placeholder).toContainText('names child "ghost"');
  await expect(surface(alex.page, 'malformed')).toHaveCount(0);
});
