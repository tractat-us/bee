// A pressed Button shows that it was sent, at once, on the page that pressed it, with no agent
// answering (bee#213). It reads `aria-disabled` with its text unchanged, refuses a second press
// while its record waits, and is pressable again once the record is handled (`bee_action_done`)
// or the surface changes under it. Each test writes records, so the spec runs its own host
// (host.mjs), and a real bee-mcp bridge (agent.mjs) opens the surfaces and handles the records.
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startAgent } from './agent.mjs';
import { Host, freePort } from './host.mjs';

test.describe.configure({ mode: 'serial' });

let host;
let agent;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-button-sent-')));
  await host.start();
  agent = await startAgent('e2e-button', { port: host.port });
  // bee_wait_action claims through the claims link, so it must be up before a test waits.
  await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');
});

test.afterAll(async () => {
  await agent?.kill();
  if (host?.process) await host.stop();
});

const surface = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"]`);
const fileIt = (page, id) => surface(page, id).getByRole('button', { name: 'Yes, file it' });
const records = (page, id) => page.locator(`#records li[data-surface-id="${id}"]`);

/** A surface with one Button, and a caption at /note an agent can change without touching the button. */
async function openAsk(surfaceId) {
  await agent.call('bee_surface_open', {
    surfaceId,
    title: 'File it?',
    components: [
      { id: 'root', component: 'Column', children: ['file', 'note'] },
      { id: 'file', component: 'Button', child: 'file-label', action: { event: { name: 'file' } } },
      { id: 'file-label', component: 'Text', text: 'Yes, file it' },
      { id: 'note', component: 'Text', text: { path: '/note' } },
    ],
    data: { note: 'waiting' },
  });
}

async function open(page, name) {
  await page.goto(`${host.url}/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

test('a pressed button reads sent at once, with no agent answering, and a double click writes one record', async ({ page }) => {
  const id = 'button-sent-at-once';
  await openAsk(id);
  await open(page, 'alex');
  const button = fileIt(page, id);
  await expect(button).toBeVisible({ timeout: 10_000 });
  await expect(button).not.toHaveAttribute('aria-disabled', 'true');

  // Nobody waits on this surface: whatever the button shows, the page showed it by itself.
  await button.dblclick();
  await expect(button).toHaveAttribute('aria-disabled', 'true', { timeout: 2_000 });
  // The name a screen reader hears is unchanged, and the button stays focusable: aria-disabled,
  // not disabled (as Sketch's Send, bee#193). Playwright reads aria-disabled as disabled.
  await expect(button).toHaveAccessibleName('Yes, file it');
  await expect(button).toBeDisabled();
  expect(await button.evaluate((b) => b.disabled)).toBe(false);
  await button.focus();
  await expect(button).toBeFocused();

  await expect(records(page, id)).toHaveCount(1, { timeout: 5_000 });
  // A second record would land within a moment of the first; give it one. Then press it again,
  // by mouse and key: force, since Playwright won't click what reads disabled.
  await page.waitForTimeout(1_000);
  await button.click({ force: true });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  await expect(records(page, id)).toHaveCount(1);
});

test('the sent look holds while an agent works on the press, and clears once it marks the record done', async ({ page }) => {
  const id = 'button-sent-handled';
  await openAsk(id);
  await open(page, 'alex');
  const button = fileIt(page, id);
  await expect(button).toBeVisible({ timeout: 10_000 });

  const waited = agent.call('bee_wait_action', { surfaceId: id, timeoutSeconds: 30 });
  await button.click();
  await expect(button).toHaveAttribute('aria-disabled', 'true', { timeout: 2_000 });
  const { record } = await waited;
  expect(record?.name, "the agent's wait returned alex's press").toBe('file');
  // Claimed is not handled: once the page shows the agent holding the record, the button still reads sent.
  const badge = page.locator(`#board article.bee-card[data-surface-id="${id}"] .bee-badge[data-claim-target="record:${record.recordId}"]`);
  await expect(badge).toHaveAttribute('data-claim-status', 'held', { timeout: 10_000 });
  await expect(button).toHaveAttribute('aria-disabled', 'true');
  // The agent writes to the card while it works, as a check-in card's /now: the press still reads sent.
  await agent.call('bee_surface_update', { surfaceId: id, data: { '/note': 'filing it now' } });
  await expect(surface(page, id)).toContainText('filing it now', { timeout: 10_000 });
  await expect(button).toHaveAttribute('aria-disabled', 'true');

  await agent.call('bee_action_done', { recordId: record.recordId, token: record.token });
  await expect(button).not.toHaveAttribute('aria-disabled', 'true', { timeout: 10_000 });
  await button.click();
  await expect(records(page, id)).toHaveCount(2, { timeout: 5_000 });
});

test('the sent look clears when the surface changes under the button, unhandled', async ({ page }) => {
  const id = 'button-sent-changed';
  await openAsk(id);
  await open(page, 'alex');
  const button = fileIt(page, id);
  await expect(button).toBeVisible({ timeout: 10_000 });

  await button.click();
  await expect(button).toHaveAttribute('aria-disabled', 'true', { timeout: 2_000 });
  // The way the host answers a wrong pairing code: new data on the surface, the record never done.
  await agent.call('bee_surface_update', { surfaceId: id, data: { '/note': "That didn't work: try again." } });
  await expect(surface(page, id)).toContainText("That didn't work", { timeout: 10_000 });
  await expect(button).not.toHaveAttribute('aria-disabled', 'true');
  await button.click();
  await expect(records(page, id)).toHaveCount(2, { timeout: 5_000 });
});

test('each row of a templated list is its own button: pressing one leaves the others pressable', async ({ page }) => {
  const id = 'button-sent-rows';
  await agent.call('bee_surface_open', {
    surfaceId: id,
    title: 'Approve which?',
    components: [
      { id: 'root', component: 'Column', children: { componentId: 'row', path: '/items' } },
      { id: 'row', component: 'Button', child: 'row-label', action: { event: { name: 'approve', context: { item: { path: 'name' } } } } },
      { id: 'row-label', component: 'Text', text: { path: 'name' } },
    ],
    data: { items: [{ name: 'Alpha' }, { name: 'Beta' }] },
  });
  await open(page, 'alex');
  const row = (name) => surface(page, id).getByRole('button', { name });
  await expect(row('Alpha')).toBeVisible({ timeout: 10_000 });
  await expect(row('Beta')).toBeVisible();

  await row('Alpha').dblclick();
  await expect(row('Alpha')).toHaveAttribute('aria-disabled', 'true', { timeout: 2_000 });
  await expect(records(page, id)).toHaveCount(1, { timeout: 5_000 });
  await expect(row('Beta')).not.toHaveAttribute('aria-disabled', 'true');

  await row('Beta').click();
  await expect(row('Beta')).toHaveAttribute('aria-disabled', 'true', { timeout: 2_000 });
  await expect(records(page, id)).toHaveCount(2, { timeout: 5_000 });
  // A second record for either row would land within a moment; give it one.
  await page.waitForTimeout(500);
  await expect(records(page, id)).toHaveCount(2);
});
