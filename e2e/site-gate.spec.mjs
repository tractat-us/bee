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
