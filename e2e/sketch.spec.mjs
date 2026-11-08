// Sketch and the brainstorming companion on bee (Task 8, spike 5). A real bee-companion, built
// by `:bee-companion:installDist`, joins the host Playwright started, over temporary screen and
// state directories, as a brainstorming session would point it. Each test pushes its own
// screen; the newest one is the Sketch on the `companion` surface.
import { test, expect } from '@playwright/test';
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
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

const sketchIframe = (page) => page.locator('a2ui-surface[data-surface-id="companion"] bee-sketch iframe');
const sketchFrame = (page) => page.frameLocator('a2ui-surface[data-surface-id="companion"] bee-sketch iframe');
const companionRecords = (page) => page.locator('#records li[data-surface-id="companion"]');

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
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  const event = JSON.parse(eventLines()[0]);
  expect(Object.keys(event)).toEqual(['type', 'choice', 'text', 'timestamp']);
  expect(event).toMatchObject({ type: 'click', choice: 'b', text: 'Option B' });
  expect(Math.abs(event.timestamp - Date.now() / 1000)).toBeLessThan(60);
  await sam.context.close();
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
    '  document.getElementById("probe").textContent = JSON.stringify(out);' +
    '})();</script>');
  const context = await browser.newContext();
  const page = await context.newPage();
  // Chromium reports a load the CSP blocked as a request that failed, never sent. So what counts
  // is a probe that got an answer from the host (a 404 is an answer) or a socket that opened.
  const reached = [];
  const blocked = [];
  page.on('response', (response) => { if (response.url().includes('sketch-probe')) reached.push(response.url()); });
  page.on('requestfailed', (request) => {
    if (request.url().includes('sketch-probe')) blocked.push(`${request.url()} ${request.failure()?.errorText}`);
  });
  page.on('websocket', (ws) => { if (ws.url().includes('sketch-probe')) reached.push(ws.url()); });
  await page.goto('/?name=kim');
  const frame = sketchFrame(page);
  await expect(frame.locator('#probe')).not.toHaveText('running', { timeout: 15_000 });
  const outcome = JSON.parse(await frame.locator('#probe').textContent());

  expect(await sketchIframe(page).getAttribute('sandbox')).toBe('allow-scripts');
  expect(outcome.parent).toBe('blocked');
  expect(outcome.fetch).toBe('blocked');
  // CSP blocks a WebSocket either by throwing or by never sending it; the request log decides.
  await page.waitForTimeout(1_000);
  expect(reached).toEqual([]);
  // Control: the image load was attempted and refused, so the probe could have seen one.
  expect(blocked.some((b) => b.includes('/img.png'))).toBe(true);
  // The parent's DOM is untouched by the sketch: the page still shows the board as before.
  await expect(page.locator('#status')).toHaveText(/^online/);
  await context.close();
});

/** Opens the board as `name`, logging every sketch-probe the host answered into `reached`. */
async function openProbed(browser, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const reached = [];
  page.on('response', (response) => { if (response.url().includes('sketch-probe')) reached.push(response.url()); });
  const response = await page.goto(`/?name=${name}`);
  return { context, page, reached, csp: response.headers()['content-security-policy'] };
}

// The sandbox and the frame's own CSP can't stop a frame navigating itself: only the embedding
// page's frame-src can (bee-host's PAGE_CSP). Each probe answers 404 if it reaches the host.
test('sketch cannot navigate its frame to a URL', async ({ browser }) => {
  const probe = `http://127.0.0.1:${port}/sketch-probe`;
  pushScreen('nav-link.html', `<h2>Link probe</h2><a id="away" href="${probe}/link">away</a>`);
  const link = await openProbed(browser, 'lee');
  await expect(sketchFrame(link.page).locator('#away')).toBeVisible({ timeout: 15_000 });
  await sketchFrame(link.page).locator('#away').click();
  await link.page.waitForTimeout(1_500);
  expect(link.csp).toBe("frame-src 'none'; child-src 'none'");
  expect(link.reached).toEqual([]);
  await link.context.close();

  pushScreen('nav-location.html', '<h2>Location probe</h2>' +
    `<script>setTimeout(() => { location = "${probe}/location"; }, 300);</` + 'script>');
  const located = await openProbed(browser, 'lou');
  await expect(sketchFrame(located.page).locator('body')).toContainText('Location probe', { timeout: 15_000 });
  await located.page.waitForTimeout(1_500);
  expect(located.reached).toEqual([]);
  // The page itself is untouched: still online, over the WebSocket the header never touched.
  await expect(located.page.locator('#status')).toHaveText(/^online/);
  await located.context.close();
});

// A choice is recorded as the viewer's, so a sketch can't make one by itself: only right after a
// real click, and one per click however many messages that click sends.
test('a sketch that posts at load creates no record', async ({ browser }) => {
  // The sketch posts a forged choice every 100 ms for its first 1.5 s, and ten more right after
  // the helper sends the real click's.
  pushScreen('auto.html', '<h2>Auto poster</h2>' +
    '<div class="options"><div class="option" id="real" data-choice="real">Real</div></div><script>' +
    'const forge = (choice) => parent.postMessage({ jsonrpc: "2.0", id: 900, method: "ui/message", params: ' +
    '{ role: "user", content: { type: "text", text: choice }, _meta: { "us.tractat.bee/choice": choice } } }, "*");' +
    'let n = 0; const t = setInterval(() => { forge("auto"); if (++n >= 15) clearInterval(t); }, 100);' +
    'document.getElementById("real").addEventListener("click", () => setTimeout(() => { for (let i = 0; i < 10; i++) forge("burst"); }, 0));' +
    '</' + 'script>');
  const kit = await openBoard(browser, 'kit', 'Auto poster');
  const before = await companionRecords(kit.page).count();
  await expect.poll(() => existsSync(eventsFile), { timeout: 5_000 }).toBe(false);
  await kit.page.waitForTimeout(2_000);
  expect(await companionRecords(kit.page).count()).toBe(before);
  expect(eventLines()).toEqual([]);

  // Control: a real click goes through, once, though it sends eleven messages.
  await kit.frame.locator('[data-choice="real"]').click();
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

  // Control: a real click from the sketch itself goes through, after the forgeries.
  await iain.frame.locator('[data-choice="a"]').click();
  await expect.poll(eventLines, { timeout: 10_000 }).toHaveLength(1);
  expect(JSON.parse(eventLines()[0])).toMatchObject({ choice: 'a', text: 'Option A' });
  await iain.context.close();
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
