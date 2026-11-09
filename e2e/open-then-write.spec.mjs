// Scratch repro: an agent opens a surface and writes data to it at once, while two pages are
// already open. Does either page stop?
import { test, expect } from '@playwright/test';
import { startAgent } from './agent.mjs';

const SEEN = { timeout: 15_000 };

async function openPage(browser, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'error') console.log(`[${name}] ${m.text()}`); });
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, SEEN);
  return { page, context };
}

test('a surface opened then written at once shows on pages already open', async ({ browser }) => {
  test.setTimeout(90_000);
  const agent = await startAgent('open-then-write');
  const a = await openPage(browser, 'sam');
  const b = await openPage(browser, 'kim');
  const id = `otw-${Date.now()}`;
  try {
    await agent.call('bee_surface_open', {
      surfaceId: id,
      title: 'Open then write',
      components: [{ id: 'root', component: 'Text', text: { path: '/note' } }],
    });
    await agent.call('bee_surface_update', { surfaceId: id, data: { '/note': 'written' } });
    for (const { page } of [a, b]) {
      await expect(page.locator(`a2ui-surface[data-surface-id="${id}"]`)).toContainText('written', SEEN);
      await expect(page.locator('#status')).toHaveText(/^online/);
    }
  } finally {
    for (const { page, context } of [a, b]) {
      const stopped = await page.locator('#status').textContent();
      if (stopped === 'stopped') console.log(`stopped ${await page.evaluate(() => location.search)}: ${await page.locator('[role=alert]').allTextContents()}`);
      await context.close();
    }
    await agent.call('bee_surface_close', { surfaceId: id }).catch(() => {});
    await agent.kill();
  }
});

test('a poll opened then answered at once from one page shows on another', async ({ browser }) => {
  test.setTimeout(90_000);
  const agent = await startAgent('open-then-answer');
  const a = await openPage(browser, 'sam');
  const b = await openPage(browser, 'kim');
  const id = `ota-${Date.now()}`;
  try {
    await agent.call('bee_surface_open', {
      surfaceId: id,
      title: 'Open then answer',
      components: [{ id: 'root', component: 'Poll', question: { path: '/question' }, options: { path: '/options' }, answers: { path: '/answers' } }],
      append: { '/question': ['Which?'], '/options': ['SSO', 'Passkeys'] },
    });
    const option = (page, label) => page.locator(`a2ui-surface[data-surface-id="${id}"] bee-poll button`, { hasText: label });
    await option(a.page, 'SSO').click(SEEN);
    for (const { page } of [a, b]) {
      await expect(page.locator(`a2ui-surface[data-surface-id="${id}"] bee-poll .total`)).toHaveText('1 answer', SEEN);
      await expect(page.locator('#status')).toHaveText(/^online/);
    }
  } finally {
    for (const { page, context } of [a, b]) {
      const stopped = await page.locator('#status').textContent();
      if (stopped === 'stopped') console.log(`stopped ${await page.evaluate(() => location.search)}: ${await page.locator('[role=alert]').allTextContents()}`);
      await context.close();
    }
    await agent.call('bee_surface_close', { surfaceId: id }).catch(() => {});
    await agent.kill();
  }
});
