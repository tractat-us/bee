// bee's A2UI renderer host: web_core's MessageProcessor driving the stock Lit renderer,
// one <a2ui-surface> per surface. The reference host is A2UI's
// samples/client/lit/shell/app.ts. Only JSON strings cross to and from Kotlin/wasm.
//
// Pinned: @a2ui/lit 0.15.0 and @a2ui/web_core 0.15.0, speaking A2UI v0.9.1
// (`version: "v0.9"`). See docs/spikes/2026-11-spike-1-renderer-glue.md.
import { MessageProcessor, injectBasicCatalogStyles } from '@a2ui/web_core/v0_9';
// Registers <a2ui-surface>, as a side effect of importing the Lit entry point.
import { Context } from '@a2ui/lit/v0_9';
import { renderMarkdown } from '@a2ui/markdown-it';
import { NOTIONS_CATALOG_ID, notionsCatalog } from './catalog.js';

export { NOTIONS_CATALOG_ID, notionsCatalog };

/**
 * Mounts a renderer under `rootElement`.
 *
 * Text in the body variants renders its Markdown through `@a2ui/markdown-it`, which sanitizes the
 * HTML it makes (DOMPurify): the renderer asks for that renderer through Lit context, and the root
 * answers. The heading and caption variants show their text as is.
 *
 * @param rootElement the element the surfaces are mounted under
 * @param catalogs the catalogs surfaces may name; defaults to the Notions catalog
 * @param place puts a new surface's `<a2ui-surface>` on the page, given the surface id and the
 *   element; defaults to appending it to `rootElement`, so surfaces show in creation order
 * @returns `process(messagesJson)`, which applies a JSON array of A2UI messages and
 *   returns a JSON array of the errors the processor reported (empty when none), and
 *   `onAction(handler)`, which calls `handler` with each user action as a JSON string
 */
export function createRendererHost(rootElement, catalogs = [notionsCatalog], place = (id, element) => rootElement.appendChild(element)) {
  injectBasicCatalogStyles();
  provideMarkdown(rootElement);
  const actionHandlers = [];
  const processor = new MessageProcessor(catalogs, (action) => {
    const json = JSON.stringify(action);
    for (const handler of actionHandlers) handler(json);
  });
  const mounted = new Map();

  processor.onSurfaceCreated((surface) => {
    const element = document.createElement('a2ui-surface');
    element.dataset.surfaceId = surface.id;
    element.surface = surface;
    place(surface.id, element);
    mounted.set(surface.id, element);
  });
  processor.onSurfaceDeleted((id) => {
    mounted.get(id)?.remove();
    mounted.delete(id);
  });

  return {
    process(messagesJson) {
      const errors = processor.processMessagesWithErrors(JSON.parse(messagesJson));
      return JSON.stringify(errors);
    },
    onAction(handler) {
      actionHandlers.push(handler);
    },
  };
}

/**
 * A renderer host for the bee page: the Notions catalog, each surface placed by `place(id, element)`.
 * Kotlin can't leave an argument out, so the page calls this rather than `createRendererHost`.
 */
export function createPlacedRendererHost(rootElement, place) {
  return createRendererHost(rootElement, [notionsCatalog], place);
}

/**
 * Answers the basic catalog's request for a Markdown renderer (a `context-request` event, the Lit
 * context protocol, for `Symbol.for('A2UIMarkdown')`) from under `rootElement`. The renderer never
 * changes, so a subscriber is answered once.
 */
function provideMarkdown(rootElement) {
  rootElement.addEventListener('context-request', (event) => {
    if (event.context !== Context.markdown) return;
    event.stopPropagation();
    event.callback(renderMarkdown, () => {});
  });
}

/** The `<a2ui-surface>` showing `surfaceId` under `rootElement`, or null. */
function surfaceElement(rootElement, surfaceId) {
  for (const element of rootElement.querySelectorAll('a2ui-surface')) {
    if (element.dataset.surfaceId === surfaceId) return element;
  }
  return null;
}

/**
 * Subscribes to web_core's `DataModel.subscribe(path, …)` on the surface `surfaceId` shown
 * under `rootElement`, for live shared typing (spec §5.3): a bound input such as a `TextField`
 * writes only the renderer's local model, so bee's LiveInputs watches it.
 *
 * @param handler called with the path's new value as JSON (`null` when the path is absent),
 *   each time it changes after this call, whoever changed it
 * @returns `{ unsubscribe() }`, or `null` if no surface `surfaceId` is shown
 */
export function subscribeData(rootElement, surfaceId, path, handler) {
  const dataModel = surfaceElement(rootElement, surfaceId)?.surface?.dataModel;
  if (!dataModel) return null;
  const subscription = dataModel.subscribe(path, (value) => handler(JSON.stringify(value ?? null)));
  return { unsubscribe: () => subscription.unsubscribe() };
}

/**
 * Reports focus entering and leaving a `TextField` under `rootElement`, as JSON
 * `{surfaceId, componentId, focused}`, or `{error}` for a field it can't place. The basic
 * catalog renders into light DOM, as `<a2ui-basic-textfield>`, so focus events reach the root
 * with the input as their target. Focus on anything else is not a field's, and isn't reported.
 */
export function onTextFieldFocus(rootElement, handler) {
  const report = (event, focused) => {
    const field = event.target?.closest?.('a2ui-basic-textfield');
    if (!field) return;
    const surfaceId = field.closest('a2ui-surface')?.dataset.surfaceId;
    const componentId = field.context?.componentModel?.id;
    if (surfaceId && componentId) {
      handler(JSON.stringify({ surfaceId, componentId, focused }));
    } else {
      handler(JSON.stringify({ error: `a TextField with surface ${surfaceId} and component ${componentId}` }));
    }
  };
  rootElement.addEventListener('focusin', (event) => report(event, true));
  rootElement.addEventListener('focusout', (event) => report(event, false));
}
