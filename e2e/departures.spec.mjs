// How a departed session reads on the page (spec §6.4). A session that ends normally, by its stdin
// closing or by SIGTERM, departs itself as left: muted, not struck through, not red. A SIGKILLed
// one says no goodbye, so the host departs it as crashed after the learner grace.
// The agents are real bee-mcp bridges (`:bee-mcp:installDist`), driven over stdio (agent.mjs).
import { test, expect } from '@playwright/test';
import { startAgent } from './agent.mjs';

/** Each agent's surface: one card apiece, so each card's opener line shows how it went. */
const surfaceOf = (name) => `departures-${name}`;

test.describe.configure({ mode: 'serial' });

/** The agents by how they end, and a janitor that closes their surfaces afterwards. */
const rig = {};

test.beforeAll(async () => {
  test.setTimeout(90_000);
  for (const name of ['closes-stdin', 'gets-sigterm', 'gets-sigkill']) {
    const agent = await startAgent(name);
    rig[name] = agent;
    await agent.call('bee_surface_open', {
      surfaceId: surfaceOf(name),
      title: `Opened by ${name}`,
      components: [{ id: 'root', component: 'Text', text: `${name} drew this.` }],
    });
    // The bridge departs itself on its claims link, and the host departs a crashed one by the
    // node its presence names, so each must be online before it ends.
    await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');
  }
});

test.afterAll(async () => {
  const janitor = await startAgent('departures-janitor');
  try {
    for (const name of ['closes-stdin', 'gets-sigterm', 'gets-sigkill']) {
      await janitor.call('bee_surface_close', { surfaceId: surfaceOf(name) }).catch(() => {});
    }
  } finally {
    for (const name of ['closes-stdin', 'gets-sigterm', 'gets-sigkill']) await rig[name]?.kill();
    await janitor.end();
  }
});

async function open(page) {
  await page.goto('/?name=alex');
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

const peer = (page, who) => page.locator(`#peers li.bee-peer[data-peer="${who}"]`);
const card = (page, name) => page.locator(`#board article.bee-card[data-surface-id="${surfaceOf(name)}"]`);

/** Expects `name`'s strip entry and card to read `reason`, styled neutrally or as an alarm. */
async function expectDeparted(page, name, reason, { alarm, timeout }) {
  const who = rig[name].identity;
  await expect(peer(page, who)).toHaveAttribute('data-reason', reason, { timeout });
  await expect(peer(page, who)).toHaveAttribute('data-state', 'departed');
  await expect(peer(page, who).locator('.bee-peer-state')).toHaveText(reason);
  await expect(peer(page, who).locator('.bee-peer-name')).toHaveCSS('text-decoration-line', alarm ? 'line-through' : 'none');
  const gone = card(page, name).locator('.bee-card-gone');
  await expect(gone).toHaveText(reason);
  await expect(gone).toHaveAttribute('data-reason', reason);
  // A neutral departure keeps the meta line's muted colour; an alarm overrides it.
  const [goneColour, metaColour] = await Promise.all([
    gone.evaluate((e) => getComputedStyle(e).color),
    card(page, name).locator('.bee-card-meta').evaluate((e) => getComputedStyle(e).color),
  ]);
  if (alarm) expect(goneColour).not.toBe(metaColour);
  else expect(goneColour).toBe(metaColour);
}

test('a session whose stdin closes reads as left, not crashed', async ({ page }) => {
  await open(page);
  await rig['closes-stdin'].end('stdin');
  // Well inside the 30 s learner grace: the left is the bridge's own, not the host's verdict.
  await expectDeparted(page, 'closes-stdin', 'left', { alarm: false, timeout: 10_000 });
});

test('a session ended with SIGTERM reads as left, not crashed', async ({ page }) => {
  await open(page);
  await rig['gets-sigterm'].end('SIGTERM');
  await expectDeparted(page, 'gets-sigterm', 'left', { alarm: false, timeout: 10_000 });
});

test('a SIGKILLed session still reads as crashed', async ({ page }) => {
  test.setTimeout(120_000);
  await open(page);
  await rig['gets-sigkill'].kill();
  // The voter drops its node after the 30 s learner grace; then the host departs it.
  await expectDeparted(page, 'gets-sigkill', 'crashed', { alarm: true, timeout: 90_000 });
});
