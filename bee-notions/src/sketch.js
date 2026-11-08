// `Sketch`: agent-written HTML in a sandboxed frame (spec §5.6, plan Task 8). Invariant: the
// HTML runs only inside `<iframe sandbox="allow-scripts">`, never with allow-same-origin, under a
// CSP that allows no network, and the page acts only on messages whose source is that frame.
//
// A click on a `[data-choice]` element inside it reaches the board as the A2UI action `choice`,
// with context `{choice, text}`. The frame reports it in the MCP Apps (SEP-1865) View-to-Host
// envelope: a JSON-RPC 2.0 `ui/message` request (see sketch-helper.js), which this element answers.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicStringSchema, TextApi } from '@a2ui/web_core/v0_9';
import { sketchHelper } from './sketch-helper.js';

/** No network, no frames, no forms: only the inline script and styles the sketch carries, and data: images. */
export const SKETCH_CSP =
  "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:";

/** The `sandbox` the frame gets: scripts, and nothing else (no allow-same-origin, ever). */
export const SKETCH_SANDBOX = 'allow-scripts';

/** The A2UI action a choice becomes, and the record name bee-companion handles. */
export const CHOICE_ACTION = 'choice';

const CHOICE_META = 'us.tractat.bee/choice';

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

  /** Answers request [id] (a notification, with no id, gets none). The frame's origin is opaque: '*'. */
  reply(id, outcome) {
    if (id === undefined || id === null) return;
    this.frame.contentWindow?.postMessage({ jsonrpc: '2.0', id, ...outcome }, '*');
  }
}
SketchElement.tagName = 'bee-sketch';

/** The catalog entry: Sketch's API with its element, as the basic catalog's entries are built. */
export const Sketch = { ...SketchApi, tagName: SketchElement.tagName, element: SketchElement };
