// A Sketch frame fits its document's height, within a floor and a cap, and a fit can't slide a
// Send under a click (bee#60). It runs its own, unseeded host (host.mjs), so the page is shorter
// than the window and a frame's fit moves the cards below it without resizing anything; a real
// bee-mcp bridge (agent.mjs) opens the Sketch surfaces. The board shows the newest surface on
// top, so a test opens the sketch it wants lowest first.
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

/** Proposes [choice], shown as [text], from inside sketch [id], as a click on a `[data-choice]` would. */
const propose = (page, id, choice, text = choice) => frame(page, id).locator('body').evaluate((body, [c, t]) => parent.postMessage({
  jsonrpc: '2.0', id: 41, method: 'ui/message',
  params: { role: 'user', content: { type: 'text', text: t }, _meta: { 'us.tractat.bee/choice': c } },
}, '*'), [choice, text]);

/** Sets the height of `#box` inside sketch [id]. */
const resize = (page, id, px) => frame(page, id).locator('#box').evaluate((box, h) => { box.style.height = `${h}px`; }, px);

const under = (page, at) => page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.className, at);

/** Moves the pointer off Send, 80px down, and back onto it at [at]: the pointer arriving on Send. */
async function leaveAndReturn(page, at) {
  const off = { x: at.x, y: at.y + 80 };
  expect(await under(page, off)).not.toBe('bee-proposal-send');
  await page.mouse.move(off.x, off.y);
  await page.mouse.move(at.x, at.y);
}

/** Counts, from now on, each time sketch [id]'s Send is disabled; `disabledCount` reads it. */
const countDisables = (page, id) => send(page, id).evaluate((button) => {
  window.beeDisables = 0;
  new MutationObserver(() => { if (button.disabled) window.beeDisables += 1; })
    .observe(button, { attributes: true, attributeFilter: ['disabled'] });
});
const disabledCount = (page) => page.evaluate(() => window.beeDisables);

/** Waits two frames, so the resize observers of any layout change so far have run. */
const settled = (page) => page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));

/** How many times sketch [id]'s frame is refitted in the next second. */
const refits = (page, id) => iframe(page, id).evaluate((f) => new Promise((resolve) => {
  let count = 0;
  const seen = new MutationObserver(() => { count += 1; });
  seen.observe(f, { attributes: true, attributeFilter: ['style'] });
  setTimeout(() => { seen.disconnect(); resolve(count); }, 1_000);
}));

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
  await openSketch('target', `${NO_MARGIN}<div style="height:150px">The target</div>`);
  await openSketch('mover', `${NO_MARGIN}<div id="box" style="height:150px">The mover</div>`);
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

  // Control: once Send has stayed put and the pointer has left it and come back, a click sends.
  await leaveAndReturn(page, at);
  await expect(send(page, 'target')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'target')).toHaveCount(1, { timeout: 10_000 });

  for (const surfaceId of ['mover', 'target']) await agent.call('bee_surface_close', { surfaceId });
});

// A sketch can learn where a resting pointer is (a frame grown over it hears `pointerover` with
// its coordinates, in Chromium), then fit its frame so its own Send lands there, and wait out the
// arming. A moving frame under a still pointer delivers only boundary events, never a
// `pointermove`, so Send stays disarmed until the pointer leaves it: the click the person meant
// for whatever was there records nothing. A resting hand's tremor moves the pointer a pixel or
// two, on Send, and that isn't the pointer arriving there either.
test('a sketch that fits its own Send under a resting pointer records nothing until the pointer leaves and returns', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1600 });
  await openSketch('below', `${NO_MARGIN}<div style="height:300px">What the person meant to click</div>`);
  await openSketch('lure', `${NO_MARGIN}<div id="box" style="height:150px">The lure</div>`);
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

  // A tremor while Send re-arms, and another after it has, then the click: nothing.
  await resize(page, 'lure', 251);
  await expect.poll(() => inner(page, 'lure'), { timeout: 5_000, intervals: [5] }).toBe(251);
  // Precondition: the tremor lands while Send re-arms.
  expect(await send(page, 'lure').isDisabled()).toBe(true);
  await page.mouse.move(at.x + 1, at.y);
  await page.waitForTimeout(600);
  await page.mouse.move(at.x + 2, at.y + 1);
  expect(await under(page, { x: at.x + 2, y: at.y + 1 })).toBe('bee-proposal-send');
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  await expect(records(page, 'lure')).toHaveCount(0);

  // Control: the pointer leaves Send and comes back, and the same click sends.
  await leaveAndReturn(page, at);
  await expect(send(page, 'lure')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'lure')).toHaveCount(1, { timeout: 10_000 });

  for (const surfaceId of ['lure', 'below']) await agent.call('bee_surface_close', { surfaceId });
});

// The companion's frame template pins its root to the frame (`html, body { height: 100%;
// overflow: hidden }`) and scrolls inside `.main`, so its root and body are always the frame's
// height. The frame fits what it holds instead: the header, and what `.main` holds.
test('a screen pinned to its frame, scrolling inside, fits what it holds', async ({ page }) => {
  const pinned = '<style>*{box-sizing:border-box;margin:0;padding:0} html, body { height: 100%; overflow: hidden }' +
    ' body { display: flex; flex-direction: column } .header { height: 42px; flex-shrink: 0 }' +
    ' .main { flex: 1; overflow-y: auto } #frame-content { padding: 2rem; min-height: 100% }</style>' +
    '<div class="header">Header</div><div class="main"><div id="frame-content"><div id="box" style="height:200px">Pinned</div></div></div>';
  await openSketch('pinned', pinned);
  await open(page, 'pat');
  await expect(frame(page, 'pinned').locator('body')).toContainText('Pinned', { timeout: 15_000 });
  // 42px of header, 2rem of padding either side, 200px of content.
  await expect.poll(() => inner(page, 'pinned'), { timeout: 5_000 }).toBe(42 + 64 + 200);
  await resize(page, 'pinned', 300);
  await expect.poll(() => inner(page, 'pinned'), { timeout: 5_000 }).toBe(42 + 64 + 300);
  await agent.call('bee_surface_close', { surfaceId: 'pinned' });
});

// A fit re-arms only the Sends it moved: a sketch above the one that fits keeps its Send armed.
test('a fit leaves armed the Send of a sketch above it', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1600 });
  await openSketch('fitter', `${NO_MARGIN}<div id="box" style="height:150px">The fitter</div>`);
  await openSketch('above', `${NO_MARGIN}<div style="height:150px">Above</div>`);
  await open(page, 'lee');
  await expect(frame(page, 'fitter').locator('body')).toContainText('The fitter', { timeout: 15_000 });
  await expect(frame(page, 'above').locator('body')).toContainText('Above', { timeout: 15_000 });
  await propose(page, 'above', 'kept');
  await expect(send(page, 'above')).toBeEnabled();

  await resize(page, 'fitter', 300);
  await expect.poll(() => inner(page, 'fitter'), { timeout: 5_000, intervals: [5] }).toBe(300);
  expect(await send(page, 'above').isEnabled()).toBe(true);
  for (const surfaceId of ['above', 'fitter']) await agent.call('bee_surface_close', { surfaceId });
});

// On a page taller than the window the body changes size with every fit, and still a fit
// re-arms only the Sends it moved.
test('a fit leaves armed the Send of a sketch above it on a page taller than the window', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 600 });
  await openSketch('filler-tall', `${NO_MARGIN}<div style="height:1000px">Filler</div>`);
  await openSketch('fitter-tall', `${NO_MARGIN}<div id="box" style="height:150px">The fitter</div>`);
  await openSketch('above-tall', `${NO_MARGIN}<div style="height:150px">Above</div>`);
  await open(page, 'ash');
  await expect(frame(page, 'filler-tall').locator('body')).toContainText('Filler', { timeout: 15_000 });
  await expect(frame(page, 'above-tall').locator('body')).toContainText('Above', { timeout: 15_000 });
  await expect.poll(() => inner(page, 'fitter-tall'), { timeout: 5_000 }).toBe(150);
  // Precondition: the page is taller than the window.
  expect(await page.evaluate(() => document.documentElement.scrollHeight > innerHeight)).toBe(true);
  // Precondition: the Send that should stay armed is above the fitter.
  const y = (id) => iframe(page, id).evaluate((f) => f.getBoundingClientRect().y);
  expect(await y('above-tall')).toBeLessThan(await y('fitter-tall'));
  await propose(page, 'above-tall', 'kept');
  await expect(send(page, 'above-tall')).toBeEnabled();
  const bodyBefore = await page.evaluate(() => document.body.getBoundingClientRect().height);
  await countDisables(page, 'above-tall');

  await resize(page, 'fitter-tall', 300);
  await expect.poll(() => inner(page, 'fitter-tall'), { timeout: 5_000, intervals: [5] }).toBe(300);
  await settled(page);
  // Precondition: the fit resized the body.
  expect(await page.evaluate(() => document.body.getBoundingClientRect().height)).toBe(bodyBefore + 150);
  expect(await disabledCount(page)).toBe(0);
  expect(await send(page, 'above-tall').isEnabled()).toBe(true);
  for (const surfaceId of ['above-tall', 'fitter-tall', 'filler-tall']) await agent.call('bee_surface_close', { surfaceId });
});

// A document whose height follows the frame's turns every fit around; the frame stops refitting.
// Its script sets its height on every resize, as a sketch that lays itself out to its window
// would, so the helper reports each one and the page's own damping is what stops it.
test('a frame whose document follows its height stops refitting', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('swing', `${NO_MARGIN}<div id="s">Swing</div><script>` +
    'const f = () => { document.getElementById("s").style.height = (1000 - innerHeight) + "px"; };' +
    'f(); addEventListener("resize", f);</' + 'script>');
  await open(page, 'kim');
  await expect(frame(page, 'swing').locator('body')).toContainText('Swing', { timeout: 15_000 });
  await page.waitForTimeout(1_500);
  expect(await refits(page, 'swing')).toBe(0);
  await agent.call('bee_surface_close', { surfaceId: 'swing' });
});

// Content sized in `vh` follows the frame's height, so each fit would shrink it and the next
// report, down to the floor. A frame's own resize reports nothing, so it keeps its first fit.
test('a sketch sized in vh keeps the height it first reports', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('vh-svg', `${NO_MARGIN}<svg style="height:60vh"></svg>`);
  await openSketch('vh-div', `${NO_MARGIN}<div style="height:80vh">Tall</div>`);
  await open(page, 'jo');
  await expect(frame(page, 'vh-div').locator('body')).toContainText('Tall', { timeout: 15_000 });
  await page.waitForTimeout(1_500);
  // The frame starts at its cap, 720px here: about 60vh and 80vh of that, not the 120px floor.
  const svg = await inner(page, 'vh-svg');
  expect(svg).toBeGreaterThan(420);
  expect(svg).toBeLessThan(450);
  const div = await inner(page, 'vh-div');
  expect(div).toBeGreaterThan(560);
  expect(div).toBeLessThanOrEqual(576);
  const [svgFits, divFits] = await Promise.all([refits(page, 'vh-svg'), refits(page, 'vh-div')]);
  expect(svgFits + divFits).toBe(0);
  for (const surfaceId of ['vh-svg', 'vh-div']) await agent.call('bee_surface_close', { surfaceId });
});

// A press already on Send when its proposal changes was meant for the old one. Held while Send
// re-arms, then slid off Send and back, it still can't send the new one.
test('a press held on Send while its proposal changes records nothing', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('held', `${NO_MARGIN}<div style="height:150px">Held</div>`);
  await open(page, 'ty');
  await expect(frame(page, 'held').locator('body')).toContainText('Held', { timeout: 15_000 });
  // Both proposals show the same text, so Send stays where the press is.
  await propose(page, 'held', 'first', 'Same');
  await expect(send(page, 'held')).toBeEnabled();
  const box = await send(page, 'held').boundingBox();
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  await page.mouse.move(at.x, at.y);
  await page.mouse.down();
  await propose(page, 'held', 'second', 'Same');
  await page.waitForTimeout(700);
  await leaveAndReturn(page, at);
  await expect(send(page, 'held')).toBeEnabled();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  await expect(records(page, 'held')).toHaveCount(0);

  // Control: a fresh click sends the proposal shown.
  expect(await under(page, at)).toBe('bee-proposal-send');
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'held')).toHaveCount(1, { timeout: 10_000 });
  await agent.call('bee_surface_close', { surfaceId: 'held' });
});

/** The centre of sketch [id]'s Send, where a click on it lands. */
async function sendCentre(page, id) {
  const box = await send(page, id).boundingBox();
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** A sketch of [content] with a field at its end, to Tab out of. */
const tabbable = (content) => `${NO_MARGIN}${content}<input id="last">`;

/** What has focus on the page, and whether it reads disarmed. */
const focused = (page) => page.evaluate(() => {
  const active = document.activeElement;
  return `${active?.className} ${active?.getAttribute('aria-disabled') === 'true' || active?.disabled === true}`;
});

// bee#84: a disabled Send hears no clicks, so a viewer clicking steadily at one spot clicked
// nothing while Send re-armed under them, and the first click after it armed sent. Now Send takes
// every click, and one it refuses re-arms it: a click within SEND_ARM_MS of the last never sends.
test('a steady stream of clicks on a re-arming Send records nothing', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('stream', `${NO_MARGIN}<div style="height:150px">Stream</div>`);
  await open(page, 'cy');
  await expect(frame(page, 'stream').locator('body')).toContainText('Stream', { timeout: 15_000 });
  await propose(page, 'stream', 'first', 'Same');
  await expect(send(page, 'stream')).toBeEnabled();
  const at = await sendCentre(page, 'stream');
  await page.mouse.move(at.x, at.y + 80);
  // The same text, so Send re-arms where it is.
  await propose(page, 'stream', 'second', 'Same');
  await expect(send(page, 'stream')).toBeDisabled();
  // The pointer arrives on Send while it re-arms, so Send doesn't wait for it to leave.
  await page.mouse.move(at.x, at.y);
  expect(await under(page, at)).toBe('bee-proposal-send');
  const started = Date.now();
  for (let i = 0; i < 12; i++) {
    await page.mouse.down();
    await page.mouse.up();
    await page.waitForTimeout(150);
  }
  // Precondition: the stream outlasted the arming several times over.
  expect(Date.now() - started).toBeGreaterThan(1_500);
  await page.waitForTimeout(1_000);
  await expect(records(page, 'stream')).toHaveCount(0);

  // Control: after a pause, a click with the pointer where it was sends.
  await expect(send(page, 'stream')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'stream')).toHaveCount(1, { timeout: 10_000 });
  await agent.call('bee_surface_close', { surfaceId: 'stream' });
});

// bee#84: Space activates a button on keyup, so the time that counts is the keydown's. Pressed
// just after Tab lands on Send, and held past its arming, it was meant for the sketch.
test('Space pressed as Send takes focus and held past its arming records nothing', async ({ page, browserName }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('space', tabbable('<div style="height:150px">Space</div>'));
  await open(page, 'di');
  await expect(frame(page, 'space').locator('body')).toContainText('Space', { timeout: 15_000 });
  await propose(page, 'space', 'held');
  await expect(send(page, 'space')).toBeEnabled();
  await frame(page, 'space').locator('#last').focus();
  // WebKit's Tab skips buttons by default, as Safari's does; Option-Tab reaches them.
  await page.keyboard.press(browserName === 'webkit' ? 'Alt+Tab' : 'Tab');
  // Precondition: Tab took focus to an armed Send.
  expect(await focused(page)).toBe('bee-proposal-send false');
  await page.keyboard.down('Space');
  await page.waitForTimeout(700);
  await page.keyboard.up('Space');
  await page.waitForTimeout(1_500);
  await expect(records(page, 'space')).toHaveCount(0);

  // Control: a fresh Space on Send, once it has had focus a while, sends.
  expect(await focused(page)).toBe('bee-proposal-send false');
  await page.keyboard.press('Space');
  await expect(records(page, 'space')).toHaveCount(1, { timeout: 10_000 });
  await agent.call('bee_surface_close', { surfaceId: 'space' });
});

// bee#84: Tab onto a Send below the window scrolls it into view, and the scroll re-arms it. A
// disabled Send lost focus there, so the keyboard path to Send never reached it.
test('Tab onto a Send below the window keeps focus on it', async ({ page, browserName }) => {
  await page.setViewportSize({ width: 1280, height: 600 });
  await openSketch('below-fold', tabbable('<div style="height:1000px">Below the fold</div>'));
  await open(page, 'em');
  await expect(frame(page, 'below-fold').locator('body')).toContainText('Below the fold', { timeout: 15_000 });
  await propose(page, 'below-fold', 'reached');
  await expect(send(page, 'below-fold')).toBeEnabled();
  await frame(page, 'below-fold').locator('#last').evaluate((field) => field.focus({ preventScroll: true }));
  // Precondition: Send is below the window, so the Tab onto it scrolls the page.
  expect(await send(page, 'below-fold').evaluate((button) => button.getBoundingClientRect().top > innerHeight)).toBe(true);
  await page.keyboard.press(browserName === 'webkit' ? 'Alt+Tab' : 'Tab');
  await settled(page);
  // The scroll re-armed Send, and focus stayed on it.
  expect(await focused(page)).toBe('bee-proposal-send true');
  await page.waitForTimeout(1_000);
  expect(await focused(page)).toBe('bee-proposal-send false');
  await page.keyboard.press('Enter');
  await expect(records(page, 'below-fold')).toHaveCount(1, { timeout: 10_000 });
  await agent.call('bee_surface_close', { surfaceId: 'below-fold' });
});

// bee#84: send() checks that Send is still where it armed, for a move nothing announced. A
// transform moves it with no scroll and no resize, so here that check is all that refuses.
test('a Send moved with no scroll or resize records nothing until it re-arms', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await openSketch('shifted', `${NO_MARGIN}<div style="height:150px">Shifted</div>`);
  await open(page, 'fi');
  await expect(frame(page, 'shifted').locator('body')).toContainText('Shifted', { timeout: 15_000 });
  await propose(page, 'shifted', 'moved');
  await expect(send(page, 'shifted')).toBeEnabled();
  await page.locator('a2ui-surface[data-surface-id="shifted"] bee-sketch .bee-proposal')
    .evaluate((bar) => { bar.style.transform = 'translateY(40px)'; });
  const at = await sendCentre(page, 'shifted');
  await page.mouse.move(at.x, at.y);
  expect(await under(page, at)).toBe('bee-proposal-send');
  // Precondition: nothing re-armed Send, so the click below meets it armed.
  expect(await send(page, 'shifted').isEnabled()).toBe(true);
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  await expect(records(page, 'shifted')).toHaveCount(0);

  // Control: re-armed where it now is, with the pointer back on it, a click sends.
  await leaveAndReturn(page, at);
  await expect(send(page, 'shifted')).toBeEnabled();
  await page.mouse.down();
  await page.mouse.up();
  await expect(records(page, 'shifted')).toHaveCount(1, { timeout: 10_000 });
  await agent.call('bee_surface_close', { surfaceId: 'shifted' });
});

// A touch held on Send while it re-arms is a pointer resting on it, so Send waits for it to leave.
// Lifting the touch leaves Send, and the next clean tap sends.
test('a touch held on Send across its arming leaves it usable once lifted', async ({ browser, browserName }) => {
  test.skip(browserName !== 'chromium', 'A held touch is driven over CDP');
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 1280, height: 1000 } });
  const page = await context.newPage();
  try {
    await openSketch('touch', `${NO_MARGIN}<div style="height:150px">Touch</div>`);
    await open(page, 'ro');
    await expect(frame(page, 'touch').locator('body')).toContainText('Touch', { timeout: 15_000 });
    await propose(page, 'touch', 'first', 'Same');
    await expect(send(page, 'touch')).toBeEnabled();
    const box = await send(page, 'touch').boundingBox();
    const at = { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2) };
    const cdp = await context.newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [at] });
    await propose(page, 'touch', 'second', 'Same');
    // Precondition: Send re-armed under the held touch.
    await expect(send(page, 'touch')).toBeDisabled();
    await page.waitForTimeout(700);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(1_000);
    await expect(records(page, 'touch')).toHaveCount(0);

    await page.touchscreen.tap(at.x, at.y);
    await expect(records(page, 'touch')).toHaveCount(1, { timeout: 10_000 });
  } finally {
    await context.close();
    await agent.call('bee_surface_close', { surfaceId: 'touch' });
  }
});
