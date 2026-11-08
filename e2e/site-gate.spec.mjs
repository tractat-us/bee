// Only the board's own page may open the board (bee#45).
//
// A page from another site can run `new WebSocket("ws://127.0.0.1:<port>/ws/board")` in a browser
// on the laptop. Loopback needs no key, so the host tells that page from its own by the `Origin` the
// browser sends. Here the other site is another port on 127.0.0.1, fulfilled by Playwright rather
// than served: the closest a site can get to the host's own origin, and no LAN prompt in the way.
import { test, expect } from '@playwright/test';

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

test('a page from another origin cannot open the board socket', async ({ page }) => {
  const board = `ws://127.0.0.1:${port}/ws/board?peer=site-gate`;

  // The control: the board's own page opens the same socket.
  await page.goto('/');
  expect(await dial(page, board)).toBe('open');

  const elsewhere = `http://127.0.0.1:${port + 1}/`;
  await page.route(elsewhere, (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>elsewhere</title>' }));
  await page.goto(elsewhere);
  expect(await dial(page, board)).toBe('refused');
});
