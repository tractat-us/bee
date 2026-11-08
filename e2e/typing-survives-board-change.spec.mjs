// bee#43: a surface opening, changing or closing elsewhere on the board leaves the field someone
// is typing in alone. A real bee-companion is the agent: it opens its own Sketch surface, shows a
// second screen on it, and closes it, while ines keeps typing in another surface's field.
//
// The caret is never moved with Playwright's End, and the field is never re-focused with a
// locator action: on macOS, End moves the caret only when the page can't scroll, and a locator
// press or type on an unfocused input sets its caret to 0 first (bee#43). Keys go through
// page.keyboard into the field that already has focus.
import { test, expect } from '@playwright/test';
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, utimesSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const companionBin = join(repo, 'bee-companion/build/install/bee-companion/bin/bee-companion');
const TEMPLATE = '<!DOCTYPE html><html><head></head><body><div class="header"><!-- BRANDING -->' +
  '<div class="status">Connecting</div></div><div id="frame-content"><!-- CONTENT --></div></body></html>';

let companion;
let exited;
let screenDir;
let screens = 0;

test.beforeAll(async () => {
  test.setTimeout(600_000);
  const dirs = mkdtempSync(join(tmpdir(), 'bee-e2e-typing-'));
  screenDir = join(dirs, 'content');
  const stateDir = join(dirs, 'state');
  const template = join(dirs, 'frame-template.html');
  mkdirSync(screenDir, { recursive: true });
  mkdirSync(stateDir, { recursive: true });
  writeFileSync(template, TEMPLATE);
  execFileSync(join(repo, 'gradlew'), [':bee-companion:installDist', '--console=plain', '--quiet'], {
    cwd: repo,
    timeout: 540_000,
    stdio: 'inherit',
  });
  companion = spawn(companionBin, ['--screen-dir', screenDir, '--state-dir', stateDir, '--frame-template', template], {
    env: { ...process.env, BEE_HOST: `ws://127.0.0.1:${process.env.BEE_E2E_PORT}` },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  exited = new Promise((resolve) => companion.once('exit', resolve));
  companion.lines = [];
  companion.stdout.on('data', (chunk) => companion.lines.push(...chunk.toString().split('\n').filter(Boolean)));
  await expect.poll(() => companion.lines.some((l) => l.includes('server-started')), { timeout: 60_000 }).toBe(true);
  const kept = join(stateDir, 'bee-companion.json');
  await expect.poll(() => existsSync(kept), { timeout: 60_000 }).toBe(true);
  companion.surface = JSON.parse(readFileSync(kept, 'utf8')).surface;
});

// SIGTERM: the companion closes its own surface in its shutdown hook, before it exits. Waiting for
// the exit keeps the next spec from starting while that Sketch is still on the board.
test.afterAll(async () => {
  if (!companion) return;
  companion.kill('SIGTERM');
  await exited;
});

/** Writes screen `name`, newer than every screen before it. */
function pushScreen(name, html) {
  const file = join(screenDir, name);
  writeFileSync(file, html);
  const at = Date.now() / 1000 + ++screens;
  utimesSync(file, at, at);
}

/**
 * Marks `element` and records, in the page, every removal of it or of an ancestor, so a later
 * read can tell the same DOM node from a re-created one with the same text.
 */
async function watch(locator, tag) {
  await locator.evaluate((element, tag) => {
    window.__watched ??= {};
    window.__watched[tag] = { element, removed: 0 };
    element.dataset.watchTag = tag;
    new MutationObserver((records) => {
      for (const r of records) {
        for (const n of r.removedNodes) if (n === element || n.contains?.(element)) window.__watched[tag].removed++;
      }
    }).observe(document, { childList: true, subtree: true });
  }, tag);
}

/** What became of the element `watch` marked as `tag`, read through `locator`. */
function fate(locator, tag) {
  return locator.evaluate((current, tag) => {
    const watched = window.__watched[tag];
    return {
      same: current === watched.element,
      connected: watched.element.isConnected,
      removed: watched.removed,
      focused: document.activeElement === watched.element,
      value: watched.element.value,
      caret: [watched.element.selectionStart, watched.element.selectionEnd],
    };
  }, tag);
}

test('a surface opening, changing and closing elsewhere leaves the typing field in place', async ({ browser }) => {
  test.setTimeout(60_000);
  const context = await browser.newContext();
  let otherContext;
  try {
    const page = await context.newPage();
    await page.goto('/?name=ines');
    await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    const sketch = page.locator(`a2ui-surface[data-surface-id="${companion.surface}"] bee-sketch iframe`);
    const sketchSurface = page.locator(`a2ui-surface[data-surface-id="${companion.surface}"]`);
    // This spec's own fixture, seeded empty: no other spec types in it.
    const fieldOf = (p) => p.locator('a2ui-surface[data-surface-id="board-change"] a2ui-basic-textfield input');
    const field = fieldOf(page);
    await expect(field).toHaveValue('');

    await field.evaluate((input) => input.focus());
    await page.keyboard.type('a');
    await watch(field, 'field');
    const typed = (s) => ({ same: true, connected: true, removed: 0, focused: true, value: s, caret: [s.length, s.length] });

    // A surface opens elsewhere.
    pushScreen('one.html', '<h2>First screen</h2>');
    await expect(sketch).toHaveCount(1, { timeout: 15_000 });
    await watch(sketchSurface, 'sketch');
    await page.keyboard.type('b');
    expect(await fate(field, 'field')).toEqual(typed('ab'));

    // It changes.
    pushScreen('two.html', '<h2>Second screen</h2>');
    await expect(page.frameLocator(`a2ui-surface[data-surface-id="${companion.surface}"] bee-sketch iframe`).locator('h2'))
      .toHaveText('Second screen', { timeout: 15_000 });
    await page.keyboard.type('c');
    expect(await fate(field, 'field')).toEqual(typed('abc'));

    // It closes, and opens again: the renderer drops that surface's element and mounts a new one.
    pushScreen('waiting.html', '<p>Continuing in terminal...</p>');
    await expect(sketchSurface).toHaveCount(0, { timeout: 15_000 });
    await page.keyboard.type('d');
    expect(await fate(field, 'field')).toEqual(typed('abcd'));
    pushScreen('three.html', '<h2>Third screen</h2>');
    await expect(sketch).toHaveCount(1, { timeout: 15_000 });
    await page.keyboard.type('e');
    expect(await fate(field, 'field')).toEqual(typed('abcde'));

    // Control: the watch sees a re-created element. The sketch's surface, closed and reopened, is
    // a new node, and its old one was removed.
    const control = await fate(sketchSurface, 'sketch');
    expect(control).toMatchObject({ same: false, connected: false, removed: 1 });

    // And the typing reached the board: a second page sees it once ines leaves the field.
    await page.locator('#status').click();
    otherContext = await browser.newContext();
    const other = await otherContext.newPage();
    await other.goto('/?name=otto');
    await expect(fieldOf(other)).toHaveValue('abcde', { timeout: 5_000 });
  } finally {
    await otherContext?.close();
    await context.close();
  }
});
