// "Invite": on the board owner's own page, a control that shows the host's join link as a QR code.
// Only a host started with --lan has a link to give, and it gives it only to its own machine, so
// these tests start one of their own beside the shared host, which isn't on the network.
//
// The keyed viewer is reached by a LAN name, `bee-lan.test`, which Chromium resolves to 127.0.0.1
// (as in bring-your-claude.spec.mjs): the page takes itself for a LAN device holding a join link's
// key. The host sees 127.0.0.1 and asks for no key, so any key will do here.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Host, freePort } from './host.mjs';

const LAN_NAME = 'bee-lan.test';
test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${LAN_NAME} 127.0.0.1`] } });

const toggle = (page) => page.locator('#bar #invite-toggle');
const panel = (page) => page.locator('#invite');

/** @type {Host} */
let host;

test.beforeAll(async () => {
  host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-invite-')), ['--lan', '--host-name', LAN_NAME]);
  await host.start();
});

test.afterAll(async () => {
  await host?.stop();
});

async function openBoard(page, url) {
  await page.goto(url);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

/** Every request the page makes to the invite route, from now on. */
function inviteRequests(page) {
  const seen = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/invite') seen.push(request.url());
  });
  return seen;
}

test('the owner on loopback gets Invite: a QR code of the join link, the link, and Copy', async ({ page, context, request }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const links = await (await request.get(`${host.url}/invite`)).json();
  const link = links.numeric[0] ?? links.named;
  expect(link).toMatch(new RegExp(`^http://[^/]+:${host.port}/\\?k=[A-Za-z0-9_-]+$`));

  await openBoard(page, `${host.url}/?name=iain`);
  await expect(panel(page)).toBeHidden();
  await toggle(page).click();
  await expect(panel(page)).toBeVisible();
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#invite-link')).toHaveText(link);
  await expect(panel(page)).toContainText('Scan to join on a phone or laptop on this network.');

  const qr = page.locator('#invite-qr');
  await expect(qr).toHaveAttribute('data-text', link);
  const box = await qr.boundingBox();
  expect(box.width).toBeGreaterThanOrEqual(280);
  expect(box.height).toBe(box.width);
  expect((await qr.locator('path').getAttribute('d')).length).toBeGreaterThan(1000);

  // The host's other links, small: its `.local` name among them, never the one the code carries.
  await expect(page.locator('#invite-others li')).toContainText([links.named]);
  await expect(page.locator('#invite-others')).not.toContainText(link);

  await page.locator('#invite-copy').click();
  await expect(page.locator('#invite-copied')).toHaveText('Copied.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link);

  await page.keyboard.press('Escape');
  await expect(panel(page)).toBeHidden();
  await toggle(page).click();
  await page.locator('#invite-close').click();
  await expect(panel(page)).toBeHidden();
});

// A keyed page asks, since on a hosted board its owner is a keyed page too (bee#145); a LAN host
// refuses it, even from this machine by the LAN name, so its key goes no further.
test('a keyed viewer gets no Invite: the host refuses it the link', async ({ page }) => {
  const refused = page.waitForResponse((response) => new URL(response.url()).pathname === '/invite');
  await openBoard(page, `http://${LAN_NAME}:${host.port}/?name=sam&k=e2e-lan-key`);
  expect((await refused).status()).toBe(403);
  // Bring your Claude is there; Invite is not.
  await expect(page.locator('#bar #bring-claude-toggle')).toBeVisible();
  await expect(toggle(page)).toHaveCount(0);
});

test('a room screen gets no Invite, and never asks for the link', async ({ page }) => {
  const asked = inviteRequests(page);
  await openBoard(page, `${host.url}/?screen=e2e-room`);
  await expect(toggle(page)).toHaveCount(0);
  expect(asked).toEqual([]);
});

test('on a host without --lan the owner gets no Invite', async ({ page }) => {
  // The shared host, Playwright's baseURL, is on loopback alone.
  const answered = page.waitForResponse((response) => new URL(response.url()).pathname === '/invite');
  await openBoard(page, '/?name=alex');
  expect((await answered).status()).toBe(404);
  await expect(toggle(page)).toHaveCount(0);
});

test('a caller off loopback is refused the link, whatever header it sends', async ({ request }) => {
  const links = await (await request.get(`${host.url}/invite`)).json();
  const lan = links.numeric[0];
  test.skip(lan === undefined, 'This runner has no IPv4 address on a network, so no call can reach the host off loopback.');
  // Dialled at the machine's own LAN address, the TCP peer is that address, not loopback: a
  // header claiming loopback must not stand in for it.
  const key = new URL(lan).searchParams.get('k');
  const response = await fetch(new URL('/invite', lan), { headers: { 'X-Forwarded-For': '127.0.0.1' } });
  expect(response.status).toBe(403);
  expect(await response.text()).not.toContain(key);
});
