// The demo, beat by beat (~/tractatus/bee-demo/RUNBOOK.md): what Iain shows, pinned so ongoing work
// can't silently break it. Each beat is a test.step, so a failure names the beat.
//
// It runs the demo's own way in: the kit `bin/bee-bundle` builds, unzipped, and its `bee-up` with a
// scratch BEE_HOME, not a raw bee-host. The owner's Claude is the stdio bridge the mcp.json that
// bee-up writes names; the teammate is a genuine peer off loopback, reaching the host at this
// machine's own network address with the key from the join link bee-up copied, and their Claude
// joins over HTTP MCP with the line their page's "Bring your Claude" offers (#122).
//
// bee-up copies the join link with `pbcopy`. A stand-in on PATH keeps it off the developer's
// clipboard and hands it to the test: the teammate opens the link the owner would send.
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startAgent } from './agent.mjs';
import { freePort } from './host.mjs';
import { startHttpAgent } from './http-agent.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

test.describe.configure({ mode: 'serial' });

const rig = {
  /** The unzipped kit: bee-up, bee-env, runtime/, bee-host/, bee-mcp/. */
  kit: null,
  beeHome: null,
  dataDir: null,
  port: null,
  /** The join link bee-up copied: `http://<this machine's address>:<port>/?k=<key>`. */
  link: null,
  key: null,
  owner: null,
  teammate: null,
  ownerPage: null,
  teammatePage: null,
  contexts: [],
  /** The HTTP agent's identity before and after the restart, for the #126 test. */
  httpIdentity: {},
};

/** Runs the kit's bee-up with [args] and returns what it printed. */
function beeUp(args) {
  const shims = join(rig.beeHome, 'shims');
  return execFileSync(join(rig.kit, 'bee-up'), args, {
    env: { ...process.env, BEE_HOME: rig.beeHome, PATH: `${shims}:${process.env.PATH}` },
    encoding: 'utf8',
    timeout: 120_000,
  });
}

/** The link bee-up last copied, or null. */
function clipboard() {
  const file = join(rig.beeHome, 'clipboard');
  return existsSync(file) ? readFileSync(file, 'utf8') : null;
}

test.beforeAll(async () => {
  test.setTimeout(240_000);
  const zip = execFileSync(join(repo, 'bin', 'bee-bundle'), [], { cwd: repo, encoding: 'utf8', timeout: 600_000 })
    .trim().split('\n').pop().split(' ')[0];
  const unpacked = mkdtempSync(join(tmpdir(), 'bee-demo-kit-'));
  execFileSync('unzip', ['-q', zip, '-d', unpacked], { timeout: 120_000 });
  rig.kit = join(unpacked, zip.split('/').pop().replace(/\.zip$/, ''));
  rig.beeHome = mkdtempSync(join(tmpdir(), 'bee-demo-home-'));
  rig.dataDir = join(rig.beeHome, 'demo');
  rig.port = Number(process.env.BEE_DEMO_PORT ?? await freePort());
  const shims = join(rig.beeHome, 'shims');
  mkdirSync(shims);
  writeFileSync(join(shims, 'pbcopy'), `#!/bin/sh\ncat > '${join(rig.beeHome, 'clipboard')}'\n`);
  chmodSync(join(shims, 'pbcopy'), 0o755);
});

test.afterEach(async ({}, testInfo) => {
  if (testInfo.status === testInfo.expectedStatus) return;
  const log = join(rig.beeHome ?? '/nonexistent', 'host.log');
  if (existsSync(log)) await testInfo.attach('host.log', { path: log, contentType: 'text/plain' });
  if (rig.owner) await testInfo.attach('owner bridge stderr', { body: rig.owner.said(), contentType: 'text/plain' });
  if (rig.teammate) await testInfo.attach('HTTP agent calls', { body: rig.teammate.log.join('\n'), contentType: 'text/plain' });
});

test.afterAll(async () => {
  await rig.teammate?.end();
  await rig.owner?.kill();
  for (const context of rig.contexts) await context.close().catch(() => {});
  if (rig.beeHome) beeUp(['--stop']);
});

const surface = (page, id) => page.locator(`a2ui-surface[data-surface-id="${id}"]`);
const button = (page, id, name) => surface(page, id).getByRole('button', { name });
const card = (page, id) => page.locator(`#board article.bee-card[data-surface-id="${id}"]`);
const peer = (page, who) => page.locator(`#peers li.bee-peer[data-peer="${who}"]`);
const records = (page, id) => page.locator(`#records li[data-surface-id="${id}"]`);
const pollOption = (page, id, label) => surface(page, id).locator('bee-poll li', { hasText: label });
const boardCard = (page, id, column, cardId) => surface(page, id).locator(`bee-board [data-column="${column}"] [data-card="${cardId}"]`);

async function online(page) {
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 30_000 });
}

async function openPage(browser, url) {
  const context = await browser.newContext();
  rig.contexts.push(context);
  const page = await context.newPage();
  await page.goto(url);
  await online(page);
  return page;
}

/** Waits until [agent]'s claims link is up: what departing, and claiming, need. */
async function claimsOnline(agent) {
  await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');
}

/**
 * Starts the owner's agent waiting on all its own surfaces, as the whiteboard prompt loops, and
 * resolves once the page shows it listening on [surfaceId]'s card. Returns the wait's promise.
 */
async function ownerListens(page, surfaceId) {
  const waited = rig.owner.call('bee_wait_action', { timeoutSeconds: 45 });
  await expect(card(page, surfaceId).locator('.bee-card-state')).toHaveText('listening', { timeout: 15_000 });
  return waited;
}

const MAIN = 'code-review';
const STEP_TEXT = 'Automated checks before human review';
const POLL = 'reviewer-poll';
const NEXT = 'next-steps';

test('the demo, beat by beat', async ({ browser }) => {
  test.setTimeout(240_000);

  await test.step('1. owner: bee-up from the kit, the page on localhost, the kit bridge draws a surface', async () => {
    const out = beeUp(['--name', 'iain', '--data-dir', rig.dataDir, '--port', String(rig.port), '--no-open']);
    expect(out).toContain(`bee board is up on port ${rig.port}.`);
    rig.link = clipboard();
    expect(rig.link, `bee-up copied no join link. It printed:\n${out}`).toMatch(new RegExp(`^http://[0-9.]+:${rig.port}/\\?k=[A-Za-z0-9_-]+$`));
    rig.key = new URL(rig.link).searchParams.get('k');

    // The owner's Claude runs the bridge bee-up's mcp.json names: the kit's, on the kit's runtime.
    const server = JSON.parse(readFileSync(join(rig.beeHome, 'mcp.json'), 'utf8')).mcpServers.bee;
    expect(server.command).toBe(join(rig.kit, 'bee-mcp', 'bin', 'bee-mcp'));
    expect(server.env.JAVA_HOME).toBe(join(rig.kit, 'runtime'));
    rig.owner = await startAgent(server.env.BEE_SESSION_NAME, { server });
    expect(rig.owner.identity).toMatch(/^agent:iain-claude#/);

    rig.ownerPage = await openPage(browser, `http://localhost:${rig.port}/?name=iain`);
    await rig.owner.call('bee_surface_open', {
      surfaceId: MAIN,
      title: 'How we review code',
      components: [
        { id: 'root', component: 'Column', children: ['ask', 'checks', 'done'] },
        { id: 'ask', component: 'Text', variant: 'h4', text: 'What comes before human review?' },
        { id: 'checks', component: 'Button', child: 'checks-label', action: { event: { name: 'answer', context: { choice: 'checks' } } } },
        { id: 'checks-label', component: 'Text', text: 'Automated checks' },
        { id: 'done', component: 'Button', child: 'done-label', action: { event: { name: 'done', context: {} } } },
        { id: 'done-label', component: 'Text', text: 'Done' },
      ],
    });
    await expect(surface(rig.ownerPage, MAIN)).toContainText('What comes before human review?', { timeout: 15_000 });
    await claimsOnline(rig.owner);
  });

  await test.step('2. teammate: opens the join link with its key, off loopback; their Claude joins over HTTP MCP', async () => {
    rig.teammatePage = await openPage(browser, `${rig.link}&name=andrew`);
    await expect(surface(rig.teammatePage, MAIN)).toContainText('What comes before human review?', { timeout: 15_000 });

    // "Bring your Claude": the line the teammate pastes, with this tab's key and name in its URL.
    await rig.teammatePage.locator('#bar #bring-claude-toggle').click();
    const line = await rig.teammatePage.locator('#bring-claude-command').textContent();
    const mcpUrl = line.match(/--transport http bee '([^']+)'/)?.[1];
    expect(mcpUrl, `no MCP URL in the line: ${line}`).toBe(`${new URL(rig.link).origin}/mcp?k=${rig.key}&name=andrew`);
    await rig.teammatePage.keyboard.press('Escape');

    rig.teammate = await startHttpAgent(mcpUrl);
    rig.httpIdentity.before = rig.teammate.identity;
    expect(rig.teammate.identity).toMatch(/^agent:andrew-claude#/);
    await expect(peer(rig.ownerPage, rig.teammate.identity)).toBeVisible({ timeout: 15_000 });
  });

  await test.step("3. two agents at once: the teammate's agent edits the owner's surface; a click reaches the owner's agent", async () => {
    await rig.teammate.call('bee_surface_update', {
      surfaceId: MAIN,
      components: [
        { id: 'root', component: 'Column', children: ['ask', 'step', 'checks', 'done'] },
        { id: 'step', component: 'Text', text: STEP_TEXT },
      ],
    });
    for (const page of [rig.ownerPage, rig.teammatePage]) await expect(surface(page, MAIN)).toContainText(STEP_TEXT, { timeout: 15_000 });

    const waited = await ownerListens(rig.teammatePage, MAIN);
    const before = await records(rig.ownerPage, MAIN).count();
    await button(rig.teammatePage, MAIN, 'Automated checks').click();
    // The click is on the board, so a wait that misses it is the agent's side, not the page's.
    await expect(records(rig.ownerPage, MAIN)).toHaveCount(before + 1, { timeout: 15_000 });
    const result = await waited;
    expect(result.record, `the owner's wait returned the teammate's click: ${JSON.stringify(result)}`).toMatchObject({ surfaceId: MAIN, name: 'answer' });
    const { record } = result;
    expect(await rig.owner.call('bee_action_done', { recordId: record.recordId, token: record.token })).toEqual({ done: record.recordId });
  });

  await test.step('4. poll and board: both pages vote and see the counts; a card moves on both', async () => {
    await rig.teammate.call('bee_surface_open', {
      surfaceId: POLL,
      title: 'Who picks the reviewer?',
      components: [{ id: 'root', component: 'Poll', question: { path: '/question' }, options: { path: '/options' }, answers: { path: '/answers' } }],
      data: { answers: {} },
      append: { '/question': ['Who picks the reviewer?'], '/options': ['the author', 'round-robin', 'CODEOWNERS'] },
    });
    await rig.teammate.call('bee_surface_open', {
      surfaceId: NEXT,
      title: 'Next steps',
      components: [{ id: 'root', component: 'Board', columns: { path: '/columns' }, cards: { path: '/cards' } }],
      data: {
        columns: { items: { todo: { title: 'To do' }, doing: { title: 'Doing' }, done: { title: 'Done' } }, order: ['todo', 'doing', 'done'] },
        cards: { items: { checks: { label: 'Automated checks', column: 'todo' } }, order: ['checks'] },
      },
    });

    await pollOption(rig.ownerPage, POLL, 'round-robin').locator('button').click();
    await pollOption(rig.teammatePage, POLL, 'CODEOWNERS').locator('button').click();
    for (const page of [rig.ownerPage, rig.teammatePage]) {
      await expect(surface(page, POLL).locator('bee-poll .total')).toHaveText('2 answers', { timeout: 15_000 });
      await expect(pollOption(page, POLL, 'round-robin').locator('.count')).toHaveText('1');
      await expect(pollOption(page, POLL, 'CODEOWNERS').locator('.count')).toHaveText('1');
      await expect(pollOption(page, POLL, 'the author').locator('.count')).toHaveText('0');
    }

    await boardCard(rig.teammatePage, NEXT, 'todo', 'checks').dragTo(surface(rig.teammatePage, NEXT).locator('bee-board [data-column="doing"]'));
    for (const page of [rig.ownerPage, rig.teammatePage]) {
      await expect(boardCard(page, NEXT, 'doing', 'checks')).toBeVisible({ timeout: 15_000 });
      await expect(boardCard(page, NEXT, 'todo', 'checks')).toHaveCount(0);
    }
  });

  await test.step('5. restart: bee-up --stop, bee-up on the same data dir; the board, both pages and both agents come back', async () => {
    const stopped = beeUp(['--stop']);
    expect(stopped).toContain('bee host stopped.');
    // Both pages saw it go: the restart is real, not a no-op.
    for (const page of [rig.ownerPage, rig.teammatePage]) await expect(page.locator('#status')).toHaveText(/^offline/, { timeout: 15_000 });

    const out = beeUp(['--name', 'iain', '--data-dir', rig.dataDir, '--port', String(rig.port), '--no-open']);
    expect(out).toMatch(/restored as .*: [1-9][0-9]* surfaces/);
    // The same join link: keys survive a restart (#109).
    expect(clipboard()).toBe(rig.link);

    // Both pages come back by themselves, no reload, and show the board as it was.
    for (const page of [rig.ownerPage, rig.teammatePage]) {
      await online(page);
      await expect(surface(page, MAIN)).toContainText(STEP_TEXT, { timeout: 15_000 });
      await expect(surface(page, POLL).locator('bee-poll .total')).toHaveText('2 answers', { timeout: 15_000 });
      await expect(boardCard(page, NEXT, 'doing', 'checks')).toBeVisible({ timeout: 15_000 });
    }

    // The stdio bridge keeps its identity, and a click on its pre-restart surface still reaches it.
    const before = rig.owner.identity;
    await claimsOnline(rig.owner);
    expect((await rig.owner.call('bee_presence', { status: 'back after the restart' })).as).toBe(before);
    const waited = await ownerListens(rig.teammatePage, MAIN);
    await button(rig.teammatePage, MAIN, 'Automated checks').click();
    const { record } = await waited;
    expect(record, "the click after the restart reached the owner's agent").toMatchObject({ surfaceId: MAIN, name: 'answer' });
    await rig.owner.call('bee_action_done', { recordId: record.recordId, token: record.token });

    // The HTTP agent reconnects on its next call: one new session, and it works.
    const board = await rig.teammate.call('bee_board', {});
    expect(board.surfaces.map((s) => s.surfaceId)).toEqual(expect.arrayContaining([MAIN, POLL, NEXT]));
    expect(rig.teammate.sessions).toBe(2);
    rig.httpIdentity.after = await rig.teammate.identityNow();
  });

  await test.step("6. done: a click on Done reaches the owner's agent, which leaves a summary and shows left", async () => {
    const waited = await ownerListens(rig.ownerPage, MAIN);
    await button(rig.ownerPage, MAIN, 'Done').click();
    const { record } = await waited;
    expect(record).toMatchObject({ surfaceId: MAIN, name: 'done' });
    await rig.owner.call('bee_action_done', { recordId: record.recordId, token: record.token });
    await rig.owner.call('bee_surface_open', {
      surfaceId: 'code-review-summary',
      title: 'What we decided',
      components: [{ id: 'root', component: 'Text', text: '**Decided:** automated checks run before human review.' }],
    });
    await rig.owner.call('bee_presence', {});
    for (const page of [rig.ownerPage, rig.teammatePage]) {
      await expect(surface(page, 'code-review-summary')).toContainText('automated checks run before human review', { timeout: 15_000 });
    }

    const who = rig.owner.identity;
    await rig.owner.end('stdin');
    for (const page of [rig.ownerPage, rig.teammatePage]) {
      await expect(peer(page, who)).toHaveAttribute('data-reason', 'left', { timeout: 15_000 });
      await expect(card(page, MAIN).locator('.bee-card-gone')).toHaveText('left');
    }
  });
});

test('7. a teammate without the key, or with a wrong one, is refused', async () => {
  test.skip(!rig.link, 'the demo test did not get as far as a join link');
  const origin = new URL(rig.link).origin;
  const initialize = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'bee-e2e-mallory', version: '0' } } });
  const mcp = (query) => fetch(`${origin}/mcp?${query}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: initialize,
  });
  const board = await (await fetch(`${origin}/board-id`)).text();
  const socket = (query) => fetch(`${origin}/ws/board?board=${board}&${query}`);

  const statuses = {
    mcpNoKey: (await mcp('name=mallory')).status,
    mcpWrongKey: (await mcp('name=mallory&k=not-the-key')).status,
    socketNoKey: (await socket('name=mallory')).status,
    socketWrongKey: (await socket('k=not-the-key')).status,
    // Positive controls: the same calls with the key get past the gate.
    mcpKey: await mcp(`name=mallory&k=${rig.key}`).then(async (r) => {
      // Ends the session it opened, so no stray bridge stays on the board.
      const id = r.headers.get('mcp-session-id');
      await r.body?.cancel();
      if (id) await fetch(`${origin}/mcp?k=${rig.key}`, { method: 'DELETE', headers: { 'mcp-session-id': id } });
      return r.status;
    }),
    socketKey: (await socket(`k=${rig.key}`)).status,
  };
  const { socketKey, ...rest } = statuses;
  expect(rest).toEqual({ mcpNoKey: 403, mcpWrongKey: 403, socketNoKey: 403, socketWrongKey: 403, mcpKey: 200 });
  // A plain GET is no WebSocket upgrade, so past the gate it is refused some other way.
  expect(socketKey, 'with the key, the board socket gets past the gate').not.toBe(403);
});

// #126: an HTTP-joined Claude comes back from a host restart as a new identity, so its cards from
// before read as muted. This pins today's behaviour. Whoever lands #126 sees this test "pass
// unexpectedly" and fails the run: delete the `test.fail` line then.
test('an HTTP-joined agent keeps its identity across a host restart (#126)', async () => {
  test.fail(true, '#126: today the HTTP agent comes back as a new identity');
  test.skip(!rig.httpIdentity.after, 'the demo test did not get as far as the restart');
  expect(rig.httpIdentity.after).toBe(rig.httpIdentity.before);
});
