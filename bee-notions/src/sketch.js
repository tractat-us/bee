// `Sketch`: agent-written HTML in a sandboxed frame (spec §5.6, plan Task 8). Invariant: the
// HTML runs only inside `<iframe sandbox="allow-scripts">`, never with allow-same-origin, under a
// CSP that allows no fetch, socket, image or frame load. The page acts only on messages whose
// source is that frame. The page's own CSP (bee-host's PAGE_CSP) keeps the frame from navigating
// to a URL.
//
// A sketch never records a choice: it only proposes one (bee#60). A click on a `[data-choice]`
// element inside it shows, on the page under that frame, "Send <text>?" with the page's own Send
// and Dismiss buttons. The choice reaches the board, as the A2UI action `choice` with context
// `{choice, text}`, only when the viewer clicks that Send. The frame reports a click in the MCP
// Apps (SEP-1865) View-to-Host envelope: a JSON-RPC 2.0 `ui/message` request (see
// sketch-helper.js), which this element answers once the viewer sends it, dismisses it, or the
// sketch replaces it.
//
// Not covered: WebRTC sits outside CSP, so a sketch can still reach the network, through an
// `RTCPeerConnection` (ICE to a STUN or TURN server it names). The helper deletes the WebRTC
// constructors, but that's a speed bump, not a boundary: a nested srcdoc frame gets a fresh
// `RTCPeerConnection`, and a closed declarative shadow root hides that frame from even a helper
// that removes nested frames. WebKit also opens a TCP connection for `<link rel=preconnect>`, and
// the browser may resolve a DNS name a sketch hints at (bee#49).
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicStringSchema, TextApi } from '@a2ui/web_core/v0_9';
import { sketchHelper } from './sketch-helper.js';

/**
 * No fetch, socket, frame or form loads: only the inline script and styles the sketch carries, and
 * data: images. CSP doesn't govern WebRTC, preconnect or DNS hints, so this isn't "no network"
 * (bee#49).
 */
export const SKETCH_CSP =
  "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:";

/** The `sandbox` the frame gets: scripts, and nothing else (no allow-same-origin, ever). */
export const SKETCH_SANDBOX = 'allow-scripts';

/** The A2UI action a choice becomes, and the record name bee-companion handles. */
export const CHOICE_ACTION = 'choice';

const CHOICE_META = 'us.tractat.bee/choice';

/**
 * How long Send stays disabled after a proposal appears or changes. The sketch decides when its
 * proposal changes, so without this it could swap the text, or slide the bar in, under a viewer's
 * click already on its way. Browsers hold their own permission prompts' buttons about as long.
 */
const SEND_ARM_MS = 500;

/**
 * Sketch's properties: `html`, and an optional `title` for the frame. The basic catalog's
 * common properties (`weight`, `accessibility`) come from `Text`'s schema, without its text.
 */
export const SketchApi = {
  name: 'Sketch',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      html: DynamicStringSchema.describe('The HTML shown in the sandboxed frame.'),
      title: DynamicStringSchema.describe("The frame's accessible title.").optional(),
    })
    .strict(),
};

/**
 * The frame's whole document: the CSP and the helper first, so they hold before any of the
 * sketch's own markup is parsed, then the sketch. A leading doctype moves to the front, so a full
 * document still renders in standards mode.
 */
export function sketchDocument(html) {
  const body = String(html).replace(/^\s*<!doctype[^>]*>/i, '');
  return (
    '<!DOCTYPE html>' +
    `<meta http-equiv="Content-Security-Policy" content="${SKETCH_CSP}">` +
    `<script>(${sketchHelper.toString()})();</` + 'script>' +
    body
  );
}

/** `<bee-sketch>`: one sandboxed frame showing its `html`, and the page's bar for its proposal. */
export class SketchElement extends A2uiLitElement {
  constructor() {
    super();
    this.frame = null;
    this.bar = null;
    this.shown = null;
    /** The choice the frame proposes, `{id, choice, text, at}`, or null. Never on the board. */
    this.proposal = null;
    this.armTimer = null;
    this.onMessage = (event) => this.receive(event);
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('message', this.onMessage);
  }

  disconnectedCallback() {
    window.removeEventListener('message', this.onMessage);
    this.drop();
    super.disconnectedCallback();
  }

  /** Lit renders nothing here; the frame and bar are managed in `updated`, outside Lit's part. */
  render() {
    return undefined;
  }

  updated(changed) {
    super.updated(changed);
    const props = this.controller?.props;
    if (!props) return;
    if (!this.frame) {
      const frame = document.createElement('iframe');
      frame.setAttribute('sandbox', SKETCH_SANDBOX);
      frame.style.cssText = 'display:block;width:100%;height:min(80vh,720px);border:0';
      this.insertBefore(frame, this.firstChild);
      this.frame = frame;
      this.bar = this.createBar();
      frame.after(this.bar);
    }
    this.frame.title = String(props.title ?? 'Sketch');
    const doc = sketchDocument(props.html ?? '');
    if (doc !== this.shown) {
      // A proposal belongs to the screen that made it: a new screen's viewer never saw it.
      this.drop();
      this.shown = doc;
      this.frame.srcdoc = doc;
    }
  }

  /**
   * The page's own bar under the frame: the proposal's text, as text, and Send and Dismiss. It's
   * outside the frame, so no sketch can reach it, and under its own frame, so a proposal is
   * always shown with the sketch that made it.
   */
  createBar() {
    const bar = document.createElement('div');
    bar.className = 'bee-proposal';
    bar.setAttribute('role', 'group');
    bar.setAttribute('aria-label', "This sketch's choice");
    bar.style.cssText = 'display:none;align-items:center;gap:0.5rem;padding:0.5rem 0;overflow-wrap:anywhere';
    const label = document.createElement('span');
    label.className = 'bee-proposal-text';
    const send = document.createElement('button');
    send.type = 'button';
    send.className = 'bee-proposal-send';
    send.textContent = 'Send';
    const dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.className = 'bee-proposal-dismiss';
    dismiss.textContent = 'Dismiss';
    // Only a real gesture counts: `isTrusted` is false for any event a script dispatched, and a
    // sketch's script can't reach the page to dispatch one anyway.
    send.addEventListener('click', (event) => { if (event.isTrusted) this.send(); });
    dismiss.addEventListener('click', (event) => { if (event.isTrusted) this.dismiss(); });
    bar.append(label, send, dismiss);
    return bar;
  }

  /** A message from this element's own frame, or nothing: any other window's is ignored. */
  receive(event) {
    if (!this.frame || event.source !== this.frame.contentWindow) return;
    const message = event.data;
    if (!message || typeof message !== 'object' || message.jsonrpc !== '2.0' || typeof message.method !== 'string') return;
    if (message.method === 'ui/message') {
      const params = message.params ?? {};
      const choice = params._meta?.[CHOICE_META];
      if (choice === undefined) {
        this.reply(message.id, { result: {} });
        return;
      }
      const text = params.content?.type === 'text' ? String(params.content.text ?? '') : '';
      this.propose(message.id, String(choice), text);
    } else {
      this.reply(message.id, { error: { code: -32601, message: `Method not found: ${message.method}` } });
    }
  }

  /**
   * Shows [choice] as this sketch's proposal, in place of any earlier one, which is answered as
   * replaced. It records nothing: only the viewer's Send does. Send is disabled for SEND_ARM_MS
   * after every change, and the bar never takes focus, so a keypress meant for something else
   * can't send it.
   */
  propose(id, choice, text) {
    if (this.proposal) this.reply(this.proposal.id, { error: { code: -32001, message: 'Replaced by a later choice.' } });
    this.proposal = { id, choice, text, at: performance.now() };
    const label = this.bar.querySelector('.bee-proposal-text');
    const shown = document.createElement('em');
    shown.textContent = text || choice;
    label.replaceChildren('Send ', shown, '?');
    const send = this.bar.querySelector('.bee-proposal-send');
    send.disabled = true;
    clearTimeout(this.armTimer);
    this.armTimer = setTimeout(() => { send.disabled = false; }, SEND_ARM_MS);
    this.bar.style.display = 'flex';
  }

  /** The viewer's Send: records the proposal shown, as theirs, and answers the frame. */
  send() {
    const proposal = this.proposal;
    if (!proposal || performance.now() - proposal.at < SEND_ARM_MS) return;
    this.context?.dispatchAction({ event: { name: CHOICE_ACTION, context: { choice: proposal.choice, text: proposal.text } } });
    this.reply(proposal.id, { result: {} });
    this.drop();
  }

  /** The viewer's Dismiss: the frame hears its choice was declined, and drops its selection. */
  dismiss() {
    if (!this.proposal) return;
    this.reply(this.proposal.id, { error: { code: -32000, message: 'Dismissed by the viewer.' } });
    this.drop();
  }

  /** Forgets the proposal, unanswered, and hides the bar. */
  drop() {
    this.proposal = null;
    clearTimeout(this.armTimer);
    if (this.bar) this.bar.style.display = 'none';
  }

  /** Answers request [id] (a notification, with no id, gets none). The frame's origin is opaque: '*'. */
  reply(id, outcome) {
    if (id === undefined || id === null) return;
    this.frame?.contentWindow?.postMessage({ jsonrpc: '2.0', id, ...outcome }, '*');
  }
}
SketchElement.tagName = 'bee-sketch';

/** The catalog entry: Sketch's API with its element, as the basic catalog's entries are built. */
export const Sketch = { ...SketchApi, tagName: SketchElement.tagName, element: SketchElement };
