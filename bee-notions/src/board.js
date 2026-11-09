// `Board`: columns and draggable cards, the work split (spec §5.6, plan Task 11). Every title and
// label is bound as Lit text (`${}`), never as markup.
//
// A card moves two ways. It can be dragged onto another column: HTML5 drag and drop, from a mouse
// or a firm long-press on iOS. Or it can be tapped, clicked, or opened with Enter or Space, which
// shows the other columns on the card as buttons ("Move to Doing"). Phones need the menu, since
// few people find the long-press. Either way the move dispatches the A2UI action `drop` with
// context `{card, column}`. The page's driver (bee-a2ui's NotionActions) writes the card's
// `column` leaf from the register it read and appends the record. Two concurrent drops of one
// card leave two values in that leaf, and the projection shows one (the greater column id), so a
// card never shows twice.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';
import { BoundData } from './bound-data.js';

/** The A2UI action a drop becomes (NotionActions.DROP). */
export const DROP_ACTION = 'drop';

/** The drag data type a card carries. */
const CARD_TYPE = 'text/x-bee-card';

/**
 * How long a move menu's buttons stay disabled after it opens, or after its options change. A
 * double tap would otherwise land on a button that just appeared under it. Sketch's Send arms
 * the same way (bee#60).
 */
export const MOVE_ARM_MS = 300;

/** A click this soon after a drag ends belongs to the drag, not a tap. */
const AFTER_DRAG_MS = 300;

/**
 * Board's properties, each a data binding to an ordered collection `{items, order}`: `columns`
 * (items `{title, owner?}`) and `cards` (items `{label, column, href?, detail?}`), with `order` an
 * array of ids. A card's label links to its `href`, an http: or https: URL, in a new tab; its
 * `detail` is one muted line under the label (bee#197).
 * Keep SurfaceValidator.BOARD (bee-a2ui) in step: the bridge refuses what the renderer can't draw.
 */
export const BoardApi = {
  name: 'Board',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      columns: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The columns: {items: {id: {title, owner?}}, order: [id]}.'),
      cards: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The cards: {items: {id: {label, column, href?, detail?}}, order: [id]}. '
        + 'href is an http: or https: URL the label links to; detail is one short line under the label.'),
    })
    .strict(),
};

/**
 * An ordered collection's items in order, each `{id, ...item}`: the order's ids at their first
 * occurrence, without ids that name no item, then any item the order lacks, sorted by id. Every
 * peer holding the same state shows the same permutation (tractat-us/bee#19's projection rule).
 */
export function ordered(collection) {
  const items = collection?.items && typeof collection.items === 'object' ? collection.items : {};
  const listed = [];
  for (const id of Array.isArray(collection?.order) ? collection.order : []) {
    if (typeof id === 'string' && Object.hasOwn(items, id) && !listed.includes(id)) listed.push(id);
  }
  const rest = Object.keys(items).filter((id) => !listed.includes(id)).sort();
  return [...listed, ...rest].map((id) => ({ ...(items[id] ?? {}), id }));
}

/**
 * A card's `href` if it is an http: or https: URL, else null. SurfaceValidator refuses any
 * other, and this keeps a page that renders unvalidated data from linking `javascript:`.
 */
export function cardHref(href) {
  if (typeof href !== 'string' || !/^https?:\/\/./i.test(href)) return null;
  try {
    const { protocol } = new URL(href);
    return protocol === 'http:' || protocol === 'https:' ? href : null;
  } catch {
    return null;
  }
}

/** The columns a card in `column` can move to, as `{id, title}` in board order: every other one. */
export function moveTargets(columns, column) {
  return columns
    .filter((each) => each.id !== String(column))
    .map((each) => ({ id: each.id, title: String(each.title ?? each.id) }));
}

// The page's colours (index.html's tokens, which follow dark mode), with fallbacks for a page
// that doesn't define them. Styled inline, as Poll is: the element renders into light DOM.
const INK = 'var(--ink,#1f2328)';
const MUTED = 'var(--muted,#656d76)';
const LINE = 'var(--line,#d8d6d0)';
const CARD = 'var(--card,#fff)';
const BG = 'var(--bg,#f6f5f2)';
const ACCENT = 'var(--accent,#b7791f)';

const BOARD = 'display:flex;flex-wrap:wrap;gap:1rem;align-items:flex-start';
const COLUMN = `flex:1 1 10rem;min-width:10rem;min-height:4rem;padding:0.5rem;border:1px solid ${LINE};`
  + `border-radius:8px;background:${BG};color:${INK}`;
const LIST = 'list-style:none;padding:0;margin:0';
// 2.75rem is 44px at the default font size: Apple's minimum touch target.
const CARD_ROW = `display:block;min-height:2.75rem;padding:0.6rem 0.75rem;margin:0.4rem 0;border:1px solid ${LINE};`
  + `border-radius:8px;background:${CARD};color:${INK};cursor:grab;font-size:1.05rem`;
// The label as a link stays the card's ink, with a faint underline: quiet, but plainly a link.
const LINK = `color:inherit;text-decoration:underline;text-decoration-color:${LINE};text-underline-offset:0.2em`;
const DETAIL = `display:block;margin-top:0.15rem;color:${MUTED};font-size:0.85rem;`
  + 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
const CARD_OPEN = `border:2px solid ${ACCENT};padding:calc(0.6rem - 1px) calc(0.75rem - 1px)`;
const MENU = 'display:flex;flex-direction:column;gap:0.4rem;margin-top:0.5rem';
const MOVE = `min-height:2.75rem;padding:0.5rem 0.75rem;border:1px solid ${ACCENT};border-radius:8px;`
  + `background:color-mix(in srgb, ${ACCENT} 14%, ${CARD});color:${INK};font:inherit;text-align:left;cursor:pointer`;

/**
 * `<bee-board>`: one section per column, each listing its cards. A card is dragged between
 * them, or tapped to show the columns it can move to.
 */
export class BoardElement extends A2uiLitElement {
  constructor() {
    super();
    this.dragging = null;
    this.dragEndedAt = -Infinity;
    /** The open move menu, `{card, column, armed, keyboard}`, or null. Never on the board. */
    this.menu = null;
    this.armTimer = null;
    this.bound = new BoundData(this, ['columns', 'cards']);
    // A press anywhere outside the open card closes its menu.
    this.onPressAnywhere = (event) => {
      const open = this.openCard();
      if (open && !event.composedPath().includes(open)) this.close();
    };
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('pointerdown', this.onPressAnywhere, true);
  }

  disconnectedCallback() {
    document.removeEventListener('pointerdown', this.onPressAnywhere, true);
    clearTimeout(this.armTimer);
    this.menu = null;
    super.disconnectedCallback();
  }

  render() {
    if (!this.controller?.props) return nothing;
    const props = this.bound.values();
    const columns = ordered(props.columns);
    const cards = ordered(props.cards);
    this.followMenu(cards);
    const known = new Set(columns.map((column) => column.id));
    const unplaced = cards.filter((card) => !known.has(String(card.column)));
    return html`<div class="bee-board" style=${BOARD}>
      ${columns.map((column) => this.column(column, cards.filter((card) => String(card.column) === column.id), columns))}
      ${unplaced.length ? html`<section class="unplaced" style=${COLUMN}><h3>No column</h3>
        <ul style=${LIST}>${unplaced.map((card) => this.card(card, columns))}</ul></section>` : nothing}
    </div>`;
  }

  column(column, cards, columns) {
    return html`<section class="column" data-column=${column.id} style=${COLUMN}
        @dragover=${(event) => event.preventDefault()}
        @drop=${(event) => this.drop(event, column.id, cards)}>
      <h3>${String(column.title ?? column.id)}</h3>
      ${column.owner ? html`<p class="owner" style="color:${MUTED}">${String(column.owner)}</p>` : nothing}
      <ul style=${LIST}>${cards.map((card) => this.card(card, columns))}</ul>
    </section>`;
  }

  card(card, columns) {
    const label = String(card.label ?? card.id);
    const open = this.menu?.card === card.id;
    return html`<li class="card" draggable="true" tabindex="0" data-card=${card.id}
        aria-haspopup="true" aria-expanded=${open ? 'true' : 'false'}
        style="${CARD_ROW};${open ? CARD_OPEN : ''}"
        @click=${(event) => this.tap(event, card)}
        @keydown=${(event) => this.key(event, card)}
        @dragstart=${(event) => {
          this.close();
          this.dragging = card.id;
          event.dataTransfer?.setData(CARD_TYPE, card.id); // lets the browser start a drag; never read back
        }}
        @dragend=${(event) => {
          this.dragging = null;
          this.dragEndedAt = event.timeStamp;
        }}>${this.label(card, label)}${this.detail(card)}${open ? this.moveMenu(card, label, columns) : nothing}</li>`;
  }

  /**
   * The card's label, as a link to its `href` when it has one. The link isn't draggable itself,
   * so a drag that starts on it drags the card, and a click that ends a drag doesn't follow it.
   */
  label(card, label) {
    const href = cardHref(card.href);
    if (!href) return html`<span class="label">${label}</span>`;
    // The title names the host, so a userinfo trick (https://bee.tractat.us@evil.com) shows on hover.
    return html`<a class="label" href=${href} title=${new URL(href).host} target="_blank" rel="noopener noreferrer"
        draggable="false" style=${LINK} @click=${(event) => { if (this.endsDrag(event)) event.preventDefault(); }}>${label}</a>`;
  }

  /** The card's detail: one muted line, cut with an ellipsis, its whole text on hover. */
  detail(card) {
    if (typeof card.detail !== 'string' || !card.detail) return nothing;
    return html`<span class="detail" title=${card.detail} style=${DETAIL}>${card.detail}</span>`;
  }

  /** Whether `event` belongs to a drag: one under way, or one that ended a moment ago. */
  endsDrag(event) {
    return Boolean(this.dragging) || event.timeStamp - this.dragEndedAt < AFTER_DRAG_MS;
  }

  /** The open card's menu: a button per column it can move to, disabled until armed. */
  moveMenu(card, label, columns) {
    // A held key's repeats never press a button: only a fresh press does.
    return html`<div class="move" role="group" aria-label=${`Move ${label}`} style=${MENU}
        @keydown=${(event) => { if (event.repeat) event.preventDefault(); }}>
      ${moveTargets(columns, card.column).map((target) => html`<button type="button" data-move=${target.id}
          ?disabled=${!this.menu.armed} style=${MOVE}
          @click=${(event) => {
            event.stopPropagation();
            this.close();
            this.dispatchDrop(card.id, target.id);
          }}>Move to ${target.title}</button>`)}
    </div>`;
  }

  /**
   * A tap or click on a card toggles its menu, unless it ends a drag, lands in the menu, or
   * follows the card's link.
   */
  tap(event, card) {
    if (this.endsDrag(event)) return;
    if (event.target?.closest?.('.move, a.label')) return;
    if (this.menu?.card === card.id) this.close();
    else this.open(card, false);
  }

  /** Enter or Space on a card opens its menu. Escape anywhere in the card closes it. */
  key(event, card) {
    if (event.key === 'Escape' && this.menu?.card === card.id) {
      event.preventDefault();
      this.close();
      event.currentTarget.focus();
    } else if ((event.key === 'Enter' || event.key === ' ') && event.target === event.currentTarget) {
      event.preventDefault();
      if (this.menu?.card !== card.id) this.open(card, true);
    }
  }

  open(card, keyboard) {
    this.menu = { card: card.id, column: String(card.column), armed: false, keyboard };
    this.arm();
  }

  close() {
    if (!this.menu) return;
    clearTimeout(this.armTimer);
    this.menu = null;
    this.requestUpdate();
  }

  /** Disables the menu's buttons for MOVE_ARM_MS, then enables them (focusing the first, from a key). */
  arm() {
    clearTimeout(this.armTimer);
    const menu = this.menu;
    menu.armed = false;
    this.requestUpdate();
    this.armTimer = setTimeout(async () => {
      if (this.menu !== menu) return;
      menu.armed = true;
      this.requestUpdate();
      await this.updateComplete;
      if (menu.keyboard && this.menu === menu) this.openCard()?.querySelector('button')?.focus();
    }, MOVE_ARM_MS);
  }

  /** Keeps the open menu true to the board: it closes when its card goes, and re-arms when it moves. */
  followMenu(cards) {
    if (!this.menu) return;
    const card = cards.find((each) => each.id === this.menu.card);
    if (!card) {
      clearTimeout(this.armTimer);
      this.menu = null;
    } else if (String(card.column) !== this.menu.column) {
      this.menu.column = String(card.column);
      this.arm();
    }
  }

  /** The `<li>` of the card whose menu is open, or null. */
  openCard() {
    if (!this.menu) return null;
    return [...this.querySelectorAll('li[data-card]')].find((li) => li.dataset.card === this.menu.card) ?? null;
  }

  drop(event, column, cardsThere) {
    event.preventDefault();
    // Only a drag that started in this element counts: card ids repeat across boards.
    const card = this.dragging;
    this.dragging = null;
    if (!card || cardsThere.some((there) => there.id === card)) return;
    this.dispatchDrop(card, column);
  }

  dispatchDrop(card, column) {
    this.context?.dispatchAction({ event: { name: DROP_ACTION, context: { card, column } } });
  }
}
BoardElement.tagName = 'bee-board';

/** The catalog entry: Board's API with its element, as the basic catalog's entries are built. */
export const Board = { ...BoardApi, tagName: BoardElement.tagName, element: BoardElement };
