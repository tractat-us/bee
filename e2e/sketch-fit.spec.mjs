// A Sketch frame fits its document's height, within a floor and a cap, and a fit can't slide a
// Send under a click (bee#60). It runs its own, unseeded host (host.mjs), so the page is shorter
// than the window and a frame's fit moves the cards below it without resizing anything; a real
// bee-mcp bridge (agent.mjs) opens the Sketch surfaces.
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
  host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-sketch-fit-')));
  await host.start();
  agent = await startAgent('e2e-sketcher', { port: host.port });
});

test.afterAll(async () => {
  await agent?.kill();
  if (host?.process) await host.stop();
});

const NO_MARGIN = '<style>body{margin:0}</style>';

async function openSketch(surfaceId, html) {
  await agent.call('bee_surface_open', {
    surfaceId,
    title: surfaceId,
    components: [{ id: 'root', component: 'Sketch', html, title: surfaceId }],
  });
}

const iframe = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"] bee-sketch iframe`);
const frame = (page, id) => page.frameLocator(`a2ui-surface[data-surface-id="${id}"] bee-sketch iframe`);
const send = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"] bee-sketch .bee-proposal-send`);
const records = (page, id) => page.locator(`#records li[data-surface-id="${id}"]`);
/** The frame's inner height: what its document gets. */
const inner = (page, id) => iframe(page, id).evaluate((f) => f.clientHeight);

async function open(page, name) {
  await page.goto(`${host.url}/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
}

/** Proposes [choice] from inside sketch [id], as a click on a `[data-choice]` would. */
const propose = (page, id, choice) => frame(page, id).locator('body').evaluate((body, c) => parent.postMessage({
  jsonrpc: '2.0', id: 41, method: 'ui/message',
  params: { role: 'user', content: { type: 'text', text: c }, _meta: { 'us.tractat.bee/choice': c } },
}, '*'), choice);

/** Sets the height of `#box` inside sketch [id]. */
const resize = (page, id, px) => frame(page, id).locator('#box').evaluate((box, h) => { box.style.height = `${h}px`; }, px);

const under = (page, at) => page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.className, at);

test('a short sketch gets a short frame, one that grows gets a taller one, and a tall one is capped', async ({ page }) => {
  await openSketch('fit-short', '<p>One line.</p>');
  await openSketch('fit-mid', `${NO_MARGIN}<div id="box" style="height:360px">A 360px diagram</div>`);
  await openSketch('fit-tall', `${NO_MARGIN}<div style="height:3000px">A very tall one</div>`);
  await open(page, 'alex');
  await expect(frame(page, 'fit-tall').locator('body')).toContainText('very tall', { timeout: 15_000 });

  // The floor: one line of text gets the shortest frame, not the cap's dead space.
  await expect.poll(() => inner(page, 'fit-short'), { timeout: 5_000 }).toBeLessThanOrEqual(120);
  // A document 360px tall gets exactly 360px, with nothing to scroll.
  await expect.poll(() => inner(page, 'fit-mid'), { timeout: 5_000 }).toBe(360);
  // It follows its document as it grows.
  await resize(page, 'fit-mid', 420);
  await expect.poll(() => inner(page, 'fit-mid'), { timeout: 5_000 }).toBe(420);
  // The cap: min(80vh, 720px), less the frame's border; past it the frame scrolls.
  const cap = await page.evaluate(() => Math.min(innerHeight * 0.8, 720));
  await expect.poll(() => iframe(page, 'fit-tall').evaluate((f) => f.getBoundingClientRect().height), { timeout: 5_000 })
    .toBeCloseTo(cap, 0);

  for (const surfaceId of ['fit-short', 'fit-mid', 'fit-tall']) await agent.call('bee_surface_close', { surfaceId });
});

// A sketch above another can move that one's armed Send away from the pointer and back again
// by fitting its frame, and on a page shorter than the window neither move resizes anything a
// ResizeObserver watches. A click as Send returns records nothing; once Send has stayed put, the
// same click sends.
test('a sketch that fits its frame to slide another sketch\'s Send back under the pointer records nothing', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1600 });
  await openSketch('mover', `${NO_MARGIN}<div id="box" style="height:150px">The mover</div>`);
  await openSketch('target', `${NO_MARGIN}<div style="height:150px">The target</div>`);
  await open(page, 'max');
  await expect(frame(page, 'target').locator('body')).toContainText('The target', { timeout: 15_000 });
  await expect.poll(() => inner(page, 'mover'), { timeout: 5_000 }).toBe(150);
  // Precondition: the page is shorter than the window, so a fit resizes neither the body nor the board.
  expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight)).toBe(true);

  await propose(page, 'target', 'slid');
  await expect(send(page, 'target')).toBeEnabled();
  const box = await send(page, 'target').boundingBox();
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  await page.mouse.move(at.x, at.y);
  expect(await under(page, at)).toBe('bee-proposal-send');

  // The mover grows, and Send slides away from the pointer.
  await resize(page, 'mover', 450);
  await expect.poll(() => under(page, at), { timeout: 5_000 }).not.toBe('bee-proposal-send');
  // The mover shrinks back, and the click lands the moment Send is under the pointer again.
  await resize(page, 'mover', 150);
  await expect.poll(() => under(page, at), { timeout: 5_000, intervals: [5] }).toBe('bee-proposal-send');
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  await expect(records(page, 'target')).toHaveCount(0);

  // Control: once Send has stayed put and the pointer has really moved on it, a click sends.
  await page.mouse.move(at.x + 1, at.y);
  await expect(send(page, 'target')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'target')).toHaveCount(1, { timeout: 10_000 });

  for (const surfaceId of ['mover', 'target']) await agent.call('bee_surface_close', { surfaceId });
});

// A sketch can learn where a resting pointer is (a frame grown over it hears `pointerover` with
// its coordinates, in Chromium), then fit its frame so its own Send lands there, and wait out the
// arming. A moving frame under a still pointer delivers only boundary events, never a
// `pointermove`, so Send stays disarmed until the pointer really moves on it: the click the
// person meant for whatever was there records nothing.
test('a sketch that fits its own Send under a resting pointer records nothing until the pointer moves', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1600 });
  await openSketch('lure', `${NO_MARGIN}<div id="box" style="height:150px">The lure</div>`);
  await openSketch('below', `${NO_MARGIN}<div style="height:300px">What the person meant to click</div>`);
  await open(page, 'sam');
  await expect(frame(page, 'below').locator('body')).toContainText('meant to click', { timeout: 15_000 });
  await expect.poll(() => inner(page, 'lure'), { timeout: 5_000 }).toBe(150);

  await propose(page, 'lure', 'lured');
  await expect(send(page, 'lure')).toBeEnabled();
  const box = await send(page, 'lure').boundingBox();
  // The pointer rests 100px below Send, on something else.
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 + 100 };
  await page.mouse.move(at.x, at.y);
  expect(await under(page, at)).not.toBe('bee-proposal-send');

  // The sketch grows by 100px, and its Send lands under the resting pointer.
  await resize(page, 'lure', 250);
  await expect.poll(() => under(page, at), { timeout: 5_000 }).toBe('bee-proposal-send');
  await page.waitForTimeout(600);
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  await expect(records(page, 'lure')).toHaveCount(0);

  // Control: a real move on Send arms it, and the same click sends.
  await page.mouse.move(at.x + 1, at.y);
  await expect(send(page, 'lure')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'lure')).toHaveCount(1, { timeout: 10_000 });

  for (const surfaceId of ['lure', 'below']) await agent.call('bee_surface_close', { surfaceId });
});
