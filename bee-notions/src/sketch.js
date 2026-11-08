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
// The frame fits its document's height, within [SKETCH_MIN_HEIGHT_PX, SKETCH_MAX_HEIGHT], from the
// helper's `ui/notifications/size-changed` notifications. A sketch so moves Send, its own and every
// Send below it, so each fit re-arms every shown proposal at once (see `fit`).
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

/** The shortest a frame gets, however little its document holds. */
export const SKETCH_MIN_HEIGHT_PX = 120;

/** The tallest a frame gets, and its height until its document reports one; past it, the frame scrolls. */
export const SKETCH_MAX_HEIGHT = 'min(80vh, 720px)';

/** Every connected `<bee-sketch>`, so one frame's fit can re-arm the Send of every other. */
const connected = new Set();

/** The A2UI action a choice becomes, and the record name bee-companion handles. */
export const CHOICE_ACTION = 'choice';

const CHOICE_META = 'us.tractat.bee/choice';

/**
 * How long Send stays disabled after a proposal appears or changes, or after Send moves. The
 * sketch decides when its proposal changes, and it can scroll the page (scrollIntoView reaches
 * the parent in Chromium), so without this it could swap the text, or slide Send, under a
 * viewer's click already on its way. It's also how long a key press on Send is ignored after Send
 * takes focus. Browsers hold their own permission prompts' buttons about as long.
 */
const SEND_ARM_MS = 500;

/** Whether two `DOMRect`s are the same box. */
const sameRect = (a, b) =>
  !!a && !!b && a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height;

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
    /** Send's box when arming last began: Send arms only if it's still there. */
    this.armRect = null;
    /** When Send last took focus, for the key press that follows Tab out of a sketch. */
    this.focusedAt = -Infinity;
    /** The height the frame was last fitted to, as CSS. */
    this.fitted = null;
    this.onMessage = (event) => this.receive(event);
    // Anything that may have moved Send re-arms it: a scroll anywhere on the page, a resize, or
    // a change in the size of the bar, this sketch or the page.
    this.onMove = () => { if (this.proposal) this.arm(); };
    this.resizes = new ResizeObserver(this.onMove);
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  connectedCallback() {
    super.connectedCallback();
    connected.add(this);
    window.addEventListener('message', this.onMessage);
    window.addEventListener('scroll', this.onMove, true);
    window.addEventListener('resize', this.onMove);
    this.resizes.observe(this);
    this.resizes.observe(document.body);
    if (this.bar) this.resizes.observe(this.bar);
  }

  disconnectedCallback() {
    connected.delete(this);
    window.removeEventListener('message', this.onMessage);
    window.removeEventListener('scroll', this.onMove, true);
    window.removeEventListener('resize', this.onMove);
    this.resizes.disconnect();
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
      frame.style.cssText = `display:block;width:100%;height:${SKETCH_MAX_HEIGHT};border:0`;
      this.insertBefore(frame, this.firstChild);
      this.frame = frame;
      this.bar = this.createBar();
      frame.after(this.bar);
      this.resizes.observe(this.bar);
    }
    this.frame.title = String(props.title ?? 'Sketch');
    this.bar.querySelector('.bee-proposal-from').textContent = this.frame.title;
    this.bar.setAttribute('aria-label', `${this.frame.title}: its choice`);
    const doc = sketchDocument(props.html ?? '');
    if (doc !== this.shown) {
      // A proposal belongs to the screen that made it: a new screen's viewer never saw it.
      this.drop();
      this.shown = doc;
      this.frame.srcdoc = doc;
    }
  }

  /**
   * The page's own bar under the frame: the sketch's title, the proposal's text, as text, and Send
   * and Dismiss. It's outside the frame, so no sketch can reach it, and joined to the bottom of its
   * own frame, so a proposal is always shown with the sketch that made it.
   */
  createBar() {
    const bar = document.createElement('div');
    bar.className = 'bee-proposal';
    bar.setAttribute('role', 'group');
    bar.style.cssText = 'display:none;align-items:center;gap:0.5rem;padding:0.5rem 0.75rem;' +
      'overflow-wrap:anywhere;border:1px solid #8888;border-top:3px solid #888;border-radius:0 0 6px 6px';
    const from = document.createElement('strong');
    from.className = 'bee-proposal-from';
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
    send.addEventListener('click', (event) => {
      if (!event.isTrusted) return;
      // Tab out of a sketch lands on its Send, so a key press meant for the sketch would send it.
      // A keyboard activation (detail 0) counts once Send has had focus for SEND_ARM_MS.
      if (event.detail === 0 && performance.now() - this.focusedAt < SEND_ARM_MS) return;
      this.send();
    });
    send.addEventListener('focus', () => { this.focusedAt = performance.now(); });
    // A held key repeats: only a fresh press may send.
    send.addEventListener('keydown', (event) => { if (event.repeat) event.preventDefault(); });
    dismiss.addEventListener('click', (event) => { if (event.isTrusted) this.dismiss(); });
    bar.append(from, label, send, dismiss);
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
    } else if (message.method === 'ui/notifications/size-changed') {
      this.fit(message.params?.height);
    } else {
      this.reply(message.id, { error: { code: -32601, message: `Method not found: ${message.method}` } });
    }
  }

  /**
   * Shows [choice] as this sketch's proposal, in place of any earlier one, which is answered as
   * replaced. It records nothing: only the viewer's Send does. Send re-arms (see `arm`) on every
   * change, and a key press on Send counts only once Send has had focus for SEND_ARM_MS, so a
   * click or key press meant for something else can't send it.
   */
  propose(id, choice, text) {
    if (this.proposal) this.reply(this.proposal.id, { error: { code: -32001, message: 'Replaced by a later choice.' } });
    this.proposal = { id, choice, text };
    const label = this.bar.querySelector('.bee-proposal-text');
    const shown = document.createElement('em');
    shown.textContent = text || choice;
    label.replaceChildren('Send ', shown, '?');
    this.bar.style.display = 'flex';
    this.arm();
  }

  /**
   * Fits the frame to a document [height] tall, in CSS pixels, within SKETCH_MIN_HEIGHT_PX and
   * SKETCH_MAX_HEIGHT. A new height moves this sketch's Send and every Send below it, and when the
   * page is shorter than the window a move changes no element's size, so no ResizeObserver hears
   * it. So every shown proposal re-arms here, in the same task that moved it, before any click
   * can land on a Send in its new place.
   */
  fit(height) {
    if (typeof height !== 'number' || !Number.isFinite(height) || height < 0) return;
    // The frame's own border is inside its height (border-box): add it, or the document scrolls.
    const border = this.frame.offsetHeight - this.frame.clientHeight;
    const css = `clamp(${SKETCH_MIN_HEIGHT_PX}px, ${Math.ceil(height) + border}px, ${SKETCH_MAX_HEIGHT})`;
    if (css === this.fitted) return;
    this.fitted = css;
    this.frame.style.height = css;
    for (const sketch of connected) sketch.onMove();
  }

  /**
   * Disables Send, and enables it SEND_ARM_MS later only if Send is still where it was when
   * arming began; otherwise arming starts again. A sketch can't move Send under a click on its way.
   */
  arm() {
    const send = this.bar.querySelector('.bee-proposal-send');
    send.disabled = true;
    this.armRect = send.getBoundingClientRect();
    clearTimeout(this.armTimer);
    this.armTimer = setTimeout(() => {
      if (sameRect(send.getBoundingClientRect(), this.armRect)) send.disabled = false;
      else this.arm();
    }, SEND_ARM_MS);
  }

  /**
   * The viewer's Send: records the proposal shown, as theirs, and answers the frame. Send must be
   * armed, and still where it was armed: a move whose scroll or resize event hasn't fired yet
   * re-arms it here instead.
   */
  send() {
    const proposal = this.proposal;
    const button = this.bar.querySelector('.bee-proposal-send');
    if (!proposal || button.disabled) return;
    if (!sameRect(button.getBoundingClientRect(), this.armRect)) {
      this.arm();
      return;
    }
    this.context.dispatchAction({ event: { name: CHOICE_ACTION, context: { choice: proposal.choice, text: proposal.text } } });
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
    this.frame.contentWindow.postMessage({ jsonrpc: '2.0', id, ...outcome }, '*');
  }
}
SketchElement.tagName = 'bee-sketch';

/** The catalog entry: Sketch's API with its element, as the basic catalog's entries are built. */
export const Sketch = { ...SketchApi, tagName: SketchElement.tagName, element: SketchElement };
