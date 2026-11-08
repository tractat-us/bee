// Only the board's own page may open the board (bee#45).
//
// A page from another site can run `new WebSocket("ws://127.0.0.1:<port>/ws/board")` in a browser
// on the laptop. Loopback needs no key, so the host tells that page from its own by the `Origin` the
// browser sends. Here the other site is a server on another loopback port: the closest a site can
// get to the host's own origin. It is a real server, not a Playwright fulfilment: Chromium treats a
// fulfilled page's address as unknown and may block its local dials itself, which would pass this
// test without the host refusing anything.
import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

const port = Number(process.env.BEE_E2E_PORT);

/** Opens `url` as a WebSocket from `page`: 'open' if it opened, 'refused' if it failed first. */
const dial = (page, url) =>
  page.evaluate(
    (target) =>
      new Promise((resolve) => {
        const ws = new WebSocket(target);
        ws.onopen = () => {
          ws.close();
          resolve('open');
        };
        ws.onerror = () => resolve('refused');
      }),
    url,
  );

let elsewhere;

test.beforeAll(async () => {
  elsewhere = createServer((_, response) => {
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end('<!doctype html><title>elsewhere</title>');
  });
  await new Promise((resolve) => elsewhere.listen(0, '127.0.0.1', resolve));
});

test.afterAll(async () => {
  await new Promise((resolve) => elsewhere.close(resolve));
});

test('a page from another origin cannot open the board socket', async ({ page }) => {
  const board = `ws://127.0.0.1:${port}/ws/board?peer=site-gate`;

  // The control: the board's own page opens the same socket.
  await page.goto('/');
  expect(await dial(page, board)).toBe('open');

  await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
  expect(await dial(page, board)).toBe('refused');
});

// bee#54: an `<img>` sends no `Origin`, so only its Fetch Metadata tells the host another site
// sent it. Admitted, it would hold a pairing slot and, after the dwell, post its `?screen=` text
// to the board as a join request. Chromium may cut such a response short itself (ORB) where Safari
// doesn't, so the host's 403 is what this test reads, not only the empty board.
test('a page from another origin cannot open a pairing channel as an image', async ({ browser, page }) => {
  const context = await browser.newContext();
  try {
    const laptop = await context.newPage();
    await laptop.goto('/?name=iain');
    await expect(laptop.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    const request = (screen) =>
      laptop.locator('a2ui-surface[data-surface-id^="bee-pair-"]').filter({ hasText: `"${screen}"` });
    /** Loads `/pair` as an image on `page`, and returns the host's status for it. */
    const image = async (screen) => {
      const answered = page.waitForResponse((r) => new URL(r.url()).pathname === '/pair');
      await page.evaluate(
        (src) => document.body.append(Object.assign(new Image(), { src })),
        `http://127.0.0.1:${port}/pair?screen=${screen}`,
      );
      return (await answered).status();
    };

    // The control: the board's own page loads the same image, and its request reaches the board.
    await page.goto('/');
    expect(await image('own-image')).toBe(200);
    await expect(request('own-image')).toHaveCount(1, { timeout: 15_000 });

    await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
    expect(await image('elsewhere-image')).toBe(403);
    // Well past the dwell, after which an admitted channel posts its request.
    await laptop.waitForTimeout(6_000);
    await expect(request('elsewhere-image')).toHaveCount(0);
  } finally {
    await context.close();
  }
});
