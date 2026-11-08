// `Poll`: a question, options, and live per-peer answers (spec §5.6, plan Task 11). Every label
// is bound as Lit text (`${}`), never as markup, so an option like `<img onerror>` shows literally.
//
// Answering dispatches the A2UI action `answer` with context `{option}`. The page's driver
// (bee-a2ui's NotionActions) writes `answers/<peer>` from the register it read and appends the
// record, so one person counts once however many tabs they answer from.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';

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

/** `<bee-poll>`: the question, then each option with its count and who chose it. */
export class PollElement extends A2uiLitElement {
  /** Light DOM, as the basic catalog's elements render. */
  createRenderRoot() {
    return this;
  }

  render() {
    const props = this.controller?.props;
    if (!props) return nothing;
    const options = pollOptions(props.options);
    const voters = new Map(options.map((option) => [option.id, []]));
    const answers = props.answers && typeof props.answers === 'object' ? props.answers : {};
    for (const [peer, answer] of Object.entries(answers).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) {
      voters.get(String(answer))?.push(peer);
    }
    const total = [...voters.values()].reduce((sum, peers) => sum + peers.length, 0);
    return html`<div class="bee-poll">
      <p class="question">${latestRevision(props.question)}</p>
      <ul>
        ${options.map((option) => html`<li data-option=${option.id}>
          <button type="button" @click=${() => this.answer(option.id)}>${option.label}</button>
          <span class="count">${voters.get(option.id).length}</span>
          <span class="voters">${voters.get(option.id).join(', ')}</span>
        </li>`)}
      </ul>
      <p class="total">${total} ${total === 1 ? 'answer' : 'answers'}</p>
    </div>`;
  }

  answer(option) {
    this.context?.dispatchAction({ event: { name: ANSWER_ACTION, context: { option } } });
  }
}
PollElement.tagName = 'bee-poll';

/** The catalog entry: Poll's API with its element, as the basic catalog's entries are built. */
export const Poll = { ...PollApi, tagName: PollElement.tagName, element: PollElement };
