// People and an agent talk in a Chat (bee#214). A page appends its person's message itself, with
// no agent in the loop, and every page shows it live. Two people sending at once both land, each
// once. An agent waiting on the Chat hears a message through bee_wait_action, and its reply,
// appended through bee_surface_update, shows on every page.
//
// The agent is a real bee-mcp bridge (agent.mjs). The pages write records, so the spec runs a host
// of its own (host.mjs). Each page's board socket goes through page.routeWebSocket, so a test can
// cut both and force two concurrent sends (context.setOffline leaves WebSockets open,
// microsoft/playwright#4912).
import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startAgent } from './agent.mjs';
import { Host, freePort } from './host.mjs';

const host = new Host(await freePort(), mkdtempSync(join(tmpdir(), 'bee-chat-')));
test.use({ baseURL: host.url });

const SURFACE = 'chat';
const SEEN = { timeout: 10_000 };

let agent;
let contexts = [];

test.beforeAll(async () => {
  test.setTimeout(120_000);
  await host.start();
});

test.afterAll(async () => {
  if (agent) await agent.kill();
  if (host.process) await host.stop();
});

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

/**
 * Opens `/` as `name` in a fresh context, its board socket routed through a switch: `cut` closes
 * the live socket and holds a redial until `restore`. notions.spec.mjs's `openPeer`, trimmed.
 */
async function openPeer(browser, name) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const link = { online: true, sockets: [], waiting: [] };
  const connect = (ws) => {
    const server = ws.connectToServer();
    ws.onMessage((m) => server.send(m));
    server.onMessage((m) => ws.send(m));
    link.sockets.push({ ws, server });
  };
  await page.routeWebSocket(/\/ws\/board/, (ws) => (link.online ? connect(ws) : link.waiting.push(ws)));
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
  return {
    page,
    async cut() {
      link.online = false;
      for (const { ws, server } of link.sockets.splice(0)) {
        await server.close().catch(() => {});
        await ws.close().catch(() => {});
      }
      await expect(page.locator('#status')).toHaveText(/^offline/, SEEN);
    },
    async restore() {
      link.online = true;
      for (const ws of link.waiting.splice(0)) connect(ws);
      await expect(page.locator('#status')).toHaveText(/^online/, { timeout: 15_000 });
    },
  };
}

const chat = (page) => page.locator(`a2ui-surface[data-surface-id="${SURFACE}"] bee-chat`);
const texts = (page) => chat(page).locator('li .text');
const authors = (page) => chat(page).locator('li .author');
const field = (page) => chat(page).locator('input[name="text"]');
const send = (page) => chat(page).locator('button[type="submit"]');

/** Types `text` into the page's Chat and presses Enter. */
async function say(page, text) {
  await field(page).fill(text);
  await field(page).press('Enter');
}

test('two people chat, a simultaneous send keeps both, and an agent hears a message and replies', async ({ browser }, testInfo) => {
  test.setTimeout(150_000);
  agent = await startAgent('chat-scout', { port: host.port });
  await agent.call('bee_surface_open', {
    surfaceId: SURFACE,
    title: 'Talk to the board',
    components: [{ id: 'root', component: 'Chat', messages: { path: '/messages' } }],
  });
  const alex = await openPeer(browser, 'alex');
  const sam = await openPeer(browser, 'sam');
  const pages = [alex.page, sam.page];
  await expect(chat(alex.page)).toBeVisible(SEEN);

  // An empty message isn't sent: Send is off, and Enter on a blank field records nothing.
  await expect(send(alex.page)).toBeDisabled();
  await say(alex.page, '   ');
  await say(alex.page, 'hello from alex');
  await expect(field(alex.page)).toHaveValue('');
  for (const page of pages) {
    await expect(texts(page)).toHaveText(['hello from alex'], SEEN);
    await expect(authors(page)).toHaveText(['alex']);
  }

  // Both send while cut off from each other: two concurrent appends. Both land, each once, in
  // the same order on every page. Markup in a message shows as text.
  await alex.cut();
  await sam.cut();
  await say(alex.page, '<b>alex, offline</b>');
  await say(sam.page, 'sam, offline');
  await alex.restore();
  await sam.restore();
  const both = ['hello from alex', '<b>alex, offline</b>', 'sam, offline'];
  for (const page of pages) {
    await expect(texts(page)).toHaveCount(3, SEEN);
    await expect(texts(page)).toContainText(['hello from alex']);
    expect((await texts(page).allTextContents()).toSorted()).toEqual(both.toSorted());
    await expect(chat(page).locator('b')).toHaveCount(0);
  }
  expect(await texts(alex.page).allTextContents()).toEqual(await texts(sam.page).allTextContents());

  // The agent waits past what it has seen, sam asks, and the wait hears the message.
  const { cursor } = await agent.call('bee_list_actions', { surfaceId: SURFACE });
  const waited = agent.call('bee_wait_action', { surfaceId: SURFACE, cursor, timeoutSeconds: 45 });
  await say(sam.page, 'scout, what next?');
  const { record } = await waited;
  expect(record?.name, 'the wait returned sam\'s message').toBe('send');
  expect(record.context.text).toBe('scout, what next?');
  expect(record.actor).toBe('human:sam');

  // Its reply, appended through the bridge, shows on both pages.
  await agent.call('bee_surface_update', {
    surfaceId: SURFACE,
    append: { '/messages': [{ id: 'reply-1', author: agent.identity, text: 'Ship the chat.', at: new Date().toISOString() }] },
  });
  await agent.call('bee_action_done', { recordId: record.recordId, token: record.token });
  for (const page of pages) {
    await expect(texts(page).last()).toHaveText('Ship the chat.', SEEN);
    await expect(authors(page).last()).toHaveText('chat-scout');
    await expect(texts(page)).toHaveCount(5);
  }
  await chat(sam.page).screenshot({ path: testInfo.outputPath('chat.png') });
});
