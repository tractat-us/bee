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
  // The socket names the host's board, so only the origin can refuse it (bee#46).
  const id = await (await fetch(`http://127.0.0.1:${port}/board-id`)).text();
  const board = `ws://127.0.0.1:${port}/ws/board?peer=site-gate&board=${id}`;

  // The control: the board's own page opens the same socket.
  await page.goto('/');
  expect(await dial(page, board)).toBe('open');

  await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
  expect(await dial(page, board)).toBe('refused');
});

// bee#54: an `<img>` or a no-cors `fetch` sends no `Origin`, so only its Fetch Metadata tells the
// host another site sent it. Admitted, it would hold a pairing slot and, after the dwell, post its
// `?screen=` text to the board as a join request. Chromium cuts an image that isn't one short, and
// may block another origin's event stream itself (ORB), where Safari does neither, so the host's
// 403 is what this test reads first, and the empty board second.
test('a page from another origin cannot open a pairing channel without a navigation', async ({ browser, page }) => {
  const context = await browser.newContext();
  try {
    const laptop = await context.newPage();
    await laptop.goto('/?name=iain');
    await expect(laptop.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    const request = (screen) =>
      laptop.locator('a2ui-surface[data-surface-id^="bee-pair-"]').filter({ hasText: `"${screen}"` });
    const pair = (screen) => `http://127.0.0.1:${port}/pair?screen=${screen}`;
    /**
     * Runs `load` on `page` with `/pair` for `screen`, and returns the host's status for it, or the
     * browser's error if the request failed: Chromium's ORB fails an admitted cross-origin stream.
     */
    const status = async (screen, load) => {
      const url = pair(screen);
      let onResponse;
      let onFailed;
      const answered = new Promise((resolve) => {
        onResponse = (r) => r.url() === url && resolve(r.status());
        onFailed = (r) => r.url() === url && resolve(r.failure()?.errorText);
        page.on('response', onResponse);
        page.on('requestfailed', onFailed);
      });
      await page.evaluate(load, url);
      try {
        return await answered;
      } finally {
        page.off('response', onResponse);
        page.off('requestfailed', onFailed);
      }
    };
    const asImage = (src) => document.body.append(Object.assign(new Image(), { src }));
    // Keeps the response, so the stream stays open past the dwell.
    const asNoCorsFetch = (url) => {
      fetch(url, { mode: 'no-cors' }).then((r) => (window.held = r));
    };

    // The control: the board's own page holds the same channel open, and its request reaches the board.
    await page.goto('/');
    expect(await status('own-fetch', asNoCorsFetch)).toBe(200);
    await expect(request('own-fetch')).toHaveCount(1, { timeout: 15_000 });

    await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
    expect(await status('elsewhere-image', asImage)).toBe(403);
    expect(await status('elsewhere-fetch', asNoCorsFetch)).toBe(403);
    // Well past the dwell, after which an admitted channel posts its request.
    await laptop.waitForTimeout(6_000);
    await expect(request('elsewhere-image')).toHaveCount(0);
    await expect(request('elsewhere-fetch')).toHaveCount(0);
  } finally {
    await context.close();
  }
});
