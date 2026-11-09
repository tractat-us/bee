// A hosted board, as `bee-host --public-url` runs it behind Fly.io's proxy (bee#145), with an
// obviously fake owner key in BEE_OWNER_KEY. Being on loopback grants nothing there, and the join
// link admits no one, so every page here holds the owner key or one pairing gave it.
//
// The public name is `bee-hosted.test`, which Chromium resolves to 127.0.0.1: a page on
// `localhost` takes itself for the owner's own machine and dials with no key, which a hosted board
// refuses, so a test proxy on a loopback name can't stand in for one. Each device's context sends
// the X-Forwarded-For a proxy would append, so pairing limits each by its own address.
//
// Iain's flow, end to end: the owner opens the board by the owner link, a teammate opens the bare
// URL and shows a code, the owner types it and presses Admit, and the teammate's Bring your Claude
// line carries the teammate's own key.
import { test, expect } from '@playwright/test';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Host, freePort } from './host.mjs';

const HOSTED_NAME = 'bee-hosted.test';
const OWNER_KEY = 'fake-owner-key-for-e2e-only-00000000000000000';
test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${HOSTED_NAME} 127.0.0.1`] } });

/** @type {Host} */
let host;
let origin;
let contexts = [];

test.beforeAll(async () => {
  const port = await freePort();
  origin = `http://${HOSTED_NAME}:${port}`;
  host = new Host(port, mkdtempSync(join(tmpdir(), 'bee-hosted-')), ['--public-url', `${origin}/`], { BEE_OWNER_KEY: OWNER_KEY });
  await host.start();
});

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

test.afterAll(async () => {
  await host?.stop();
  expect(host.log.join(''), 'no key in the host log').not.toContain(OWNER_KEY);
});

/** A device of its own, at its own address as the proxy names it. */
async function device(browser, address) {
  const context = await browser.newContext({ extraHTTPHeaders: { 'X-Forwarded-For': address } });
  contexts.push(context);
  return context.newPage();
}

async function boarded(page) {
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

/** The key the host keeps for its join link, which a hosted board never admits. */
const joinKey = () => readFileSync(join(host.dataDir, 'keys'), 'utf8').match(/^join (\S+)$/m)[1];

/** The `k` and `name` a Bring your Claude line carries. */
async function lineOf(page) {
  await page.locator('#bar #bring-claude-toggle').click();
  const text = await page.locator('#bring-claude-command').textContent();
  const url = new URL(text.match(/'([^']+)'/)[1]);
  return { text, url, key: url.searchParams.get('k'), name: url.searchParams.get('name') };
}

/** Types [joiner]'s code into its join request on [admitter]'s board, and admits it. */
async function admit(admitter, joiner) {
  const code = joiner.locator('#pairing-code');
  await expect(code).toHaveText(/^[0-9]{6}$/, { timeout: 15_000 });
  const request = admitter.locator('a2ui-surface[data-surface-id^="bee-pair-"]');
  await request.locator('a2ui-basic-textfield input').fill(await code.textContent());
  await request.getByRole('button', { name: 'Admit' }).click();
  await boarded(joiner);
}

// First, while no page has been on this board: nobody's presence can linger from an earlier test.
test('with nobody on the board, a device asking to join is refused at once', async ({ browser }) => {
  const andrew = await device(browser, '203.0.113.20');
  await andrew.goto(`${origin}/?name=andrew`);
  await expect(andrew.locator('#pairing-prompt')).toHaveText('Nobody is here to let you in. Try again when someone is on the board.', { timeout: 15_000 });
  await expect(andrew.locator('#pairing-code')).toHaveText('');
  await expect(andrew.locator('#status')).toHaveCount(0);
});

test('the owner opens the board by the key in the fragment, which never leaves the browser', async ({ browser }) => {
  const owner = await device(browser, '203.0.113.10');
  const documents = [];
  owner.on('request', (request) => {
    if (new URL(request.url()).pathname === '/') documents.push(request.url());
  });
  await owner.goto(`${origin}/?name=iain#k=${OWNER_KEY}`);
  await boarded(owner);

  expect(documents.length, 'rig: the page load was seen').toBeGreaterThan(0);
  // The page load itself carries no key: a browser never sends a fragment, and the page's first act is to drop it.
  for (const url of documents) expect(url).not.toContain(OWNER_KEY);
  expect(owner.url()).toBe(`${origin}/?name=iain`);

  // The tab's own entry was rewritten: going back to it lands on no key. (The browser's global
  // history may still hold the URL as typed; replaceState doesn't reach it.)
  await owner.goto('about:blank');
  await owner.goBack();
  expect(owner.url()).not.toContain(OWNER_KEY);
  await boarded(owner);
});

test('the owner admits a teammate by code, and the teammate brings their Claude with their own key', async ({ browser }) => {
  const owner = await device(browser, '203.0.113.10');
  await owner.goto(`${origin}/?name=iain#k=${OWNER_KEY}`);
  await boarded(owner);

  // The owner's invite is the bare public URL, which works from anywhere.
  await owner.locator('#bar #invite-toggle').click();
  await expect(owner.locator('#invite-link')).toHaveText(`${origin}/`);
  await expect(owner.locator('#invite')).toContainText('Scan to join on a phone or laptop.');
  await expect(owner.locator('#invite')).not.toContainText('on this network');

  const andrew = await device(browser, '203.0.113.21');
  await andrew.goto(`${origin}/?name=andrew`);
  await admit(owner, andrew);
  await expect(andrew.locator('#status')).toHaveText('online as human:andrew');

  const line = await lineOf(andrew);
  expect(line.text).toContain('claude mcp add --scope user --transport http bee');
  expect(line.url.origin + line.url.pathname).toBe(`${origin}/mcp`);
  expect(line.name).toBe('andrew');
  expect(line.key).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(line.key).not.toBe(OWNER_KEY);
  expect(line.key).not.toBe(joinKey());
  await expect(andrew.locator('#bring-claude')).toContainText("This line holds your key: don't share it.");
  await expect(andrew.locator('#bring-claude-unnamed')).toHaveCount(0);

  // Any admitted person can admit anyone: Andrew gets Invite too.
  await andrew.locator('#bar #invite-toggle').click();
  await expect(andrew.locator('#invite-link')).toHaveText(`${origin}/`);
});

test('a teammate opened with no name gets the placeholder in their line', async ({ browser }) => {
  const owner = await device(browser, '203.0.113.10');
  await owner.goto(`${origin}/?name=iain#k=${OWNER_KEY}`);
  await boarded(owner);

  const guest = await device(browser, '203.0.113.22');
  await guest.goto(`${origin}/`);
  await admit(owner, guest);

  const line = await lineOf(guest);
  expect(line.name).toBe('you');
  expect(line.key).not.toBe(OWNER_KEY);
  await expect(guest.locator('#bring-claude-unnamed')).toContainText('put your own name there');
});
