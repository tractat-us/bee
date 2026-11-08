// The page reads as a shared board: a card per surface with its title and opener, and a strip of
// who is attached. The agent here is a real bee-mcp bridge (`:bee-mcp:installDist`), the one a
// Claude session runs, driven over its stdio MCP transport (mcp-bridge.mjs).
import { test, expect } from '@playwright/test';
import { startBridge } from './mcp-bridge.mjs';

const WORKING_ON = 'drawing the board page';
const SURFACE = 'board-page-card';
const TITLE = 'Pick a lunch spot';
const CLAIM = `card:${SURFACE}/tacos`;
// Opened before FIELD (ids tie-break the same way), so reopening it puts its card ahead of FIELD's.
const OLDER = 'board-page-a-older';
const FIELD = 'board-page-b-field';

let agent;
let identity;
/** The token of the claim on CLAIM, once won, for afterAll to release it under. */
let token;

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
  // bee_claim answers `unavailable` until the bridge's claims link is up.
  await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');
});

test.afterAll(async () => {
  // Released under its token, so the bridge's exit orphans nothing, and closed, so no later spec
  // sees these surfaces. A release without the token frees only an orphaned target.
  try {
    if (token) expect((await agent.call('bee_release', { target: CLAIM, token })).released).toBe(true);
  } finally {
    for (const surfaceId of [SURFACE, OLDER, FIELD]) await agent?.call('bee_surface_close', { surfaceId }).catch(() => {});
    await agent?.kill();
  }
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
  const claim = await agent.call('bee_claim', { target: CLAIM });
  expect(claim.outcome).toBe('won');
  token = claim.token;
  const badge = card(page).locator(`.bee-badge[data-claim-target="${CLAIM}"]`);
  await expect(badge).toHaveAttribute('data-claim-status', 'held', { timeout: 10_000 });
  await expect(badge).toHaveText('claimed by e2e-agent');
});

test('a focused field keeps its focus when a card is reopened from history ahead of it', async ({ browser, page }) => {
  await agent.call('bee_surface_open', { surfaceId: OLDER, title: 'Older', components: [{ id: 'root', component: 'Text', text: 'older' }] });
  await agent.call('bee_surface_open', {
    surfaceId: FIELD,
    title: 'Field',
    components: [{ id: 'root', component: 'TextField', label: 'Note', value: { path: '/note' } }],
    data: { note: '' },
  });
  await agent.call('bee_surface_close', { surfaceId: OLDER });
  await open(page, 'alex');
  const field = page.locator(`a2ui-surface[data-surface-id="${FIELD}"] a2ui-basic-textfield input`);
  await field.focus();
  await field.pressSequentially('half a thought');

  // Another person reopens OLDER: a click on this page's history strip would take the focus itself.
  const other = await browser.newContext();
  try {
    const sam = await other.newPage();
    await open(sam, 'sam');
    await sam.locator('#history summary').click();
    await sam.locator(`#history button[data-surface-id="${OLDER}"]`).click();

    const older = page.locator(`#board article.bee-card[data-surface-id="${OLDER}"]`);
    await expect(older.locator('.bee-card-title')).toHaveText('Older', { timeout: 10_000 });
    // In the board's order, by style, so no card was moved.
    const position = (id) => page.locator(`#board article.bee-card[data-surface-id="${id}"]`).evaluate((e) => Number(e.style.order));
    expect(await position(OLDER)).toBeLessThan(await position(FIELD));
    await expect(field).toBeFocused();
    await expect(field).toHaveValue('half a thought');
  } finally {
    await other.close();
  }
});

test('the debug lines sit under a collapsed Details, off the board', async ({ page }) => {
  await open(page, 'alex');
  await expect(page.locator('#details')).not.toHaveAttribute('open');
  await expect(page.locator('#details #records')).toBeHidden();
  await expect(page.locator('#details #store')).toBeHidden();
  await expect(page.locator('#bar #status')).toBeVisible();
});
