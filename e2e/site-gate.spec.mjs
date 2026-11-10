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

// bee#54: an `<img>` sends no `Origin`, so only its Fetch Metadata tells the host another site
// sent it, and the host's 403 for it is the gate's. A `GET` of `/pair` opens nothing (bee#211), so
// the image's empty board would hold even if the gate let it by. A no-cors `POST`, which would
// open a pairing, sends its `Origin`, so its 403 is the `Origin` check's; `SiteGateTest` pins a
// `POST` refused on Fetch Metadata alone. Chromium may block another origin's answer itself
// (ORB), where Safari doesn't, so the host's 403 is what this test reads first, and the empty
// board second.
test('a page from another origin cannot open a pairing', async ({ browser, page }) => {
  const { context, request } = await watchBoard(browser);
  try {
    /**
     * Runs `load` on `page` with `/pair` for `screen`, and returns the host's status for it, or the
     * browser's error if the request failed: Chromium's ORB fails an admitted cross-origin answer.
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
    // Opens a pairing as the board's page does, and keeps asking after it past the dwell (bee#211).
    const asThePage = (url) => {
      fetch(url, { method: 'POST' })
        .then((r) => r.json())
        .then(({ token }) => (window.polling = setInterval(() => fetch('/pair/poll', { method: 'POST', body: token }), 1_000)));
    };
    const asNoCorsPost = (url) => {
      fetch(url, { method: 'POST', mode: 'no-cors' });
    };

    // The control: the board's own page opens a pairing the same way, and its request reaches the board.
    await page.goto('/');
    expect(await status('own-page', asThePage)).toBe(200);
    await expect(request('own-page')).toHaveCount(1, { timeout: 15_000 });

    await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
    expect(await status('elsewhere-image', asImage)).toBe(403);
    expect(await status('elsewhere-post', asNoCorsPost)).toBe(403);
    // Well past the dwell, after which an admitted pairing posts its request.
    await page.waitForTimeout(PAST_THE_DWELL_MS);
    await expect(request('elsewhere-image')).toHaveCount(0);
    await expect(request('elsewhere-post')).toHaveCount(0);
  } finally {
    await context.close();
  }
});

// bee#55's review: no legitimate caller navigates to `/pair`, so the host takes no navigation
// there, only the page's own requests. Another origin's page that sets `location.href` to it
// is a navigation from another site. One that lists it in speculation rules gets a prefetch
// Chromium marks `Sec-Fetch-Site: none`, as if typed, so only the path tells it from a real one.
test('a page from another origin cannot navigate to or prefetch a pairing channel', async ({ browser, page }) => {
  const { context, request } = await watchBoard(browser);
  try {
    await page.goto(`http://127.0.0.1:${elsewhere.address().port}/`);

    // The browser reports each prefetch's outcome over CDP, which is what says the host answered
    // it: no request on the board alone can't tell a refused prefetch from one never sent. Under
    // the old rule, headless Chromium reports `PrefetchFailedNetError` and drops the stream before
    // the dwell, so here the board stays empty either way and only the outcome reads the host.
    const preloading = await page.context().newCDPSession(page);
    const prefetches = [];
    preloading.on('Preload.prefetchStatusUpdated', (event) => prefetches.push(event));
    await preloading.send('Preload.enable');
    const prefetched = pair('elsewhere-prefetch');
    await page.evaluate((url) => {
      const rules = Object.assign(document.createElement('script'), { type: 'speculationrules' });
      rules.textContent = JSON.stringify({ prefetch: [{ source: 'list', urls: [url] }] });
      document.head.append(rules);
    }, prefetched);
    // Soft, so a host that admits both shows each one, and the board, in one run.
    const soft = expect.configure({ soft: true });
    await soft
      .poll(() => prefetches.findLast((e) => e.prefetchUrl === prefetched && e.status === 'Failure')?.prefetchStatus, {
        message: "the prefetch's failure, as Chromium reports it",
        timeout: 15_000,
      })
      .toBe('PrefetchFailedNon2XX');

    const navigated = pair('elsewhere-navigation');
    const answered = page.waitForResponse((r) => r.url() === navigated);
    await page.evaluate((url) => {
      location.href = url;
    }, navigated);
    soft((await answered).status(), 'the navigation').toBe(403);

    await page.waitForTimeout(PAST_THE_DWELL_MS);
    await soft(request('elsewhere-prefetch')).toHaveCount(0);
    await soft(request('elsewhere-navigation')).toHaveCount(0);
  } finally {
    await context.close();
  }
});

/** The 3 s dwell, after which an admitted pairing posts its join request, and then some. */
const PAST_THE_DWELL_MS = 7_000;

/** `/pair` on the host, for a room screen named `screen`. */
const pair = (screen) => `http://127.0.0.1:${port}/pair?screen=${screen}`;

/**
 * Opens the board on the laptop in a context of its own, and returns it with `request(screen)`:
 * the join request a pairing for `screen` posted to the board, if any.
 */
async function watchBoard(browser) {
  const context = await browser.newContext();
  const laptop = await context.newPage();
  await laptop.goto('/?name=iain');
  await expect(laptop.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  const request = (screen) =>
    laptop.locator('a2ui-surface[data-surface-id^="bee-pair-"]').filter({ hasText: `"${screen}"` });
  return { context, request };
}

// A prerender of the board's own page carries `Sec-Purpose`. Admitted, Chrome would run the page,
// get a 403 on its scripts and sockets, then activate it blank. Refused, Chrome drops the prerender
// and navigates normally, so the board comes up online.
test('a prerender of the board page activates online, not blank', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(() => {
    const rules = document.createElement('script');
    rules.type = 'speculationrules';
    rules.textContent = JSON.stringify({ prerender: [{ source: 'list', urls: ['/?name=x'] }] });
    document.head.append(rules);
  });
  await page.waitForTimeout(1_000);
  await page.evaluate(() => {
    window.location.href = '/?name=x';
  });
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
});
