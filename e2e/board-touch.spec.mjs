// Moving a Board card from a phone (tractat-us/bee#128): a tap on a card shows the other columns
// as buttons, and choosing one is the same `drop` a mouse drag dispatches. The phone is Playwright's
// iPhone 13 (touch, mobile viewport) in whichever engine the project runs. Uses the `board-tap`
// fixture (NotionsFixtures.kt) so notions.spec.mjs's boards stay as that spec expects them.
import { test, expect, devices } from '@playwright/test';

const SURFACE = 'board-tap';
const { defaultBrowserType, ...iPhone } = devices['iPhone 13'];

/** Opens `/` as `name` in a fresh context made from `options`, online. */
async function open(browser, name, options = {}) {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return { context, page };
}

const board = (page) => page.locator(`a2ui-surface[data-surface-id="${SURFACE}"] bee-board`);
const inColumn = (page, column, card) => board(page).locator(`[data-column="${column}"] [data-card="${card}"]`);
const records = (page) => page.locator(`#records li[data-surface-id="${SURFACE}"]`);

let contexts = [];

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

test.describe.configure({ mode: 'serial' });

test('tapping a card on a phone moves it in every browser', async ({ browser }) => {
  const phone = await open(browser, 'alex', iPhone);
  const desk = await open(browser, 'sam');
  contexts = [phone.context, desk.context];
  await expect(inColumn(desk.page, 'todo', 'c1')).toBeVisible();

  await inColumn(phone.page, 'todo', 'c1').tap();
  const menu = board(phone.page).locator('[data-card="c1"] .move');
  await expect(menu.locator('button')).toHaveText(['Move to Doing', 'Move to Done']);
  // A finger's worth: Apple's 44px minimum.
  for (const button of await menu.locator('button').all()) {
    expect((await button.boundingBox()).height).toBeGreaterThanOrEqual(44);
  }
  await menu.locator('button[data-move="doing"]').tap();

  for (const { page } of [phone, desk]) {
    await expect(inColumn(page, 'doing', 'c1')).toBeVisible({ timeout: 5_000 });
    await expect(inColumn(page, 'todo', 'c1')).toHaveCount(0);
    await expect(records(page).last()).toContainText('human:alex drop on root');
  }
  await expect(board(phone.page).locator('.move')).toHaveCount(0);
});

test('a tap elsewhere closes the menu and moves nothing', async ({ browser }) => {
  const phone = await open(browser, 'alex', iPhone);
  contexts = [phone.context];

  await inColumn(phone.page, 'todo', 'c2').tap();
  await expect(board(phone.page).locator('.move')).toHaveCount(1);
  await board(phone.page).locator('[data-column="done"] h3').tap();

  await expect(board(phone.page).locator('.move')).toHaveCount(0);
  await phone.page.waitForTimeout(500);
  await expect(inColumn(phone.page, 'todo', 'c2')).toBeVisible();
});

test('a mouse drag moves the card and opens no menu', async ({ browser }) => {
  const desk = await open(browser, 'sam');
  contexts = [desk.context];

  await inColumn(desk.page, 'todo', 'c2').dragTo(board(desk.page).locator('[data-column="done"]'));

  await expect(inColumn(desk.page, 'done', 'c2')).toBeVisible({ timeout: 5_000 });
  await desk.page.waitForTimeout(500);
  await expect(board(desk.page).locator('.move')).toHaveCount(0);
});
