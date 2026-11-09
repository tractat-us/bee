// `Chat`: people and agents talking on the board (bee#214). Messages are an append-only array in
// the surface's data, each `{id, author, text, at}`, shown oldest first with the newest in view.
// Every author and text is bound as Lit text (`${}`), never as markup, so a message like
// `<img onerror>` shows literally.
//
// Sending dispatches the A2UI action `send` with context `{id, text}`. The page's driver
// (bee-a2ui's NotionActions) appends the message as the page's actor, stamped with the record's
// time, in the same transform as the record, so a session waiting on the surface hears it. An
// agent posts by appending the same entry through the bridge's `append`.
//
// The field is this element's own `<input>`, not a bound TextField: what a person is typing stays
// on their page until they send it.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';
import { BoundData } from './bound-data.js';
import { peerName } from './poll.js';

/** The A2UI action a message becomes (NotionActions.SEND). */
export const SEND_ACTION = 'send';

/** The most characters a message holds (NotionActions.MAX_MESSAGE_LENGTH). */
export const MAX_MESSAGE_LENGTH = 2000;

/**
 * Chat's one property, a data binding: `messages`, an append-only array of `{id, author, text, at}`.
 * Keep SurfaceValidator.CHAT (bee-a2ui) in step: the bridge refuses what the renderer can't draw.
 */
export const ChatApi = {
  name: 'Chat',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      messages: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The messages, appended: {id, author, text, at}.'),
    })
    .strict(),
};

/**
 * The messages a bound array holds, as `{id, author, text, at}`, in the order they were appended,
 * each id once at its first occurrence. An entry with no text is left out and a text past the cap
 * is cut, so one bad message never hides the rest.
 */
export function chatMessages(messages) {
  const seen = new Set();
  const shown = [];
  for (const entry of Array.isArray(messages) ? messages : []) {
    if (!entry || typeof entry !== 'object' || typeof entry.text !== 'string') continue;
    const id = String(entry.id ?? shown.length);
    if (seen.has(id)) continue;
    seen.add(id);
    shown.push({
      id,
      author: String(entry.author ?? ''),
      text: entry.text.slice(0, MAX_MESSAGE_LENGTH),
      at: typeof entry.at === 'string' ? entry.at : '',
    });
  }
  return shown;
}

/**
 * A fresh message id: 16 random bytes as hex. `crypto.randomUUID` needs a secure context, which a
 * plain-HTTP LAN page isn't; `getRandomValues` doesn't.
 */
export function newMessageId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** A message's time as the viewer's clock shows it, for its tooltip; empty if it can't be read. */
function shownAt(at) {
  const time = new Date(at);
  return Number.isNaN(time.getTime()) ? '' : time.toLocaleString();
}

// The page's colours (index.html's tokens), with fallbacks, styled inline as Poll and Board are.
const INK = 'var(--ink,#1f2328)';
const MUTED = 'var(--muted,#656d76)';
const LINE = 'var(--line,#d8d6d0)';
const CARD = 'var(--card,#fff)';
const ACCENT = 'var(--accent,#b7791f)';

const LIST = 'list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:0.35rem;'
  + 'max-height:min(50vh,22rem);overflow-y:auto;overscroll-behavior:contain';
const MESSAGE = 'line-height:1.4;white-space:pre-wrap;overflow-wrap:anywhere';
const AUTHOR = `font-weight:600;color:${MUTED};margin-right:0.4rem`;
const COMPOSE = 'display:flex;gap:0.5rem;margin:0.75rem 0 0';
const FIELD = `flex:1;min-width:0;min-height:2.75rem;padding:0.5rem 0.75rem;border:1px solid ${LINE};border-radius:8px;`
  + `background:${CARD};color:${INK};font:inherit;font-size:1rem`;
const SEND = `min-height:2.75rem;padding:0.5rem 1rem;border:1px solid ${ACCENT};border-radius:8px;background:${CARD};`
  + `color:${INK};font:inherit;font-weight:600;cursor:pointer`;

/** How close to the bottom, in pixels, a reader counts as following the newest message. */
const FOLLOWING_PX = 24;

/**
 * `<bee-chat>`: the messages, oldest first, then one field and a Send. Enter sends; a blank
 * message isn't sent. A new message scrolls into view while the reader is at the bottom, or when
 * it's their own; one reading further up is left where they are.
 */
export class ChatElement extends A2uiLitElement {
  constructor() {
    super();
    this.bound = new BoundData(this, ['messages']);
    this.blank = true;
    this.following = true;
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  updated() {
    const list = this.querySelector('.messages');
    if (list && this.following) list.scrollTop = list.scrollHeight;
  }

  render() {
    if (!this.controller?.props) return nothing;
    const messages = chatMessages(this.bound.values().messages);
    return html`<div class="bee-chat" style="color:${INK}">
      <ol class="messages" style=${LIST} @scroll=${this.scrolled}>
        ${messages.map((message) => html`<li data-message=${message.id} title=${shownAt(message.at)} style=${MESSAGE}><span
            class="author" title=${message.author} style=${AUTHOR}>${peerName(message.author)}</span><span class="text">${message.text}</span></li>`)}
      </ol>
      <form class="compose" style=${COMPOSE} @submit=${this.send}>
        <input type="text" name="text" aria-label="Message" placeholder="Message" autocomplete="off"
            maxlength=${MAX_MESSAGE_LENGTH} style=${FIELD} @input=${this.typed}>
        <button type="submit" style=${SEND} ?disabled=${this.blank}>Send</button>
      </form>
    </div>`;
  }

  /** Whether the reader is at the bottom, kept as they scroll so a re-render knows. */
  scrolled(event) {
    const list = event.currentTarget;
    this.following = list.scrollHeight - list.scrollTop - list.clientHeight <= FOLLOWING_PX;
  }

  typed(event) {
    const blank = event.currentTarget.value.trim() === '';
    if (blank !== this.blank) {
      this.blank = blank;
      this.requestUpdate();
    }
  }

  /** Sends the field's text, unless it's blank, then empties the field and follows the newest. */
  send(event) {
    event.preventDefault();
    const field = event.currentTarget.elements.text;
    const text = field.value.trim();
    if (!text) return;
    this.context?.dispatchAction({ event: { name: SEND_ACTION, context: { id: newMessageId(), text } } });
    field.value = '';
    this.blank = true;
    this.following = true;
    this.requestUpdate();
  }
}
ChatElement.tagName = 'bee-chat';

/** The catalog entry: Chat's API with its element, as the basic catalog's entries are built. */
export const Chat = { ...ChatApi, tagName: ChatElement.tagName, element: ChatElement };
