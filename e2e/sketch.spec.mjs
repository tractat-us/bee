// Sketch and the brainstorming companion on bee (Task 8, spike 5). A real bee-companion, built
// by `:bee-companion:installDist`, joins the host Playwright started, over temporary screen and
// state directories, as a brainstorming session would point it. Each test pushes its own
// screen; the newest one is the Sketch on the companion's own surface, `companion-<id>`, which it
// keeps in state_dir/bee-companion.json.
import { test, expect } from '@playwright/test';
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createInterface } from 'node:readline';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const port = process.env.BEE_E2E_PORT;
const dirs = mkdtempSync(join(tmpdir(), 'bee-e2e-companion-'));
const screenDir = join(dirs, 'content');
const stateDir = join(dirs, 'state');
const eventsFile = join(stateDir, 'events');
const companionBin = join(repo, 'bee-companion/build/install/bee-companion/bin/bee-companion');
const template = join(dirs, 'frame-template.html');
// A fixture frame template, shaped like the companion's: its CSS, and the content slot.
const TEMPLATE = '<!DOCTYPE html><html><head><style>.options{display:flex;gap:1rem}</style></head>' +
  '<body><div class="header"><!-- BRANDING --><div class="status">Connecting</div></div>' +
  '<div id="frame-content"><!-- CONTENT --></div></body></html>';

test.describe.configure({ mode: 'serial' });

let companion;
let surfaceId;
let screens = 0;

test.beforeAll(async () => {
  test.setTimeout(600_000);
  mkdirSync(screenDir, { recursive: true });
  mkdirSync(stateDir, { recursive: true });
  writeFileSync(template, TEMPLATE);
  execFileSync(join(repo, 'gradlew'), [':bee-companion:installDist', '--console=plain', '--quiet'], {
    cwd: repo,
    timeout: 540_000,
    stdio: 'inherit',
  });
  companion = spawn(companionBin, ['--screen-dir', screenDir, '--state-dir', stateDir, '--frame-template', template], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${port}` },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  companion.lines = [];
  companion.stdout.on('data', (chunk) => companion.lines.push(...chunk.toString().split('\n').filter(Boolean)));
  await expect.poll(() => companion.lines.some((l) => l.includes('server-started')), { timeout: 60_000 }).toBe(true);
  const kept = join(stateDir, 'bee-companion.json');
  await expect.poll(() => existsSync(kept), { timeout: 60_000 }).toBe(true);
  surfaceId = JSON.parse(readFileSync(kept, 'utf8')).surface;
  expect(surfaceId).toMatch(/^companion-[0-9a-f]+$/);
});

// The board is shared with the other specs: the companion leaves it as the brainstorming loop
// does, with a waiting screen, which closes its surface.
test.afterAll(async () => {
  if (!companion) return;
  pushScreen('waiting.html', '<p>Continuing in terminal...</p>');
  await expect.poll(() => companion.lines.some((l) => l.includes('screen-closed')), { timeout: 10_000 }).toBe(true);
  companion.kill();
});

/** Writes screen `name`, newer than every screen before it, and returns its file name. */
function pushScreen(name, html) {
  const file = join(screenDir, name);
  writeFileSync(file, html);
  const at = Date.now() / 1000 + ++screens;
  utimesSync(file, at, at);
  return name;
}

/** Opens the board as `name` and waits for the companion's sketch to show `text`. */
async function openBoard(browser, name, text) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  const frame = sketchFrame(page);
  await expect(frame.locator('body')).toContainText(text, { timeout: 15_000 });
  return { context, page, frame };
}

const sketchIframe = (page) => page.locator(`a2ui-surface[data-surface-id="${surfaceId}"] bee-sketch iframe`);
const sketchFrame = (page) => page.frameLocator(`a2ui-surface[data-surface-id="${surfaceId}"] bee-sketch iframe`);
const companionRecords = (page) => page.locator(`#records li[data-surface-id="${surfaceId}"]`);

/** The page's own bar under a sketch, showing the choice it proposes (bee#60). */
const proposal = (page, surface = surfaceId) =>
  page.locator(`a2ui-surface[data-surface-id="${surface}"] bee-sketch .bee-proposal`);

/**
 * Sends the sketch's proposal, with a click on the page's own Send button. Send re-arms whenever
 * it moves, so it's scrolled into view first, and clicked once it has armed where it stays.
 */
async function confirm(page, surface = surfaceId) {
  const send = proposal(page, surface).getByRole('button', { name: 'Send', exact: true });
  await send.scrollIntoViewIfNeeded();
  await settled(page);
  await expect(send).toBeEnabled();
  await send.click();
}

/** Waits two frames, so the scroll and resize events of any layout change so far have fired. */
const settled = (page) => page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));

function eventLines() {
  return existsSync(eventsFile) ? readFileSync(eventsFile, 'utf8').split('\n').filter(Boolean) : [];
}

const options = '<h2>Which layout?</h2><div class="options">' +
  '<div class="option" data-choice="a" onclick="toggleSelect(this)">Option A</div>' +
  '<div class="option" data-choice="b" onclick="toggleSelect(this)">Option B</div></div>';

test('a sketch click lands as a record with a basis', async ({ browser }) => {
  pushScreen('layout.html', options);
  const alex = await openBoard(browser, 'alex', 'Which layout?');
  const before = await companionRecords(alex.page).count();

  await alex.frame.locator('[data-choice="a"]').click();
  await expect(proposal(alex.page)).toContainText('Option A');
  await confirm(alex.page);
  // The actor is the person, the component is the sketch, and the basis names the screen alex saw.
  await expect(companionRecords(alex.page)).toHaveCount(before + 1, { timeout: 5_000 });
  await expect(companionRecords(alex.page).last()).toHaveText(/^human:alex choice on root, having seen \/screens [1-9]\d*, records \d+$/);
  await alex.context.close();
});

test('click inside the sketch reaches the events file', async ({ browser }) => {
  pushScreen('layout-2.html', options.replace('Which layout?', 'Which layout, again?'));
  const sam = await openBoard(browser, 'sam', 'Which layout, again?');
  await expect.poll(() => existsSync(eventsFile), { timeout: 5_000 }).toBe(false);

  await sam.frame.locator('[data-choice="b"]').click();
  await confirm(sam.page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  const event = JSON.parse(eventLines()[0]);
  expect(Object.keys(event)).toEqual(['type', 'choice', 'text', 'timestamp']);
  expect(event).toMatchObject({ type: 'click', choice: 'b', text: 'Option B' });
  expect(Math.abs(event.timestamp - Date.now() / 1000)).toBeLessThan(60);
  await sam.context.close();
});

// A sketch only proposes (bee#60): the page shows the proposal under that sketch and records it
// only when the viewer clicks the page's own Send. A later proposal replaces the earlier one.
test('a later proposal replaces the earlier one and Send records it once', async ({ browser }) => {
  pushScreen('replace.html', options.replace('Which layout?', 'Replace layout?'));
  const pat = await openBoard(browser, 'pat', 'Replace layout?');
  await expect.poll(() => existsSync(eventsFile), { timeout: 5_000 }).toBe(false);

  await pat.frame.locator('[data-choice="a"]').click();
  await expect(proposal(pat.page)).toContainText('Option A');
  await pat.frame.locator('[data-choice="b"]').click();
  await expect(proposal(pat.page)).toContainText('Option B');
  await expect(proposal(pat.page)).not.toContainText('Option A');
  // The page answers the replaced proposal, and the sketch keeps the later one's selection.
  await expect(pat.frame.locator('.option.selected')).toHaveText('Option B');
  // A proposal never records itself, nor does a click a script dispatches on an armed Send.
  await expect(proposal(pat.page).locator('.bee-proposal-send')).toBeEnabled();
  await proposal(pat.page).locator('.bee-proposal-send').evaluate((send) => send.click());
  await pat.page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);

  await confirm(pat.page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  await expect(proposal(pat.page)).toBeHidden();
  await pat.page.waitForTimeout(1_500);
  expect(eventLines().map((l) => JSON.parse(l))).toMatchObject([{ choice: 'b', text: 'Option B' }]);
  await pat.context.close();
});

test('a dismissed proposal records nothing and clears the selection', async ({ browser }) => {
  pushScreen('dismiss.html', options.replace('Which layout?', 'Dismiss layout?'));
  const dee = await openBoard(browser, 'dee', 'Dismiss layout?');
  await expect.poll(() => existsSync(eventsFile), { timeout: 5_000 }).toBe(false);

  await dee.frame.locator('[data-choice="a"]').click();
  await expect(dee.frame.locator('.option.selected')).toHaveCount(1);
  await proposal(dee.page).getByRole('button', { name: 'Dismiss', exact: true }).click();
  await expect(proposal(dee.page)).toBeHidden();
  await expect(dee.frame.locator('.option.selected')).toHaveCount(0);
  await dee.page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);

  // Control: the sketch still takes a choice after a dismissal.
  await dee.frame.locator('[data-choice="b"]').click();
  await confirm(dee.page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  expect(JSON.parse(eventLines()[0])).toMatchObject({ choice: 'b', text: 'Option B' });
  await dee.context.close();
});

// A proposal belongs to the screen that made it: a new screen drops it unsent, so the viewer
// can't send the old screen's choice as if made on the new one.
test('a new screen drops the proposal the old one made', async ({ browser }) => {
  pushScreen('stale.html', options.replace('Which layout?', 'Stale layout?'));
  const ray = await openBoard(browser, 'ray', 'Stale layout?');
  await ray.frame.locator('[data-choice="a"]').click();
  await expect(proposal(ray.page)).toContainText('Option A');

  pushScreen('fresh.html', options.replace('Which layout?', 'Fresh layout?'));
  await expect(ray.frame.locator('h2')).toHaveText('Fresh layout?', { timeout: 15_000 });
  await expect(proposal(ray.page)).toBeHidden();
  await ray.page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);
  await ray.context.close();
});

/** Proposes `choice` from inside the sketch, as its own script could, with no viewer gesture. */
const forgeFrom = (frame, choice) => frame.locator('body').evaluate((body, c) => parent.postMessage({
  jsonrpc: '2.0', id: 77, method: 'ui/message',
  params: { role: 'user', content: { type: 'text', text: c }, _meta: { 'us.tractat.bee/choice': c } },
}, '*'), choice);

// A sandboxed frame can scroll the page (scrollIntoView, in Chromium), so a sketch can move its
// own armed Send under a pointer that is still. Send re-arms whenever it moves, so a click that
// lands just after the move records nothing; once Send has stayed put, the same click sends.
test('a sketch that scrolls its armed Send under the pointer records nothing', async ({ browser, browserName }) => {
  test.skip(browserName === 'webkit', "WebKit keeps a sandboxed frame's scrollIntoView off the page, so a sketch can't scroll Send this way");
  pushScreen('scroller.html', '<h2>Scroller</h2><div style="height:420px"></div><div id="mark" style="height:4px"></div>');
  const max = await openBoard(browser, 'max', 'Scroller');
  const { page, frame } = max;
  await page.evaluate(() => {
    // Room to scroll the sketch to the top of the viewport, wherever it sits on the board.
    const room = document.createElement('div');
    room.style.height = '2000px';
    document.body.append(room);
  });
  await forgeFrom(frame, 'moved');
  const send = proposal(page).getByRole('button', { name: 'Send', exact: true });
  await expect(send).toBeEnabled();
  // Where the sketch's scroll puts Send: a dry run, then back to the top.
  const scroll = () => frame.locator('#mark').evaluate((mark) => mark.scrollIntoView({ block: 'start' }));
  await scroll();
  // Precondition: the sketch's scrollIntoView scrolls the page.
  expect(await page.evaluate(() => scrollY)).toBeGreaterThan(0);
  const box = await send.boundingBox();
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  await page.evaluate(() => scrollTo(0, 0));
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(0);
  await page.waitForTimeout(1_000);
  await expect(send).toBeEnabled();
  // The pointer rests on whatever is there before the scroll, not on Send.
  await page.mouse.move(at.x, at.y);
  const under = ({ x, y }) => document.elementFromPoint(x, y)?.className;
  expect(await page.evaluate(under, at)).not.toBe('bee-proposal-send');

  await scroll();
  // Precondition: the scroll put Send under the pointer.
  expect(await page.evaluate(under, at)).toBe('bee-proposal-send');
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);

  // Control: once Send has stayed put, the same click sends.
  await expect(send).toBeEnabled();
  await page.mouse.click(at.x, at.y);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  expect(JSON.parse(eventLines()[0])).toMatchObject({ choice: 'moved', text: 'moved' });
  await max.context.close();
});

// Tab out of a sketch lands on its own Send, so Enter, meant for the sketch, would send it. A
// keyboard press on Send counts only once Send has had focus for a moment.
test('Enter just after tabbing out of a sketch records nothing', async ({ browser, browserName }) => {
  pushScreen('tabber.html', options.replace('Which layout?', 'Tab layout?') + '<input id="last">');
  const tia = await openBoard(browser, 'tia', 'Tab layout?');
  const { page, frame } = tia;
  await frame.locator('[data-choice="a"]').click();
  await frame.locator('#last').click();
  // Send in view and armed, so the Tab below scrolls nothing and Send stays enabled.
  const send = proposal(page).getByRole('button', { name: 'Send', exact: true });
  await send.scrollIntoViewIfNeeded();
  await settled(page);
  await expect(send).toBeEnabled();
  const focused = () => page.evaluate(() => `${document.activeElement?.className} ${document.activeElement?.disabled}`);
  // WebKit's Tab skips buttons by default, as Safari's does; Option-Tab reaches them.
  await page.keyboard.press(browserName === 'webkit' ? 'Alt+Tab' : 'Tab');
  // Precondition: Tab took focus to an enabled Send, so the Enter just after reached it.
  expect(await focused()).toBe('bee-proposal-send false');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);

  // Control: Enter on Send, once it has had focus a while, sends.
  expect(await focused()).toBe('bee-proposal-send false');
  await page.keyboard.press('Enter');
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  expect(JSON.parse(eventLines()[0])).toMatchObject({ choice: 'a', text: 'Option A' });
  await tia.context.close();
});

// In a `data-multiselect` container the companion lets the viewer select several options. Each
// needs its own Send: a later click replaces an unsent one, and the frame drops that one's
// selection, so what it shows selected is what was sent, plus the one proposed.
test('multiselect shows selected only what was sent and the proposal', async ({ browser }) => {
  const multi = '<h2>Pick several</h2><div class="options" data-multiselect>' +
    ['a', 'b', 'c'].map((c) => `<div class="option" data-choice="${c}" onclick="toggleSelect(this)">Option ${c.toUpperCase()}</div>`).join('') +
    '</div>';
  pushScreen('multi.html', multi);
  const mo = await openBoard(browser, 'mo', 'Pick several');
  const { page, frame } = mo;
  const selected = frame.locator('.option.selected');

  await frame.locator('[data-choice="a"]').click();
  await confirm(page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  await frame.locator('[data-choice="b"]').click();
  await frame.locator('[data-choice="c"]').click();
  await expect(proposal(page)).toContainText('Option C');
  // B was replaced unsent, so it no longer shows selected; A was sent, so it still does.
  await expect(selected).toHaveText(['Option A', 'Option C']);
  await proposal(page).getByRole('button', { name: 'Dismiss', exact: true }).click();
  await expect(selected).toHaveText(['Option A']);

  await frame.locator('[data-choice="b"]').click();
  await confirm(page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(2);
  await expect(selected).toHaveText(['Option A', 'Option B']);
  expect(eventLines().map((l) => JSON.parse(l).choice)).toEqual(['a', 'b']);
  await mo.context.close();
});

test('sketch cannot reach the parent DOM or the network', async ({ browser }) => {
  const probe = `http://127.0.0.1:${port}/sketch-probe`;
  // The sketch tries the parent's DOM, a fetch, an image and a WebSocket, and writes what happened.
  pushScreen('escape.html', '<h2>Escape attempt</h2><pre id="probe">running</pre>' +
    '<img src="' + probe + '/img.png">' +
    '<script>(async () => {' +
    '  const out = {};' +
    '  try { out.parent = parent.document.body ? "reached" : "none"; } catch (e) { out.parent = "blocked"; }' +
    '  try { await fetch("' + probe + '/fetch"); out.fetch = "reached"; } catch (e) { out.fetch = "blocked"; }' +
    '  try { new WebSocket("ws://127.0.0.1:' + port + '/sketch-probe"); out.socket = "opened"; } catch (e) { out.socket = "blocked"; }' +
    '  out.rtc = typeof RTCPeerConnection;' +
    '  document.getElementById("probe").textContent = JSON.stringify(out);' +
    '})();</script>');
  const context = await browser.newContext();
  const page = await context.newPage();
  // What counts is a probe that got an answer from the host (a 404 is an answer) or a socket that
  // opened. A load the frame's CSP refused is reported on the console, in Chromium and WebKit alike
  // (Chromium also reports it as a failed request, WebKit doesn't), as clickAway relies on.
  const reached = [];
  const refused = [];
  page.on('response', (response) => { if (response.url().includes('sketch-probe')) reached.push(response.url()); });
  page.on('websocket', (ws) => { if (ws.url().includes('sketch-probe')) reached.push(ws.url()); });
  page.on('console', (message) => { if (message.text().includes('sketch-probe')) refused.push(message.text()); });
  await page.goto('/?name=kim');
  const frame = sketchFrame(page);
  await expect(frame.locator('#probe')).not.toHaveText('running', { timeout: 15_000 });
  const outcome = JSON.parse(await frame.locator('#probe').textContent());

  expect(await sketchIframe(page).getAttribute('sandbox')).toBe('allow-scripts');
  expect(outcome.parent).toBe('blocked');
  expect(outcome.fetch).toBe('blocked');
  // Best-effort only: WebRTC sits outside CSP (bee#49), so the helper deletes its constructor.
  expect(outcome.rtc).toBe('undefined');
  // CSP blocks a WebSocket either by throwing or by never sending it; the request log decides.
  await page.waitForTimeout(1_000);
  expect(reached).toEqual([]);
  // Control: the image load was attempted and refused, so the probe could have seen one.
  expect(refused.some((r) => r.includes('/img.png'))).toBe(true);
  // The parent's DOM is untouched by the sketch: the page still shows the board as before.
  await expect(page.locator('#status')).toHaveText(/^online/);
  await context.close();
});

/**
 * Opens the board as `name` and clicks `#away` in the sketch. Every sketch-probe the host
 * answered goes into `reached`, and every one the page's CSP refused into `refused`.
 */
async function clickAway(browser, name, heading) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const reached = [];
  const refused = [];
  page.on('response', (response) => { if (response.url().includes('sketch-probe')) reached.push(response.url()); });
  page.on('console', (message) => { if (message.text().includes('sketch-probe')) refused.push(message.text()); });
  const response = await page.goto(`/?name=${name}`);
  // The screen just pushed, not the one before it: both have an #away.
  await expect(sketchFrame(page).locator('h2')).toHaveText(heading, { timeout: 15_000 });
  await sketchFrame(page).locator('#away').click();
  await page.waitForTimeout(1_500);
  return { context, page, reached, refused, csp: response.headers()['content-security-policy'] };
}

// The sandbox and the frame's own CSP can't stop a frame navigating itself: only the embedding
// page's frame-src can (bee-host's PAGE_CSP). Each probe answers 404 if it reaches the host.
test('sketch cannot navigate its frame to a URL', async ({ browser }) => {
  const probe = `http://127.0.0.1:${port}/sketch-probe`;
  pushScreen('nav-link.html', `<h2>Link probe</h2><a id="away" href="${probe}/link">away</a>`);
  const link = await clickAway(browser, 'lee', 'Link probe');
  expect(link.reached).toEqual([]);
  expect(link.csp).toBe("frame-src 'none'; child-src 'none'");
  // Control: the navigation was attempted, and the page's policy is what refused it.
  expect(link.refused.join('\n')).toContain(`${probe}/link`);
  await link.context.close();

  pushScreen('nav-location.html', '<h2>Location probe</h2>' +
    `<button id="away" onclick="location = '${probe}/location'">away</button>`);
  const located = await clickAway(browser, 'lou', 'Location probe');
  expect(located.reached).toEqual([]);
  expect(located.refused.join('\n')).toContain(`${probe}/location`);
  // The page itself is untouched: still online, over the WebSocket the header never touched.
  await expect(located.page.locator('#status')).toHaveText(/^online/);
  await located.context.close();
});

// A sketch that posts by itself, at load and through the companion's page API, only proposes:
// the proposal shows on the page and records nothing until the viewer sends it.
test('a sketch that posts at load creates no record', async ({ browser }) => {
  test.setTimeout(60_000);
  pushScreen('auto.html', '<h2>Auto poster</h2>' +
    '<div class="options"><div class="option" id="real" data-choice="real">Real</div></div><script>' +
    'const forge = (choice) => parent.postMessage({ jsonrpc: "2.0", id: 900, method: "ui/message", params: ' +
    '{ role: "user", content: { type: "text", text: choice }, _meta: { "us.tractat.bee/choice": choice } } }, "*");' +
    'brainstorm.choice("helper"); let n = 0; const t = setInterval(() => { forge("auto"); if (++n >= 25) { clearInterval(t); document.body.dataset.done = "1"; } }, 100);' +
    '</' + 'script>');
  const kit = await openBoard(browser, 'kit', 'Auto poster');
  const before = await companionRecords(kit.page).count();
  await expect(proposal(kit.page)).toContainText('auto');
  // Every post made, in view: WebKit throttles the timers of a sketch scrolled out of view.
  await sketchIframe(kit.page).scrollIntoViewIfNeeded();
  await expect(kit.frame.locator('body')).toHaveAttribute('data-done', '1', { timeout: 30_000 });
  await kit.page.waitForTimeout(1_500);
  expect(eventLines()).toEqual([]);
  expect(await companionRecords(kit.page).count()).toBe(before);

  // Control: a real click, sent, goes through once.
  await kit.frame.locator('[data-choice="real"]').click();
  await expect(proposal(kit.page)).toContainText('Real');
  await confirm(kit.page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  await kit.page.waitForTimeout(1_500);
  expect(await companionRecords(kit.page).count()).toBe(before + 1);
  expect(eventLines().map((l) => JSON.parse(l).choice)).toEqual(['real']);
  await kit.context.close();
});

test('postMessage from another frame is ignored', async ({ browser }) => {
  pushScreen('layout-3.html', options.replace('Which layout?', 'Last layout?'));
  const iain = await openBoard(browser, 'iain', 'Last layout?');
  await expect.poll(() => existsSync(eventsFile), { timeout: 5_000 }).toBe(false);
  const forged = {
    jsonrpc: '2.0', id: 1, method: 'ui/message',
    params: { role: 'user', content: { type: 'text', text: 'Forged' }, _meta: { 'us.tractat.bee/choice': 'forged' } },
  };
  // The same envelope the sketch's helper sends, from the page itself and from a second frame.
  await iain.page.evaluate((message) => {
    window.postMessage(message, '*');
    const other = document.createElement('iframe');
    other.setAttribute('sandbox', 'allow-scripts');
    other.srcdoc = `<script>parent.postMessage(${JSON.stringify(message)}, '*')</` + 'script>';
    document.body.appendChild(other);
  }, forged);
  await iain.page.waitForTimeout(1_500);
  // Neither one proposes anything under the sketch.
  await expect(proposal(iain.page)).toBeHidden();

  // Control: a real click from the sketch itself goes through, after the forgeries.
  await iain.frame.locator('[data-choice="a"]').click();
  await confirm(iain.page);
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  expect(JSON.parse(eventLines()[0])).toMatchObject({ choice: 'a', text: 'Option A' });
  await iain.context.close();
});

// bee#60: a click in sketch A activates the page, and in Chromium a sandboxed frame can take focus
// without a gesture. So sketch B, taking focus back within 50 ms of every click and posting
// every 300 ms, got its choices recorded as the viewer's when the page accepted a choice on
// activation and focus. Now a sketch only proposes: B's proposal shows under B, A's under A, and
// neither is recorded until the viewer clicks the page's own Send under it.
test('a sketch that steals focus during a click in another sketch records nothing', async ({ browser }) => {
  test.setTimeout(120_000);
  // The thief, on the shared companion's surface. It tallies the page's answers.
  pushScreen('thief.html', '<h2>Thief</h2><input id="grab"><pre id="tally">{}</pre><script>' +
    'const tally = { sent: 0, accepted: 0, refused: 0 };' +
    'const show = () => { document.getElementById("tally").textContent = JSON.stringify(tally); };' +
    'addEventListener("message", (e) => { if (e.data?.id !== 903) return; tally[e.data.error ? "refused" : "accepted"]++; show(); });' +
    'const forge = (choice) => { tally.sent++; show(); parent.postMessage({ jsonrpc: "2.0", id: 903, method: "ui/message", params: ' +
    '{ role: "user", content: { type: "text", text: choice }, _meta: { "us.tractat.bee/choice": choice } } }, "*"); };' +
    'setInterval(() => { window.focus(); document.getElementById("grab").focus(); }, 50);' +
    'setInterval(() => forge("stolen"), 300);' +
    '</' + 'script>');
  // The chooser: a second companion's sketch, on its own directories, with a real choice.
  const own = mkdtempSync(join(tmpdir(), 'bee-e2e-chooser-'));
  const ownScreens = join(own, 'content');
  const ownState = join(own, 'state');
  const ownEvents = join(ownState, 'events');
  mkdirSync(ownScreens, { recursive: true });
  writeFileSync(join(ownScreens, 'chooser.html'), options.replace('Which layout?', 'Chooser layout?'));
  const chooser = spawn(companionBin, ['--screen-dir', ownScreens, '--state-dir', ownState, '--frame-template', template], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${port}` },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const exited = new Promise((resolve) => chooser.on('exit', (code, signal) => resolve(code ?? signal)));
  const kept = join(ownState, 'bee-companion.json');
  await expect.poll(() => existsSync(kept), { timeout: 60_000 }).toBe(true);
  const ownSurface = JSON.parse(readFileSync(kept, 'utf8')).surface;
  const ownLines = () => (existsSync(ownEvents) ? readFileSync(ownEvents, 'utf8').split('\n').filter(Boolean) : []);
  // The chooser stops even when an assertion fails, or it outlives the host, redialling.
  try {
    await chooseBesideAThief(browser, ownSurface, ownLines);
  } finally {
    chooser.kill('SIGTERM');
    await exited;
  }
});

/** Opens the board as bo and makes a choice in the chooser's sketch while the thief runs. */
async function chooseBesideAThief(browser, ownSurface, ownLines) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/?name=bo');
  await expect(sketchFrame(page).locator('h2')).toHaveText('Thief', { timeout: 15_000 });
  const chooserFrame = page.frameLocator(`a2ui-surface[data-surface-id="${ownSurface}"] bee-sketch iframe`);
  await expect(chooserFrame.locator('h2')).toHaveText('Chooser layout?', { timeout: 15_000 });

  // Three real clicks in A, a second apart: each one opens the thief's window.
  for (const choice of ['a', 'b', 'b']) {
    await chooserFrame.locator(`[data-choice="${choice}"]`).click();
    await page.waitForTimeout(1_000);
  }
  await page.waitForTimeout(2_000);
  // The thief's posts reach no record (red in Chromium on the old acceptance path).
  expect(eventLines()).toEqual([]);
  // Nor does the chooser's click, which waits for Send (red in every engine on the old path).
  expect(ownLines()).toEqual([]);
  // Each proposal shows under the sketch that made it.
  await expect(proposal(page, ownSurface)).toContainText('Option B');
  await expect(proposal(page, ownSurface)).not.toContainText('stolen');
  await expect(proposal(page)).toContainText('stolen');

  await confirm(page, ownSurface);
  await expect.poll(ownLines, { timeout: 10_000 }).toHaveLength(1);
  // The thief swaps its proposal every 300 ms, so its Send never arms: clicks on the spot where
  // it sits, as a viewer's click on its way would land, record nothing.
  const thiefSend = proposal(page).getByRole('button', { name: 'Send', exact: true });
  await thiefSend.scrollIntoViewIfNeeded();
  const send = await thiefSend.boundingBox();
  const at = { x: send.x + send.width / 2, y: send.y + send.height / 2 };
  // Precondition: the clicks land on the thief's Send, not on whatever is beside it.
  expect(await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.className, at)).toBe('bee-proposal-send');
  for (let i = 0; i < 5; i++) {
    await page.mouse.click(at.x, at.y);
    await page.waitForTimeout(150);
  }
  await page.waitForTimeout(3_000);
  expect(eventLines()).toEqual([]);
  expect(ownLines().map((l) => JSON.parse(l))).toMatchObject([{ choice: 'b', text: 'Option B' }]);
  // Control: the thief kept posting through every click, and none was taken.
  const tallied = async () => JSON.parse(await sketchFrame(page).locator('#tally').textContent());
  const tally = await tallied();
  expect(tally.accepted).toBe(0);
  await expect.poll(async () => (await tallied()).sent, { timeout: 5_000 }).toBeGreaterThan(tally.sent);
  await context.close();
}

// The brainstorming loop's stop script kills its companion with SIGTERM. A companion stopped so
// closes its own surface before it ends, so the board keeps no open, dead Sketch, and then leaves,
// so the strip reads it left. Without the leave, the host departs it crashed a learner grace (30 s)
// later; the departure wait outlasts that, so a missing leave reads crashed, not a timeout.
test('a companion stopped with SIGTERM closes its surface and reads as left', async ({ browser }) => {
  test.setTimeout(150_000);
  const own = mkdtempSync(join(tmpdir(), 'bee-e2e-stopped-'));
  const ownScreens = join(own, 'content');
  const ownState = join(own, 'state');
  mkdirSync(ownScreens, { recursive: true });
  writeFileSync(join(ownScreens, 'stopping.html'), '<h2>About to stop</h2>');
  const stopping = spawn(companionBin, ['--screen-dir', ownScreens, '--state-dir', ownState, '--frame-template', template], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${port}`, BEE_SESSION_NAME: 'stopping' },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  const exited = new Promise((resolve) => stopping.on('exit', (code, signal) => resolve(code ?? signal)));
  // Its stderr, passed on as `inherit` would, and the identity it says it starts as.
  const identity = new Promise((resolve) => {
    createInterface({ input: stopping.stderr }).on('line', (line) => {
      process.stderr.write(`${line}\n`);
      const starts = line.match(/starts as (agent:\S+),/);
      if (starts) resolve(starts[1]);
    });
  });
  const kept = join(ownState, 'bee-companion.json');
  await expect.poll(() => existsSync(kept), { timeout: 60_000 }).toBe(true);
  const ownSurface = JSON.parse(readFileSync(kept, 'utf8')).surface;
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/?name=sol');
  const shown = page.frameLocator(`a2ui-surface[data-surface-id="${ownSurface}"] bee-sketch iframe`);
  await expect(shown.locator('h2')).toHaveText('About to stop', { timeout: 15_000 });
  // Its presence names its claims node only once the link is up, so it's on the strip once it can leave.
  const peer = page.locator(`#peers li.bee-peer[data-peer="${await identity}"]`);
  await expect(peer).toHaveAttribute('data-state', /^(here|acting|waiting)$/, { timeout: 30_000 });

  stopping.kill('SIGTERM');
  await exited;
  // A closed surface leaves the page; an open one would stay, its companion gone.
  await expect(page.locator(`a2ui-surface[data-surface-id="${ownSurface}"]`)).toHaveCount(0, { timeout: 5_000 });
  await expect(peer).toHaveAttribute('data-state', 'departed', { timeout: 60_000 });
  await expect(peer).toHaveAttribute('data-reason', 'left');
  await context.close();
});

// A companion whose adapter fails ends, and says so in state_dir, as the companion server does:
// it never goes on advertising itself. A second companion, on its own directories, loses its
// screen directory, so its first listing fails. It writes nothing to the board before that.
test('a companion whose adapter fails exits and says it stopped', async () => {
  test.setTimeout(90_000);
  const own = mkdtempSync(join(tmpdir(), 'bee-e2e-failing-'));
  const ownScreens = join(own, 'content');
  const ownState = join(own, 'state');
  mkdirSync(ownScreens, { recursive: true });
  const failing = spawn(companionBin, ['--screen-dir', ownScreens, '--state-dir', ownState, '--frame-template', template], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${port}` },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const exited = new Promise((resolve) => failing.on('exit', (code) => resolve(code)));
  await expect.poll(() => existsSync(join(ownState, 'server-info')), { timeout: 30_000 }).toBe(true);

  rmSync(ownScreens, { recursive: true });
  const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('still running'), 30_000))]);
  if (code === 'still running') failing.kill();
  expect(code).toBe(1);
  expect(existsSync(join(ownState, 'server-info'))).toBe(false);
  expect(JSON.parse(readFileSync(join(ownState, 'server-stopped'), 'utf8')).reason).toMatch(/^failed: /);
});
