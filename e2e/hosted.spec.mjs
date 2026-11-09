// A hosted board, as `bee-host --public-url` runs it behind Fly.io's proxy (bee#145), with an
// obviously fake owner key in BEE_OWNER_KEY. Being on loopback grants nothing there, and the join
// link admits no one, so every page here holds the owner key or one pairing or an invite gave it.
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
/** Every invite secret a test minted, which the host log must never hold. */
const secrets = [];

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
  for (const secret of secrets) expect(host.log.join(''), 'no invite secret in the host log').not.toContain(secret);
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

/** A minted invite: the public URL with a 256-bit secret in its fragment (bee#215). */
const INVITE_LINK = () => new RegExp(`^${origin.replace(/[.]/g, '\\.')}/#i=[A-Za-z0-9_-]{43}$`);

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
  const alex = await device(browser, '203.0.113.20');
  await alex.goto(`${origin}/?name=alex`);
  await expect(alex.locator('#pairing-prompt')).toHaveText('Nobody is here to let you in. Try again when someone is on the board.', { timeout: 15_000 });
  await expect(alex.locator('#pairing-code')).toHaveText('');
  await expect(alex.locator('#status')).toHaveCount(0);
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

  // The owner's invite is a minted link, which works from anywhere (bee#215).
  await owner.locator('#bar #invite-toggle').click();
  await expect(owner.locator('#invite-link')).toHaveText(INVITE_LINK());
  await expect(owner.locator('#invite')).toContainText('Scan to join on a phone or laptop.');
  await expect(owner.locator('#invite')).not.toContainText('on this network');

  const alex = await device(browser, '203.0.113.21');
  await alex.goto(`${origin}/?name=alex`);
  await admit(owner, alex);
  await expect(alex.locator('#status')).toHaveText('online as human:alex');

  const line = await lineOf(alex);
  expect(line.text).toContain('claude mcp add --scope user --transport http bee');
  expect(line.url.origin + line.url.pathname).toBe(`${origin}/mcp`);
  expect(line.name).toBe('alex');
  expect(line.key).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(line.key).not.toBe(OWNER_KEY);
  expect(line.key).not.toBe(joinKey());
  await expect(alex.locator('#bring-claude')).toContainText("This line holds your key: don't share it.");
  await expect(alex.locator('#bring-claude-unnamed')).toHaveCount(0);

  // Any admitted person can admit anyone: Alex gets Invite too.
  await alex.locator('#bar #invite-toggle').click();
  await expect(alex.locator('#invite-link')).toHaveText(INVITE_LINK());
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

/** Opens [owner]'s Invite and reads the link it minted, with the secret in its fragment. */
async function invited(owner) {
  await owner.locator('#bar #invite-toggle').click();
  const link = owner.locator('#invite-link');
  await expect(link).toHaveText(INVITE_LINK());
  const text = await link.textContent();
  await expect(owner.locator('#invite-qr')).toHaveAttribute('data-text', text);
  await expect(owner.locator('#invite')).toContainText('Anyone with this link can join, for a day.');
  const secret = text.split('#i=')[1];
  secrets.push(secret);
  return { link: text, secret };
}

// bee#215: an invite admits whoever opens it, with nobody typing a code, and asks their name.
test('an invite link admits whoever opens it, under the name they give, and is reusable', async ({ browser }) => {
  const owner = await device(browser, '203.0.113.10');
  await owner.goto(`${origin}/?name=morgan#k=${OWNER_KEY}`);
  await boarded(owner);
  const { link, secret } = await invited(owner);

  // A fresh browser, with ?name= to fill the prompt in.
  const sam = await device(browser, '203.0.113.24');
  const requests = [];
  sam.on('request', (request) => requests.push({ url: request.url(), body: request.postData() ?? '' }));
  await sam.goto(`${origin}/?name=sam#i=${secret}`);
  await expect(sam.locator('#join-name')).toHaveValue('sam');
  expect(sam.url(), 'the secret leaves the address bar at once').toBe(`${origin}/?name=sam`);
  await sam.locator('#join-submit').click();
  await boarded(sam);
  await expect(sam.locator('#status')).toHaveText('online as human:sam');
  await expect(owner.locator('#peers')).toContainText('sam');

  expect(requests.length, "rig: the page's requests were seen").toBeGreaterThan(0);
  expect(requests.filter((r) => r.url.includes(secret)), 'no URL carries the secret').toEqual([]);
  const carriers = requests.filter((r) => r.body.includes(secret)).map((r) => new URL(r.url).pathname);
  expect(carriers, 'only the redemption carries it, in its body').toEqual(['/invite/redeem']);

  const line = await lineOf(sam);
  expect(line.name).toBe('sam');
  expect(line.key).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(line.key).not.toBe(OWNER_KEY);

  // The same link, opened bare by someone else, who types a name.
  const robin = await device(browser, '203.0.113.25');
  await robin.goto(link);
  await expect(robin.locator('#join-name')).toHaveValue('');
  await robin.locator('#join-name').fill('robin');
  await robin.locator('#join-name').press('Enter');
  await boarded(robin);
  await expect(robin.locator('#status')).toHaveText('online as human:robin');
  expect((await lineOf(robin)).key).not.toBe(line.key);
});

test('an invite the board never made says so, and offers pairing by code instead', async ({ browser }) => {
  const owner = await device(browser, '203.0.113.10');
  await owner.goto(`${origin}/?name=morgan#k=${OWNER_KEY}`);
  await boarded(owner);

  const kim = await device(browser, '203.0.113.26');
  await kim.goto(`${origin}/#i=${'A'.repeat(43)}`);
  await kim.locator('#join-name').fill('kim');
  await kim.locator('#join-submit').click();
  await expect(kim.locator('#join-problem')).toHaveText("This invite isn't one this board made.");
  await kim.locator('#join-pair').click();
  await admit(owner, kim);
  await expect(kim.locator('#status')).toHaveText('online as human:kim');
});
