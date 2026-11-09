// "Bring your Claude": the bar's control opens a panel with the line that points a viewer's own
// Claude session at this board. A keyed viewer gets this tab's key and their name, for every
// folder; the owner, on loopback, gets the kit's bridge line and a URL for one folder only. Each
// also gets a line for a Codex session.
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

const folderCommand = (page) => page.locator('#bring-claude-folder-command');
const codexCommand = (page) => page.locator('#bring-claude-codex-command');

// A teammate's line: bee for every folder on their machine.
const line = (origin, query) => `claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee '${origin}/mcp?${query}'`;
// The owner's: the kit's bridge, as bee-up prints it, then a URL for one folder only.
const KIT_LINE = "claude --mcp-config ~/.bee/mcp.json --allowedTools 'mcp__bee__*'";
const folderLine = (origin, query) => `claude mcp add --transport http bee '${origin}/mcp?${query}'`;
// A Codex session's: user-wide for a teammate, one run only for the owner.
const codexLine = (origin, query) => `codex mcp add bee --url '${origin}/mcp?${query}'`;
const ownerCodexLine = (origin, query) =>
  `codex -c 'mcp_servers.bee.url="${origin}/mcp?${query}"' -c 'mcp_servers.bee.default_tools_approval_mode="approve"'`;

async function openBoard(page, url) {
  await page.goto(url);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

test('the owner on loopback gets the kit line first and a one-folder URL, each copied by its own button', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await openBoard(page, '/?name=alex');
  await expect(panel(page)).toBeHidden();

  await toggle(page).click();
  const folder = folderLine(`http://127.0.0.1:${port}`, 'name=alex');
  await expect(panel(page)).toBeVisible();
  await expect(command(page)).toHaveText(KIT_LINE);
  await expect(folderCommand(page)).toHaveText(folder);
  await expect(codexCommand(page)).toHaveText(ownerCodexLine(`http://127.0.0.1:${port}`, 'name=alex'));
  await expect(panel(page)).toContainText('the line bee-up printed');
  await expect(panel(page)).toContainText('/mcp__bee__whiteboard <topic>');
  await expect(panel(page)).not.toContainText('--scope user');
  await expect(page.locator('#bring-claude-unnamed')).toHaveCount(0);

  // "Copied." shows under the Claude lines, above the Codex one.
  expect(await page.evaluate(() => document.querySelector('#bring-claude-folder-line').nextElementSibling.id)).toBe('bring-claude-copied');
  await page.locator('#bring-claude-copy').click();
  await expect(page.locator('#bring-claude-copied')).toHaveText('Copied.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(KIT_LINE);

  await page.locator('#bring-claude-folder-copy').click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(folder);

  await page.locator('#bring-claude-codex-copy').click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(ownerCodexLine(`http://127.0.0.1:${port}`, 'name=alex'));
});

test('a keyed LAN viewer gets its key in the line, and only there', async ({ page }) => {
  await openBoard(page, `http://${LAN_NAME}:${port}/?name=sam&k=e2e-lan-key`);
  // The key left the address bar as the page loaded; the line still has it.
  expect(page.url()).not.toContain('e2e-lan-key');

  await toggle(page).click();
  const expected = line(`http://${LAN_NAME}:${port}`, 'k=e2e-lan-key&name=sam');
  await expect(command(page)).toHaveText(expected);
  await expect(folderCommand(page)).toHaveCount(0);
  await expect(codexCommand(page)).toHaveText(codexLine(`http://${LAN_NAME}:${port}`, 'k=e2e-lan-key&name=sam'));
  expect(await page.evaluate(() => window.isSecureContext)).toBe(false);

  expect(await page.evaluate(() => document.querySelector('#bring-claude-line').nextElementSibling.id)).toBe('bring-claude-copied');
  // No async clipboard here: Copy selects the line, copies it the old way if it can, and says which.
  await page.locator('#bring-claude-copy').click();
  await expect(page.locator('#bring-claude-copied')).toHaveText(/^(Copied\.|Selected: press (⌘C|Ctrl\+C) to copy\.)$/);
  expect(await page.evaluate(() => window.getSelection().toString())).toBe(expected);

  // The key is in the panel's two lines, Claude's and Codex's, and nowhere else on the page.
  const html = await page.content();
  expect(html.split('e2e-lan-key').length - 1).toBe(2);
  expect((await panel(page).innerHTML()).split('e2e-lan-key').length - 1).toBe(2);
});

test('an unnamed viewer gets the placeholder and is told so, and the panel closes', async ({ page }) => {
  await openBoard(page, '/');
  await toggle(page).click();
  await expect(folderCommand(page)).toHaveText(folderLine(`http://127.0.0.1:${port}`, 'name=you'));
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
