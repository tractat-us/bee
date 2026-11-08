// The page reads as a shared board: a card per surface with its title and opener, and a strip of
// who is attached. The agent here is a real bee-mcp bridge (`:bee-mcp:installDist`), the one a
// Claude session runs, driven over its stdio MCP transport (mcp-bridge.mjs).
import { test, expect } from '@playwright/test';
import { startBridge } from './mcp-bridge.mjs';

const WORKING_ON = 'drawing the board page';
const SURFACE = 'board-page-card';
const TITLE = 'Pick a lunch spot';
const CLAIM = `card:${SURFACE}/tacos`;

let agent;
let identity;

test.beforeAll(async () => {
  agent = await startBridge(process.env.BEE_E2E_PORT, 'e2e-agent');
  ({ as: identity } = await agent.call('bee_presence', { status: WORKING_ON }));
  await agent.call('bee_surface_open', {
    surfaceId: SURFACE,
    title: TITLE,
    components: [
      { id: 'root', component: 'Column', children: ['ask', 'unsafe'] },
      { id: 'ask', component: 'Text', text: 'Tacos or ramen? Pick **one**.' },
      { id: 'unsafe', component: 'Text', text: 'Raw HTML stays out: <img src=x onerror="window.__beeOwned = 1">' },
    ],
  });
});

test.afterAll(async () => {
  // Closed and released, so no later spec sees this surface, and the bridge's exit orphans nothing.
  await agent?.call('bee_release', { target: CLAIM }).catch(() => {});
  await agent?.call('bee_surface_close', { surfaceId: SURFACE }).catch(() => {});
  await agent?.kill();
});

async function open(page, name) {
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

const peer = (page, who) => page.locator(`#peers li.bee-peer[data-peer="${who}"]`);
const card = (page) => page.locator(`#board article.bee-card[data-surface-id="${SURFACE}"]`);

test('the strip shows an attached agent with its working-on line, and the person', async ({ page }) => {
  await open(page, 'alex');
  await expect(peer(page, identity)).toHaveAttribute('data-state', 'acting', { timeout: 10_000 });
  await expect(peer(page, identity).locator('.bee-peer-name')).toContainText('e2e-agent');
  await expect(peer(page, identity).locator('.bee-peer-status')).toHaveText(WORKING_ON);
  await expect(peer(page, 'human:alex')).toHaveAttribute('data-state', 'here', { timeout: 10_000 });
});

test('a card shows its title, its opener and what the opener is working on, around its surface', async ({ page }) => {
  await open(page, 'alex');
  await expect(card(page).locator('.bee-card-title')).toHaveText(TITLE);
  await expect(card(page).locator('.bee-card-opener')).toHaveAttribute('data-peer', identity);
  await expect(card(page).locator('.bee-card-working')).toHaveText(WORKING_ON, { timeout: 10_000 });
  await expect(card(page).locator(`a2ui-surface[data-surface-id="${SURFACE}"]`)).toBeVisible();
  // Body text is Markdown, sanitized: bold renders, and raw HTML never becomes an element.
  await expect(card(page).locator('a2ui-basic-text strong')).toHaveText('one');
  await expect(card(page).locator('img')).toHaveCount(0);
  expect(await page.evaluate(() => window.__beeOwned)).toBeUndefined();
});

test('a claimed card shows who holds it', async ({ page }) => {
  await open(page, 'alex');
  await expect(card(page)).toBeVisible();
  await expect(card(page).locator('.bee-badge')).toHaveCount(0);
  expect((await agent.call('bee_claim', { target: CLAIM })).outcome).toBe('won');
  const badge = card(page).locator(`.bee-badge[data-claim-target="${CLAIM}"]`);
  await expect(badge).toHaveAttribute('data-claim-status', 'held', { timeout: 10_000 });
  await expect(badge).toHaveText('claimed by e2e-agent');
});

test('the debug lines sit under a collapsed Details, off the board', async ({ page }) => {
  await open(page, 'alex');
  await expect(page.locator('#details')).not.toHaveAttribute('open');
  await expect(page.locator('#details #records')).toBeHidden();
  await expect(page.locator('#details #store')).toBeHidden();
  await expect(page.locator('#bar #status')).toBeVisible();
});
