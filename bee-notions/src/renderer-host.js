// bee's A2UI renderer host: web_core's MessageProcessor driving the stock Lit renderer,
// one <a2ui-surface> per surface. The reference host is A2UI's
// samples/client/lit/shell/app.ts. Only JSON strings cross to and from Kotlin/wasm.
//
// Pinned: @a2ui/lit 0.15.0 and @a2ui/web_core 0.15.0, speaking A2UI v0.9.1
// (`version: "v0.9"`). See docs/spikes/2026-11-spike-1-renderer-glue.md.
import { MessageProcessor, injectBasicCatalogStyles } from '@a2ui/web_core/v0_9';
// Registers <a2ui-surface>, as a side effect of importing the Lit entry point.
import { basicCatalog } from '@a2ui/lit/v0_9';

export const NOTIONS_CATALOG_ID = 'https://tractat.us/bee/catalogs/notions/v1.json';

// The Notions catalog: the basic catalog's components, under bee's own id. Sketch, Board
// and Poll join it in later tasks.
export const notionsCatalog = basicCatalog.derive({ catalogId: NOTIONS_CATALOG_ID });

/**
 * Mounts a renderer under `rootElement`.
 *
 * @param rootElement the element the surfaces are mounted in, in creation order
 * @param catalogs the catalogs surfaces may name; defaults to the Notions catalog
 * @returns `process(messagesJson)`, which applies a JSON array of A2UI messages and
 *   returns a JSON array of the errors the processor reported (empty when none), and
 *   `onAction(handler)`, which calls `handler` with each user action as a JSON string
 */
export function createRendererHost(rootElement, catalogs = [notionsCatalog]) {
  injectBasicCatalogStyles();
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
    rootElement.appendChild(element);
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
