// An empty board says what it is and how to start, and the hint gives way to the first surface.
// The shared host is seeded, so this spec runs its own, unseeded host (host.mjs), and a real
// bee-mcp bridge (agent.mjs) opens and closes the surface on it.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startAgent } from './agent.mjs';
import { Host, freePort } from './host.mjs';

// A closed surface stays on the board as history, and opening its id again is refused, so each test
// opens its own.
const FIRST = 'empty-board-first';
const SECOND = 'empty-board-second';

test.describe.configure({ mode: 'serial' });

let host;
let agent;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-empty-board-')));
  await host.start();
  agent = await startAgent('e2e-empty', { port: host.port });
});

test.afterAll(async () => {
  await agent?.kill();
  if (host?.process) await host.stop();
});

const hint = (page) => page.locator('#board .bee-empty-hint');
const card = (page, id) => page.locator(`#board article.bee-card[data-surface-id="${id}"]`);

async function openSurface(surfaceId, text) {
  await agent.call('bee_surface_open', {
    surfaceId,
    title: 'First',
    components: [{ id: 'root', component: 'Text', text }],
  });
}

test('an empty board shows how to start, and the first surface replaces the hint', async ({ page }) => {
  await page.goto(`${host.url}/?name=alex`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  await expect(hint(page)).toBeVisible();
  await expect(hint(page)).toContainText('Nothing on the board yet.');
  await expect(hint(page).locator('code')).toHaveText('/mcp__bee__whiteboard <topic>');
  // The hint offers the bar's "Bring your Claude" panel too.
  await hint(page).locator('.bring-claude-open').click();
  await expect(page.locator('#bring-claude')).toBeVisible();
  await expect(page.locator('#bring-claude-folder-command')).toContainText('/mcp?name=alex');
  await page.locator('#bring-claude-close').click();

  await openSurface(FIRST, 'Hello, board.');
  await expect(card(page, FIRST)).toBeVisible({ timeout: 10_000 });
  await expect(hint(page)).toBeHidden();

  await agent.call('bee_surface_close', { surfaceId: FIRST });
  await expect(card(page, FIRST)).toHaveCount(0, { timeout: 10_000 });
  await expect(hint(page)).toBeVisible();
});

test('a page that opens on a board with a surface never shows the hint', async ({ page }) => {
  await openSurface(SECOND, 'Still here.');
  // Count every frame the hint is shown in, from before the page's first script runs, so a flash
  // while the page catches up with its host fails the test too.
  await page.addInitScript(() => {
    window.__beeHintShown = 0;
    new MutationObserver(() => {
      const hint = document.querySelector('#board .bee-empty-hint');
      if (hint && !hint.hidden) window.__beeHintShown++;
    }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['hidden'] });
  });
  await page.goto(`${host.url}/?name=sam`);
  await expect(card(page, SECOND)).toBeVisible({ timeout: 15_000 });
  await expect(hint(page)).toBeHidden();
  expect(await page.evaluate(() => window.__beeHintShown)).toBe(0);
  await agent.call('bee_surface_close', { surfaceId: SECOND });
});
