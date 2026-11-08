// `Poll`: a question, options, and live per-peer answers (spec §5.6, plan Task 11). Every label
// is bound as Lit text (`${}`), never as markup, so an option like `<img onerror>` shows literally.
//
// Answering dispatches the A2UI action `answer` with context `{option}`. The page's driver
// (bee-a2ui's NotionActions) writes `answers/<peer>` from the register it read and appends the
// record, so one person counts once however many tabs they answer from.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';
import { BoundData } from './bound-data.js';

/** The A2UI action an answer becomes (NotionActions.ANSWER). */
export const ANSWER_ACTION = 'answer';

/**
 * Poll's properties, each a data binding: `question` (an append-only array of revisions; the
 * last one shows), `options` (an append-only array, each entry a string that is its own id, or
 * `{id, label}`) and `answers` (an object keyed by peer identity, each value an option id).
 * Keep SurfaceValidator.POLL (bee-a2ui) in step: the bridge refuses what the renderer can't draw.
 */
export const PollApi = {
  name: 'Poll',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      question: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The question: revisions, the last of which shows.'),
      options: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The options: strings, or {id, label}.'),
      answers: DynamicValueSchema.describe("REF:#/$defs/DynamicValue|Each peer's answer, an option id, keyed by peer."),
    })
    .strict(),
};

/** The question a bound value shows: the last revision of an array, or a plain string as it is. */
export function latestRevision(question) {
  if (Array.isArray(question)) return question.length ? String(question[question.length - 1]) : '';
  return question == null ? '' : String(question);
}

/**
 * The options a bound array holds, as `{id, label}`, each id once at its first occurrence: two
 * peers appending one option at once must not show it twice.
 */
export function pollOptions(options) {
  const seen = new Set();
  const shown = [];
  for (const entry of Array.isArray(options) ? options : []) {
    const option = typeof entry === 'string'
      ? { id: entry, label: entry }
      : entry && typeof entry === 'object' && typeof entry.id === 'string'
        ? { id: entry.id, label: String(entry.label ?? entry.id) }
        : null;
    if (option && !seen.has(option.id)) {
      seen.add(option.id);
      shown.push(option);
    }
  }
  return shown;
}

/**
 * The name part of a peer identity, as a voter list shows it: `human:sam` is `sam`,
 * `agent:scout#1a2b3c4d` is `scout`. Anything else shows as it is.
 */
export function peerName(peer) {
  const match = /^[a-z]+:([^:#]+)(#[0-9a-f]{8})?$/.exec(String(peer));
  return match ? match[1] : String(peer);
}

// The page's colours (index.html's tokens), with fallbacks for a page that doesn't define them.
// Styled inline, as Board is: the element renders into light DOM, inside the page's card.
const INK = 'var(--ink,#1f2328)';
const MUTED = 'var(--muted,#656d76)';
const LINE = 'var(--line,#d8d6d0)';
const CARD = 'var(--card,#fff)';
const ACCENT = 'var(--accent,#b7791f)';

const ROW = 'position:relative;display:flex;align-items:center;gap:0.75rem;width:100%;min-height:3rem;'
  + `padding:0.6rem 0.9rem;border:1px solid ${LINE};border-radius:10px;background:${CARD};color:${INK};`
  + 'font:inherit;font-size:1.05rem;text-align:left;cursor:pointer;overflow:hidden';
const ROW_CHOSEN = `border:2px solid ${ACCENT};padding:calc(0.6rem - 1px) calc(0.9rem - 1px)`;
const BAR = 'position:absolute;inset:0 auto 0 0;pointer-events:none;transition:width 0.3s;'
  + `background:color-mix(in srgb, ${ACCENT} 18%, transparent)`;
const VOTERS = 'position:relative;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
  + `font-size:0.85rem;color:${MUTED}`;
const COUNT = 'position:relative;font-size:1.3rem;font-weight:700;font-variant-numeric:tabular-nums';

/**
 * `<bee-poll>`: the question, then one row per option: its label, who chose it, its count, and a
 * bar for its share of the answers. The viewer's own answer is marked (`aria-pressed`). The viewer
 * is the nearest `data-viewer` above the element, which bee-web's page sets on `#board`.
 */
export class PollElement extends A2uiLitElement {
  constructor() {
    super();
    this.bound = new BoundData(this, ['question', 'options', 'answers']);
  }

  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  render() {
    if (!this.controller?.props) return nothing;
    const props = this.bound.values();
    const options = pollOptions(props.options);
    const voters = new Map(options.map((option) => [option.id, []]));
    const answers = props.answers && typeof props.answers === 'object' ? props.answers : {};
    for (const [peer, answer] of Object.entries(answers).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) {
      voters.get(String(answer))?.push(peer);
    }
    const total = [...voters.values()].reduce((sum, peers) => sum + peers.length, 0);
    const viewer = this.closest('[data-viewer]')?.getAttribute('data-viewer');
    const mine = viewer && Object.hasOwn(answers, viewer) ? String(answers[viewer]) : null;
    return html`<div class="bee-poll" style="color:${INK}">
      <p class="question" style="margin:0 0 0.75rem;font-size:1.15rem;font-weight:600">${latestRevision(props.question)}</p>
      <ul style="list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:0.5rem">
        ${options.map((option) => this.row(option, voters.get(option.id), total, option.id === mine))}
      </ul>
      <p class="total" style="margin:0.75rem 0 0;font-size:0.85rem;color:${MUTED}">${total} ${total === 1 ? 'answer' : 'answers'}</p>
    </div>`;
  }

  /** One option's row: the whole row answers it, and its bar is its share of `total`. */
  row(option, peers, total, chosen) {
    const share = total ? Math.round((peers.length / total) * 100) : 0;
    return html`<li data-option=${option.id}>
      <button type="button" aria-pressed=${chosen ? 'true' : 'false'} style="${ROW};${chosen ? ROW_CHOSEN : ''}"
          @click=${() => this.answer(option.id)}>
        <span class="bar" style="${BAR};width:${share}%"></span>
        <span class="label" style="position:relative;font-weight:${chosen ? 700 : 500}">${option.label}</span>
        <span class="voters" title=${peers.join(', ')} style=${VOTERS}>${peers.map(peerName).join(', ')}</span>
        <span class="count" style=${COUNT}>${peers.length}</span>
      </button>
    </li>`;
  }

  answer(option) {
    this.context?.dispatchAction({ event: { name: ANSWER_ACTION, context: { option } } });
  }
}
PollElement.tagName = 'bee-poll';

/** The catalog entry: Poll's API with its element, as the basic catalog's entries are built. */
export const Poll = { ...PollApi, tagName: PollElement.tagName, element: PollElement };
