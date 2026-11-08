// Orphan decisions from the page (spec §6.4, plan Task 11). Two real bridges, zed and ada, join
// the host (agent.mjs). zed opens a surface and waits on it; iain's click on it becomes zed's
// record, and zed is killed holding it. After the learner grace the host departs zed, which
// orphans the claim, and opens its decision: a `Poll` offering ada.
import { test, expect } from '@playwright/test';
import { startAgent } from './agent.mjs';

const SOURCE = 'orphan-source';

test.describe.configure({ mode: 'serial' });

/** Everything the two tests share: the bridges, iain's laptop page, and the orphan zed left. */
const rig = {};

test.beforeAll(async ({ browser }) => {
  test.setTimeout(150_000);
  const context = await browser.newContext();
  const laptop = await context.newPage();
  await laptop.goto('/?name=iain');
  await expect(laptop.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });

  const zed = await startAgent('zed');
  const ada = await startAgent('ada');
  await zed.call('bee_surface_open', {
    surfaceId: SOURCE,
    title: 'Orphan source',
    components: [
      { id: 'root', component: 'Button', child: 'label', action: { event: { name: 'press' } } },
      { id: 'label', component: 'Text', text: 'Press' },
    ],
  });
  const waiting = zed.call('bee_wait_action', { surfaceId: SOURCE, timeoutSeconds: 30 });
  await laptop.locator(`a2ui-surface[data-surface-id="${SOURCE}"]`).getByRole('button', { name: 'Press' }).click();
  const held = await waiting;
  expect(held.record?.token, "rig: zed's wait returned iain's press, held under a token").toMatch(/^\d+:\d+$/);
  zed.kill();

  const decision = `orphan-record:${held.record.recordId}`;
  // The voter drops zed's node after the 30 s learner grace; then the host departs zed.
  await expect(laptop.locator(`a2ui-surface[data-surface-id="${decision}"] bee-poll`)).toBeVisible({ timeout: 90_000 });
  Object.assign(rig, { context, laptop, zed, ada, held: held.record, decision });
});

test.afterAll(async () => {
  rig.ada?.kill();
  rig.zed?.kill();
  await rig.context?.close();
});

test("a screen's answer to an orphan decision is refused and records nothing", async ({ browser }) => {
  const context = await browser.newContext();
  try {
    const screen = await context.newPage();
    await screen.goto('/?screen=wall');
    await expect(screen.locator('#status')).toHaveText('online as human:wall', { timeout: 15_000 });
    const poll = screen.locator(`a2ui-surface[data-surface-id="${rig.decision}"] bee-poll`);
    await poll.getByRole('button', { name: 'Release it' }).click();

    await expect(screen.locator('#problem')).toHaveText(/A room screen can't answer/, { timeout: 5_000 });
    // Long enough for a record, had one been written, to reach the laptop.
    await screen.waitForTimeout(2_000);
    await expect(rig.laptop.locator(`#records li[data-surface-id="${rig.decision}"]`)).toHaveCount(0);
    const listed = await rig.ada.call('bee_list_actions', { surfaceId: rig.decision });
    expect(listed.records).toEqual([]);
  } finally {
    await context.close();
  }
});

test("a person's Reassign reaches the agent's wait", async () => {
  // ada's wait is open first, as an agent's loop leaves one: it passes zed's orphaned record over,
  // then takes it once the Reassign commits.
  const waiting = rig.ada.call('bee_wait_action', { surfaceId: SOURCE, timeoutSeconds: 60 });
  const poll = rig.laptop.locator(`a2ui-surface[data-surface-id="${rig.decision}"] bee-poll`);
  await poll.getByRole('button', { name: `Reassign to ${rig.ada.identity}` }).click();

  const got = await waiting;
  expect(got.timedOut).toBe(false);
  expect(got.record?.recordId).toBe(rig.held.recordId);
  expect(got.record?.token).toMatch(/^\d+:\d+$/);
  expect(got.record?.token, "the reassign's token, not zed's").not.toBe(rig.held.token);
  await expect(rig.laptop.locator(`#records li[data-surface-id="${rig.decision}"]`)).toHaveCount(1);
});
