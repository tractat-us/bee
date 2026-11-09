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
  // The name a screen reader hears is unchanged, and the button stays focusable.
  await expect(button).toHaveAccessibleName('Yes, file it');
  await expect(button).toBeEnabled();

  await expect(records(page, id)).toHaveCount(1, { timeout: 5_000 });
  // A second record would land within a moment of the first; give it one.
  await page.waitForTimeout(1_000);
  await button.click();
  await page.waitForTimeout(500);
  await expect(records(page, id)).toHaveCount(1);
});

test('the sent look clears once an agent marks the record done, and the button presses again', async ({ page }) => {
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
  // Claimed is not handled: it still reads sent while the agent works on it.
  await page.waitForTimeout(1_000);
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
  await agent.call('bee_surface_update', { surfaceId: id, data: { note: "That didn't work: try again." } });
  await expect(surface(page, id)).toContainText("That didn't work", { timeout: 10_000 });
  await expect(button).not.toHaveAttribute('aria-disabled', 'true');
  await button.click();
  await expect(records(page, id)).toHaveCount(2, { timeout: 5_000 });
});
