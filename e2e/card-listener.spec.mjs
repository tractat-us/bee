// A card says whether the session behind it will hear an answer. An agent opener reads `listening`
// while its bridge is in `bee_wait_action` and `working` otherwise. Once it has left, the card's
// actions show muted and a line says answers wait for another session, but they stay clickable: a
// record is addressed to its surface, and any session may wait on any surface (spec §5.2).
// The agent is a real bee-mcp bridge (`:bee-mcp:installDist`), driven over stdio (agent.mjs).
import { test, expect } from '@playwright/test';
import { startAgent } from './agent.mjs';

const SURFACE = 'card-listener-poll';
const NAME = 'card-listener';

/** Presence reaches the page on its next board change or 2 s tick (BoardChrome.TICK). */
const SEEN = { timeout: 10_000 };

test.afterAll(async () => {
  // The bridge that opened the surface has left, so another session closes it.
  const janitor = await startAgent('card-listener-janitor');
  try {
    await janitor.call('bee_surface_close', { surfaceId: SURFACE }).catch(() => {});
  } finally {
    await janitor.end();
  }
});

test('an agent card reads listening while its bridge waits, and mutes its actions once the bridge has left', async ({ page }) => {
  test.setTimeout(90_000);
  const agent = await startAgent(NAME);
  try {
    await agent.call('bee_surface_open', {
      surfaceId: SURFACE,
      title: 'Which first?',
      components: [{ id: 'root', component: 'Poll', question: { path: '/question' }, options: { path: '/options' }, answers: { path: '/answers' } }],
      data: { answers: {} },
      append: { '/question': ['Which first?'], '/options': ['SSO', 'Passkeys'] },
    });
    // The bridge departs itself as left on its claims link, so it must be online before it ends.
    await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');

    await page.goto('/?name=alex');
    await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    const card = page.locator(`#board article.bee-card[data-surface-id="${SURFACE}"]`);
    const state = card.locator('.bee-card-state');
    const option = (label) => card.locator('bee-poll button', { hasText: label });

    await expect(state).toHaveText('working', SEEN);
    await expect(state).toHaveAttribute('data-state', 'working');

    const waited = agent.call('bee_wait_action', { surfaceId: SURFACE, timeoutSeconds: 30 });
    await expect(state).toHaveText('listening', SEEN);
    await expect(state).toHaveAttribute('data-state', 'listening');
    await expect(card).not.toHaveAttribute('data-unheard');
    await expect(card.locator('.bee-card-unheard')).toHaveCount(0);
    await expect(option('SSO')).toHaveCSS('opacity', '1');

    // An answer while it listens reaches it. Marked done, so its leaving orphans nothing.
    await option('SSO').click();
    const { record } = await waited;
    expect(record?.name, "the bridge's wait returned alex's answer").toBe('answer');
    await agent.call('bee_action_done', { recordId: record.recordId, token: record.token });
    await expect(state).toHaveText('working', SEEN);

    await agent.end('stdin');
    await expect(card).toHaveAttribute('data-unheard', '', SEEN);
    await expect(card.locator('.bee-card-gone')).toHaveText('left');
    await expect(card.locator('.bee-card-state')).toHaveCount(0);
    await expect(card.locator('.bee-card-unheard')).toHaveText(`${NAME} left — answers here wait for another session`);
    // Muted, never hidden: the question and options still show, and an answer still lands on the board.
    await expect(card.locator('bee-poll .question')).toHaveText('Which first?');
    await expect(option('Passkeys')).toHaveCSS('opacity', '0.5');
    await expect(option('Passkeys')).toBeEnabled();
    await option('Passkeys').click();
    await expect(option('Passkeys')).toHaveAttribute('aria-pressed', 'true', SEEN);
  } finally {
    await agent.kill();
  }
});
