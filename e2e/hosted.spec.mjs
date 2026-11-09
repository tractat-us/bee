// A hosted board, as `bee-host --public-url` runs it behind Fly.io's proxy (bee#145), with an
// obviously fake owner key in BEE_OWNER_KEY. Being on loopback grants nothing there, so every page
// here holds a key or pairs.
//
// The public name is `bee-hosted.test`, which Chromium resolves to 127.0.0.1: a page on
// `localhost` takes itself for the owner's own machine and dials with no key, which a hosted board
// refuses, so a test proxy on a loopback name can't stand in for one.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Host, freePort } from './host.mjs';

const HOSTED_NAME = 'bee-hosted.test';
const OWNER_KEY = 'fake-owner-key-for-e2e-only-00000000000000000';
test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${HOSTED_NAME} 127.0.0.1`] } });

/** @type {Host} */
let host;
let origin;

test.beforeAll(async () => {
  const port = await freePort();
  origin = `http://${HOSTED_NAME}:${port}`;
  host = new Host(port, mkdtempSync(join(tmpdir(), 'bee-hosted-')), ['--public-url', `${origin}/`], { BEE_OWNER_KEY: OWNER_KEY });
  await host.start();
});

test.afterAll(async () => {
  await host?.stop();
  expect(host.log.join(''), 'no key in the host log').not.toContain(OWNER_KEY);
});

async function boarded(page) {
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

/** Every URL the page asks for, documents and sockets alike, from now on. */
function requests(page) {
  const seen = [];
  page.on('request', (request) => seen.push(request.url()));
  page.on('websocket', (socket) => seen.push(socket.url()));
  return seen;
}

test('the owner opens the board by the key in the fragment, which never leaves the browser', async ({ page }) => {
  const seen = requests(page);
  await page.goto(`${origin}/?name=iain#k=${OWNER_KEY}`);
  await boarded(page);

  const documents = seen.filter((url) => new URL(url).pathname === '/');
  expect(documents.length, 'rig: the page load was seen').toBeGreaterThan(0);
  // The page load itself carries no key: a browser never sends a fragment, and the page's first act is to drop it.
  for (const url of documents) expect(url).not.toContain(OWNER_KEY);
  expect(page.url()).not.toContain(OWNER_KEY);
  expect(page.url()).toBe(`${origin}/?name=iain`);

  // The tab's own entry was rewritten: going back to it lands on no key. (The browser's global
  // history may still hold the URL as typed; replaceState doesn't reach it.)
  await page.goto('about:blank');
  await page.goBack();
  expect(page.url()).not.toContain(OWNER_KEY);
  await boarded(page);
});

test('the owner gets Invite, and Bring your Claude carries the owner key for every folder', async ({ page }) => {
  await page.goto(`${origin}/?name=iain#k=${OWNER_KEY}`);
  await boarded(page);
  await page.locator('#bar #invite-toggle').click();
  await expect(page.locator('#invite-link')).toHaveText(`${origin}/`);

  await page.locator('#bar #bring-claude-toggle').click();
  await expect(page.locator('#bring-claude-command')).toHaveText(
    `claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee '${origin}/mcp?k=${OWNER_KEY}&name=iain'`,
  );
});

test('a page with no key gets nothing from loopback: it pairs', async ({ page }) => {
  await page.goto(`${origin}/`);
  await expect(page.locator('#pairing-code')).toHaveText(/^[0-9]{6}$/, { timeout: 15_000 });
  await expect(page.locator('#bar #invite-toggle')).toHaveCount(0);
});
