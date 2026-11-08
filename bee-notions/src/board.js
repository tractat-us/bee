// `Board`: columns and draggable cards, the work split (spec §5.6, plan Task 11). Every title and
// label is bound as Lit text (`${}`), never as markup.
//
// Dropping a card on another column dispatches the A2UI action `drop` with context
// `{card, column}`. The page's driver (bee-a2ui's NotionActions) writes the card's `column` leaf
// from the register it read and appends the record. Two concurrent drops of one card leave two
// values in that leaf, and the projection shows one (the greater column id), so a card never
// shows twice.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';

/** The A2UI action a drop becomes (NotionActions.DROP). */
export const DROP_ACTION = 'drop';

/** The drag data type a card carries. */
const CARD_TYPE = 'text/x-bee-card';

/**
 * Board's properties, each a data binding to an ordered collection `{items, order}`: `columns`
 * (items `{title, owner?}`) and `cards` (items `{label, column}`), with `order` an array of ids.
 * Keep SurfaceValidator.BOARD (bee-a2ui) in step: the bridge refuses what the renderer can't draw.
 */
export const BoardApi = {
  name: 'Board',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      columns: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The columns: {items: {id: {title, owner?}}, order: [id]}.'),
      cards: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The cards: {items: {id: {label, column}}, order: [id]}.'),
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

/** `<bee-board>`: one section per column, each listing its cards; a card is dragged between them. */
export class BoardElement extends A2uiLitElement {
  constructor() {
    super();
    this.dragging = null;
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  render() {
    const props = this.controller?.props;
    if (!props) return nothing;
    const columns = ordered(props.columns);
    const cards = ordered(props.cards);
    const known = new Set(columns.map((column) => column.id));
    const unplaced = cards.filter((card) => !known.has(String(card.column)));
    return html`<div class="bee-board" style="display:flex;gap:1rem;align-items:flex-start">
      ${columns.map((column) => this.column(column, cards.filter((card) => String(card.column) === column.id)))}
      ${unplaced.length ? html`<section class="unplaced"><h3>No column</h3><ul>${unplaced.map((card) => this.card(card))}</ul></section>` : nothing}
    </div>`;
  }

  column(column, cards) {
    return html`<section class="column" data-column=${column.id}
        style="min-width:10rem;min-height:4rem;padding:0.5rem;border:1px solid #ccc;border-radius:6px"
        @dragover=${(event) => event.preventDefault()}
        @drop=${(event) => this.drop(event, column.id, cards)}>
      <h3>${String(column.title ?? column.id)}</h3>
      ${column.owner ? html`<p class="owner">${String(column.owner)}</p>` : nothing}
      <ul style="list-style:none;padding:0;margin:0">${cards.map((card) => this.card(card))}</ul>
    </section>`;
  }

  card(card) {
    return html`<li class="card" draggable="true" data-card=${card.id}
        style="padding:0.25rem 0.5rem;margin:0.25rem 0;border:1px solid #999;border-radius:4px;background:#fff;cursor:grab"
        @dragstart=${(event) => {
          this.dragging = card.id;
          event.dataTransfer?.setData(CARD_TYPE, card.id);
        }}
        @dragend=${() => { this.dragging = null; }}>${String(card.label ?? card.id)}</li>`;
  }

  drop(event, column, cardsThere) {
    event.preventDefault();
    const card = event.dataTransfer?.getData(CARD_TYPE) || this.dragging;
    this.dragging = null;
    if (!card || cardsThere.some((there) => there.id === card)) return;
    this.context?.dispatchAction({ event: { name: DROP_ACTION, context: { card, column } } });
  }
}
BoardElement.tagName = 'bee-board';

/** The catalog entry: Board's API with its element, as the basic catalog's entries are built. */
export const Board = { ...BoardApi, tagName: BoardElement.tagName, element: BoardElement };
