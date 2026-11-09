// "Bring your Claude": the bar's control opens a panel with the one line that points a viewer's own
// Claude session at this board, with this tab's key and the viewer's name.
//
// The keyed viewer is reached by a LAN name, `bee-lan.test`, which Chromium resolves to 127.0.0.1
// (as in second-screen.spec.mjs): the page takes itself for a LAN device holding a join link's key,
// and, being plain HTTP off loopback, has no async clipboard. The host sees 127.0.0.1 and asks for
// no key, so any key will do here.
import { test, expect } from '@playwright/test';

const LAN_NAME = 'bee-lan.test';
const port = process.env.BEE_E2E_PORT;
test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${LAN_NAME} 127.0.0.1`] } });

const toggle = (page) => page.locator('#bar #bring-claude-toggle');
const panel = (page) => page.locator('#bring-claude');
const command = (page) => page.locator('#bring-claude-command');

const line = (origin, query) => `claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee '${origin}/mcp?${query}'`;

async function openBoard(page, url) {
  await page.goto(url);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

test('a loopback viewer gets a line with no key, and Copy puts it on the clipboard', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await openBoard(page, '/?name=alex');
  await expect(panel(page)).toBeHidden();

  await toggle(page).click();
  const expected = line(`http://127.0.0.1:${port}`, 'name=alex');
  await expect(panel(page)).toBeVisible();
  await expect(command(page)).toHaveText(expected);
  await expect(panel(page)).toContainText('/mcp__bee__whiteboard <topic>');
  await expect(page.locator('#bring-claude-unnamed')).toHaveCount(0);

  await page.locator('#bring-claude-copy').click();
  await expect(page.locator('#bring-claude-copied')).toHaveText('Copied.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected);
});

test('a keyed LAN viewer gets its key in the line, and only there', async ({ page }) => {
  await openBoard(page, `http://${LAN_NAME}:${port}/?name=sam&k=e2e-lan-key`);
  // The key left the address bar as the page loaded; the line still has it.
  expect(page.url()).not.toContain('e2e-lan-key');

  await toggle(page).click();
  const expected = line(`http://${LAN_NAME}:${port}`, 'k=e2e-lan-key&name=sam');
  await expect(command(page)).toHaveText(expected);
  expect(await page.evaluate(() => window.isSecureContext)).toBe(false);

  // No async clipboard here: Copy selects the line, copies it the old way if it can, and says which.
  await page.locator('#bring-claude-copy').click();
  await expect(page.locator('#bring-claude-copied')).toHaveText(/^(Copied\.|Selected: press (⌘C|Ctrl\+C) to copy\.)$/);
  expect(await page.evaluate(() => window.getSelection().toString())).toBe(expected);

  // The key is in the panel's text and nowhere else on the page.
  const html = await page.content();
  expect(html.split('e2e-lan-key').length - 1).toBe(1);
});

test('an unnamed viewer gets the placeholder and is told so, and the panel closes', async ({ page }) => {
  await openBoard(page, '/');
  await toggle(page).click();
  await expect(command(page)).toHaveText(line(`http://127.0.0.1:${port}`, 'name=you'));
  await expect(page.locator('#bring-claude-unnamed')).toContainText('put your own name there');

  await page.keyboard.press('Escape');
  await expect(panel(page)).toBeHidden();
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
  await toggle(page).click();
  await page.locator('#bring-claude-close').click();
  await expect(panel(page)).toBeHidden();
});

test('a room screen offers no line', async ({ page }) => {
  await openBoard(page, '/?screen=e2e-room');
  await expect(toggle(page)).toHaveCount(0);
  await expect(panel(page)).toHaveCount(0);
});
