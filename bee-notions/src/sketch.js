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
// helper's `ui/notifications/size-changed` notifications. A fit can move this sketch's Send and
// every Send below it, so it re-arms each shown proposal whose Send it moved (see `fit`), and a
// frame that refits too often keeps its height.
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

/**
 * How many times in a second a frame's fits may turn from growing to shrinking, or back, before
 * its height is pinned until its next screen. A document whose height follows the frame's
 * (`height: calc(1000px - 100vh)`) would otherwise refit, and re-arm every Send it moves, many
 * times a second, for as long as it's shown. Content that only grows, or only shrinks, never turns.
 */
const TURNS_PER_SECOND = 4;

/** The A2UI action a choice becomes, and the record name bee-companion handles. */
export const CHOICE_ACTION = 'choice';

const CHOICE_META = 'us.tractat.bee/choice';

/**
 * How long Send stays disarmed after a proposal appears or changes, after Send moves, or after a
 * click it refused. The sketch decides when its proposal changes, and it can scroll the page
 * (scrollIntoView reaches the parent in Chromium), so without this it could swap the text, or
 * slide Send, under a viewer's click already on its way. It's also how long after Send takes
 * focus a key press on it is too soon. Browsers hold their own permission prompts' buttons about
 * as long, and Chromium counts repeated clicks on them the same way (InputEventActivationProtector).
 */
const SEND_ARM_MS = 500;

/**
 * Where [element] is on the screen: its client box, and the visual viewport's pan and zoom. A
 * pinch-zoom, or a pan of the zoomed page, moves Send on the screen and leaves its client box as
 * it was.
 */
const screenBox = (element) => {
  const { x, y, width, height } = element.getBoundingClientRect();
  const view = window.visualViewport;
  return { x, y, width, height, left: view?.offsetLeft ?? 0, top: view?.offsetTop ?? 0, scale: view?.scale ?? 1 };
};

/** Whether two `screenBox`es are the same place on the screen. */
const sameBox = (a, b) =>
  !!a && !!b && ['x', 'y', 'width', 'height', 'left', 'top', 'scale'].every((key) => a[key] === b[key]);

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
    /** Whether Send is armed: shown as `aria-disabled`, and checked by its click handler. */
    this.armed = false;
    /** Send's `screenBox` when arming last began: Send arms only if it's still there. */
    this.armRect = null;
    /** Whether the pointer is over Send, from Send's own boundary events. */
    this.pointerOver = false;
    /** Where the pointer came over Send, until the `pointermove` after it (see `arm`). */
    this.overAt = null;
    /** Whether the pointer has moved onto Send since arming last began (see `arm`). */
    this.pointerArrived = false;
    /** Whether arming has finished, Send unmoved, but waits for the pointer resting on it to leave. */
    this.awaitingLeave = false;
    /** When Send last armed, and when a press on it last began: only a later press sends. */
    this.enabledAt = Infinity;
    this.pressedAt = -Infinity;
    /** When Send last took focus, and when a fresh Enter or Space on it last went down. */
    this.focusedAt = -Infinity;
    this.keyPressedAt = -Infinity;
    /** The height the frame was last fitted to, as CSS. */
    this.fitted = null;
    /** This screen's last fit's direction, when its fits recently turned, and whether it's pinned (see `fit`). */
    this.fitDirection = 0;
    this.turns = [];
    this.pinned = false;
    this.onMessage = (event) => this.receive(event);
    // Anything that may have moved Send re-arms it: a scroll anywhere on the page, a resize, a
    // pinch-zoom or a pan of the zoomed page, or a change in the size of the bar or this sketch. The page's body changes size with every fit
    // on a page taller than the window, so its resize re-arms Send only if Send has moved.
    this.onMove = () => { if (this.proposal) this.arm(); };
    this.resizes = new ResizeObserver((entries) => {
      if (!this.proposal) return;
      if (entries.every((entry) => entry.target === document.body)) this.rearmIfMoved();
      else this.arm();
    });
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
    window.visualViewport?.addEventListener('scroll', this.onMove);
    window.visualViewport?.addEventListener('resize', this.onMove);
    this.resizes.observe(this);
    this.resizes.observe(document.body);
    if (this.bar) this.resizes.observe(this.bar);
  }

  disconnectedCallback() {
    connected.delete(this);
    window.removeEventListener('message', this.onMessage);
    window.removeEventListener('scroll', this.onMove, true);
    window.removeEventListener('resize', this.onMove);
    window.visualViewport?.removeEventListener('scroll', this.onMove);
    window.visualViewport?.removeEventListener('resize', this.onMove);
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
      this.fitDirection = 0;
      this.turns = [];
      this.pinned = false;
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
    // Send is never `disabled`: a disabled button hears no clicks, so a viewer clicking steadily
    // at one spot would click nothing while Send armed under them, and a disabled Send that took
    // focus would drop it. It's disarmed with `aria-disabled` instead, and refuses here.
    //
    // Only a real gesture counts: `isTrusted` is false for any event a script dispatched, and a
    // sketch's script can't reach the page to dispatch one anyway.
    send.addEventListener('click', (event) => {
      if (!event.isTrusted) return;
      const keyPressedAt = this.keyPressedAt;
      this.keyPressedAt = -Infinity;
      // A press that began before Send armed was meant for what Send showed then, or for wherever
      // Send was. A keyboard activation (detail 0) dates from its keydown, since Space activates
      // on keyup. Tab out of a sketch lands on its Send, so that keydown also counts only once
      // Send has had focus for SEND_ARM_MS: a key pressed for the sketch, even held past then,
      // doesn't send. A detail-0 click with no keydown at all is assistive tech pressing the button
      // through the accessibility API (screen reader browse mode, Voice Control, Dragon); it
      // sends once armed, which `send()` checks. That reading assumes no page code stops a keydown
      // before it reaches Send: true today, the only `stopPropagation` calls are in `board.js` and
      // `renderer-host.js`.
      const fresh = event.detail !== 0
        ? this.pressedAt >= this.enabledAt
        : keyPressedAt === -Infinity
          || (keyPressedAt >= this.enabledAt && keyPressedAt - this.focusedAt >= SEND_ARM_MS);
      if (!(fresh && this.send())) this.refuse();
    });
    send.addEventListener('pointerdown', (event) => { if (event.isTrusted) this.pressedAt = performance.now(); });
    send.addEventListener('focus', () => { this.focusedAt = performance.now(); });
    // A key pressed on Send and never released there (focus moved on) isn't a press for the next one.
    send.addEventListener('blur', () => { this.keyPressedAt = -Infinity; });
    // A pointer moved onto Send gets its `pointerover` and a `pointermove` from one input event,
    // at the same point. One that Send was moved under gets a `pointerover` alone, and its next
    // move, a hand's tremor, is somewhere else (see `arm`).
    send.addEventListener('pointerover', (event) => {
      this.pointerOver = true;
      this.overAt = { x: event.clientX, y: event.clientY };
    });
    send.addEventListener('pointermove', (event) => {
      if (this.overAt && event.clientX === this.overAt.x && event.clientY === this.overAt.y) this.pointerArrived = true;
      this.overAt = null;
    });
    // Leaving Send is the only way out of waiting for the pointer to leave it: a mouse moved off
    // it, or a touch lifted. Moving on Send isn't.
    send.addEventListener('pointerout', () => {
      this.pointerOver = false;
      this.overAt = null;
      if (!this.awaitingLeave) return;
      this.awaitingLeave = false;
      if (sameBox(screenBox(send), this.armRect)) this.enable();
      else this.arm();
    });
    // A held key repeats: only a fresh press may send.
    send.addEventListener('keydown', (event) => {
      if (!event.isTrusted) return;
      if (event.repeat) event.preventDefault();
      else if (event.key === 'Enter' || event.key === ' ') this.keyPressedAt = performance.now();
    });
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
   * change, and a key press on Send counts only if it went down once Send had had focus for
   * SEND_ARM_MS, so a click or key press meant for something else can't send it.
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
   * SKETCH_MAX_HEIGHT. A new height can move this sketch's Send and every Send below it, and when
   * the page is shorter than the window a move changes no element's size, so no ResizeObserver
   * hears it. So each shown proposal whose Send has moved re-arms here, in the same task that
   * moved it, before any click can land on a Send in its new place.
   *
   * After TURNS_PER_SECOND turns in a second, the frame keeps its height until its next screen.
   */
  fit(height) {
    if (this.pinned || typeof height !== 'number' || !Number.isFinite(height) || height < 0) return;
    // The frame's own border is inside its height (border-box): add it, or the document scrolls.
    const border = this.frame.offsetHeight - this.frame.clientHeight;
    const css = `clamp(${SKETCH_MIN_HEIGHT_PX}px, ${Math.ceil(height) + border}px, ${SKETCH_MAX_HEIGHT})`;
    if (css === this.fitted) return;
    this.fitted = css;
    const before = this.frame.offsetHeight;
    this.frame.style.height = css;
    const after = this.frame.offsetHeight;
    if (after === before) return;
    const direction = Math.sign(after - before);
    if (this.fitDirection && direction !== this.fitDirection) {
      const now = performance.now();
      this.turns = this.turns.filter((at) => now - at < 1_000);
      this.turns.push(now);
      if (this.turns.length >= TURNS_PER_SECOND) this.pinned = true;
    }
    this.fitDirection = direction;
    for (const sketch of connected) sketch.rearmIfMoved();
  }

  /**
   * Re-arms Send if it has left the box it was armed in. Away and back again are both moves, so
   * a Send slid away and back under a click still re-arms twice.
   */
  rearmIfMoved() {
    if (!this.proposal) return;
    if (!sameBox(screenBox(this.sendButton()), this.armRect)) this.arm();
  }

  /**
   * Disarms Send, and arms it SEND_ARM_MS later only if Send is still where it was when arming
   * began; otherwise arming starts again. A sketch can't move Send under a click on its way.
   *
   * Nor under a pointer at rest: a sketch can learn where a resting pointer is (a frame grown over
   * it hears `pointerover`, with coordinates, in Chromium), then fit its frame so its own Send
   * lands there. So when arming ends with the pointer over Send, and the pointer hasn't moved onto
   * Send since arming began, Send stays disarmed until the pointer leaves it. A move on Send, as
   * small as a tremor, doesn't count. A touch held on Send leaves it when it lifts, and that
   * touch's own tap can't send, since it began before Send armed.
   */
  arm() {
    const send = this.sendButton();
    this.setArmed(false);
    this.armRect = screenBox(send);
    this.pointerArrived = false;
    this.awaitingLeave = false;
    clearTimeout(this.armTimer);
    this.armTimer = setTimeout(() => {
      if (!sameBox(screenBox(send), this.armRect)) this.arm();
      else if (this.pointerOver && !this.pointerArrived) this.awaitingLeave = true;
      else this.enable();
    }, SEND_ARM_MS);
  }

  /** Arms Send where it is. */
  enable() {
    this.setArmed(true);
    this.enabledAt = performance.now();
  }

  /** Shows Send armed or not, to the eye and to assistive tech. The click handler checks `armed`. */
  setArmed(armed) {
    const send = this.sendButton();
    this.armed = armed;
    send.setAttribute('aria-disabled', String(!armed));
    send.style.opacity = armed ? '' : '0.5';
    send.style.cursor = armed ? '' : 'default';
  }

  /**
   * Re-arms Send after a click it refused, so a click within SEND_ARM_MS of a refused one never
   * sends, however steady the stream. A pointer that arrived on Send still counts as arrived if
   * Send hasn't moved, so a viewer who clicked too soon needn't leave Send and come back.
   */
  refuse() {
    if (!this.proposal) return;
    const arrived = this.pointerArrived && sameBox(screenBox(this.sendButton()), this.armRect);
    this.arm();
    this.pointerArrived = arrived;
  }

  /**
   * The viewer's Send: records the proposal shown, as theirs, answers the frame, and says whether
   * it did. Send must be armed, and still where it was armed: a move that fired no event, or whose
   * event hasn't fired yet, is refused here.
   */
  send() {
    const proposal = this.proposal;
    if (!proposal || !this.armed || !sameBox(screenBox(this.sendButton()), this.armRect)) return false;
    this.context.dispatchAction({ event: { name: CHOICE_ACTION, context: { choice: proposal.choice, text: proposal.text } } });
    this.reply(proposal.id, { result: {} });
    this.drop();
    return true;
  }

  /** The page's own Send, in this sketch's bar. */
  sendButton() {
    return this.bar.querySelector('.bee-proposal-send');
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
    this.awaitingLeave = false;
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
