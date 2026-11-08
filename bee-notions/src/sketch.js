// `Sketch`: agent-written HTML in a sandboxed frame (spec §5.6, plan Task 8). Invariant: the
// HTML runs only inside `<iframe sandbox="allow-scripts">`, never with allow-same-origin, under a
// CSP that allows no fetch, socket, image or frame load, and the page acts only on messages whose
// source is that frame, and on a choice only while the viewer's click is fresh. The page's own
// CSP (bee-host's PAGE_CSP) keeps the frame from navigating to a URL.
//
// Not covered: WebRTC sits outside CSP, so a sketch can still reach the network through an
// `RTCPeerConnection` (ICE to a STUN or TURN server it names), and the browser may resolve a DNS
// name a sketch hints at (bee#49). The helper deletes the WebRTC constructors, best-effort.
//
// A click on a `[data-choice]` element inside it reaches the board as the A2UI action `choice`,
// with context `{choice, text}`. The frame reports it in the MCP Apps (SEP-1865) View-to-Host
// envelope: a JSON-RPC 2.0 `ui/message` request (see sketch-helper.js), which this element answers.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicStringSchema, TextApi } from '@a2ui/web_core/v0_9';
import { sketchHelper } from './sketch-helper.js';

/**
 * No fetch, socket, frame or form loads: only the inline script and styles the sketch carries, and
 * data: images. CSP doesn't govern WebRTC or DNS hints, so this isn't "no network" (bee#49).
 */
export const SKETCH_CSP =
  "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:";

/** The `sandbox` the frame gets: scripts, and nothing else (no allow-same-origin, ever). */
export const SKETCH_SANDBOX = 'allow-scripts';

/** The A2UI action a choice becomes, and the record name bee-companion handles. */
export const CHOICE_ACTION = 'choice';

const CHOICE_META = 'us.tractat.bee/choice';

/**
 * The least time between two `ui/message`s from one frame, refused ones included: faster than a
 * person clicks twice. A frame that posts faster than this gets nothing through.
 */
const CHOICE_GAP_MS = 250;

/**
 * How long a gesture on the page itself explains the page's user activation: Chromium's
 * transient activation lasts about this long.
 */
const PAGE_GESTURE_MS = 5_000;

/** The gestures that activate a page, as the page itself sees them. */
const PAGE_GESTURES = ['pointerdown', 'keydown'];

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

/** `<bee-sketch>`: one sandboxed frame showing its `html`. */
export class SketchElement extends A2uiLitElement {
  constructor() {
    super();
    this.frame = null;
    this.shown = null;
    this.lastMessageAt = -Infinity;
    this.lastPageGestureAt = -Infinity;
    this.onMessage = (event) => this.receive(event);
    this.onPageGesture = () => {
      this.lastPageGestureAt = performance.now();
    };
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('message', this.onMessage);
    // Capture, so no handler on the page can hide a gesture from it. A gesture inside the
    // sketch's frame (cross-origin) never reaches these listeners.
    for (const type of PAGE_GESTURES) window.addEventListener(type, this.onPageGesture, { capture: true });
  }

  disconnectedCallback() {
    window.removeEventListener('message', this.onMessage);
    for (const type of PAGE_GESTURES) window.removeEventListener(type, this.onPageGesture, { capture: true });
    super.disconnectedCallback();
  }

  /** Lit renders nothing here; the frame is managed in `updated`, outside Lit's part. */
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
    }
    this.frame.title = String(props.title ?? 'Sketch');
    const doc = sketchDocument(props.html ?? '');
    if (doc !== this.shown) {
      this.shown = doc;
      this.frame.srcdoc = doc;
    }
  }

  /** A message from this element's own frame, or nothing: any other window's is ignored. */
  receive(event) {
    if (!this.frame || event.source !== this.frame.contentWindow) return;
    const message = event.data;
    if (!message || typeof message !== 'object' || message.jsonrpc !== '2.0' || typeof message.method !== 'string') return;
    if (message.method === 'ui/message') {
      if (!this.mayChoose()) {
        this.reply(message.id, { error: { code: -32000, message: 'Refused: a choice needs a click by the viewer.' } });
        return;
      }
      const params = message.params ?? {};
      const choice = params._meta?.[CHOICE_META];
      const text = params.content?.type === 'text' ? String(params.content.text ?? '') : '';
      if (choice !== undefined) {
        this.context?.dispatchAction({ event: { name: CHOICE_ACTION, context: { choice: String(choice), text } } });
      }
      this.reply(message.id, { result: {} });
    } else {
      this.reply(message.id, { error: { code: -32601, message: `Method not found: ${message.method}` } });
    }
  }

  /**
   * Whether a `ui/message` now is the viewer's: a choice is recorded as theirs, so the sketch may
   * not send one on its own. A click inside the frame activates this page too (user activation
   * reaches every ancestor), so `isActive` holds only for a few seconds after a real gesture.
   *
   * Activation is page-wide, so a gesture on the page itself (typing in another surface's field)
   * would open the same window to a sketch posting in the background. A gesture the page saw in
   * the last PAGE_GESTURE_MS explains the activation, so the choice is refused; one inside the
   * frame never reaches the page's listeners. The cost: a choice made within that time of typing
   * on the page is refused too.
   *
   * Every message counts toward the CHOICE_GAP_MS limit, refused or not, so a frame that posts
   * faster than a person clicks gets nothing through.
   *
   * Not covered: a sketch that posts while the viewer presses on the frame but not on a choice
   * (selecting text, say) is taken as the viewer's choice.
   */
  mayChoose() {
    const now = performance.now();
    const sincePrevious = now - this.lastMessageAt;
    this.lastMessageAt = now;
    if (sincePrevious < CHOICE_GAP_MS) return false;
    if (!navigator.userActivation?.isActive) return false;
    return now - this.lastPageGestureAt >= PAGE_GESTURE_MS;
  }

  /** Answers request [id] (a notification, with no id, gets none). The frame's origin is opaque: '*'. */
  reply(id, outcome) {
    if (id === undefined || id === null) return;
    this.frame.contentWindow?.postMessage({ jsonrpc: '2.0', id, ...outcome }, '*');
  }
}
SketchElement.tagName = 'bee-sketch';

/** The catalog entry: Sketch's API with its element, as the basic catalog's entries are built. */
export const Sketch = { ...SketchApi, tagName: SketchElement.tagName, element: SketchElement };
