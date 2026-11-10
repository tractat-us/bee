// A Claude session's stand-in that joins over MCP's Streamable HTTP transport, as a teammate's
// Claude does with `claude mcp add --transport http bee '<origin>/mcp?k=<key>&name=<them>'` (#122):
// nothing installed, the host runs the bridge. The stdio twin is agent.mjs.
//
// A minimal client: each JSON-RPC request is one POST, answered as JSON or as an SSE stream that
// carries the answer. The session id the host hands back on `initialize` rides every later call.
// When the host no longer knows that id (it restarted), the call is answered 404; the client then
// starts a new session and retries once, as Claude Code reconnects on its next tool call.

/** How long a request waits for its answer: longer than `bee_wait_action`'s 60 s cap. */
const REQUEST_TIMEOUT_MS = 75_000;

const PROTOCOL = '2025-06-18';

/** An HTTP answer the client did not expect, with its status kept for a test to read. */
export class HttpRefusal extends Error {
  constructor(status, body, what) {
    super(`${what}: HTTP ${status}: ${body}`);
    this.status = status;
  }
}

/** The JSON-RPC answer to request [id] in an SSE body: the first `data:` event that names it. */
async function answerFromStream(response, id) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffered = '';
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffered += decoder.decode(value, { stream: true });
      let end;
      while ((end = buffered.search(/\r?\n\r?\n/)) >= 0) {
        const event = buffered.slice(0, end);
        buffered = buffered.slice(end).replace(/^\r?\n\r?\n/, '');
        const data = event.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
        if (!data) continue;
        const message = JSON.parse(data);
        if (message.id === id) return message;
      }
    }
  } finally {
    reader.cancel().catch(() => {});
  }
  throw new Error(`the SSE stream for request ${id} ended without its answer`);
}

/**
 * Joins the host at [url] (`http://<addr>:<port>/mcp?k=<key>&name=<name>`) over Streamable HTTP and
 * resolves to `{ identity, call(tool, args), end(), sessions, log }` once initialized:
 * - `identity` is the bridge's peer identity as `bee_presence` names it, read again after each
 *   new session (`identityNow()` re-reads it).
 * - `call` resolves to a tool's parsed JSON answer, re-initializing once on a 404.
 * - `sessions` counts the sessions this client has started: 2 after one reconnect.
 * - `end()` deletes the session, as a client that exits cleanly does.
 * - `log` is every status line the client saw, for a failure message.
 */
export async function startHttpAgent(url) {
  let sessionId = null;
  let next = 1;
  const log = [];
  const state = { sessions: 0 };

  const post = async (message, { expectAnswer = true } = {}) => {
    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'mcp-protocol-version': PROTOCOL,
    };
    if (sessionId) headers['mcp-session-id'] = sessionId;
    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(message),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    log.push(`${message.method} -> ${response.status} ${response.headers.get('content-type') ?? ''}`);
    if (!response.ok && response.status !== 202) {
      throw new HttpRefusal(response.status, await response.text(), message.method);
    }
    const id = response.headers.get('mcp-session-id');
    if (id) sessionId = id;
    if (!expectAnswer) {
      await response.body?.cancel();
      return null;
    }
    const type = response.headers.get('content-type') ?? '';
    const answer = type.includes('text/event-stream') ? await answerFromStream(response, message.id) : await response.json();
    if (answer.error) throw new Error(`${message.method}: ${JSON.stringify(answer.error)}`);
    return answer.result;
  };

  const request = (method, params) => post({ jsonrpc: '2.0', id: next++, method, params });

  const initialize = async () => {
    sessionId = null;
    // Stands in for a teammate's Claude Code, so the host names the session `<name>-claude` (#224).
    await request('initialize', { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: 'claude-code', version: '0' } });
    await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, { expectAnswer: false });
    state.sessions += 1;
  };

  const callOnce = async (tool, args) => {
    const result = await request('tools/call', { name: tool, arguments: args });
    const text = result.content?.[0]?.text ?? '';
    if (result.isError) throw new Error(`the HTTP agent's ${tool} was refused: ${text}`);
    return JSON.parse(text);
  };

  const call = async (tool, args = {}) => {
    try {
      return await callOnce(tool, args);
    } catch (failure) {
      if (!(failure instanceof HttpRefusal) || failure.status !== 404) throw failure;
      log.push(`session ${sessionId} is gone; starting a new one`);
      await initialize();
      return callOnce(tool, args);
    }
  };

  await initialize();
  const agent = {
    identity: null,
    call,
    get sessions() {
      return state.sessions;
    },
    log,
    async identityNow() {
      agent.identity = (await call('bee_presence', { status: 'here' })).as;
      return agent.identity;
    },
    async end() {
      if (!sessionId) return;
      const response = await fetch(url, {
        method: 'DELETE',
        headers: { 'mcp-session-id': sessionId, 'mcp-protocol-version': PROTOCOL },
      }).catch(() => null);
      log.push(`DELETE -> ${response?.status}`);
      sessionId = null;
    },
  };
  await agent.identityNow();
  return agent;
}
