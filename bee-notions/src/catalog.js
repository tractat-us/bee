// The Notions catalog (spec §5.6): A2UI v1.0's basic catalog's components, plus Sketch, Poll, Board
// and Chat, under bee's own id. Keep SurfaceValidator.NOTIONS_COMPONENT_TYPES (bee-a2ui) in step
// with this list: the bridge refuses what the renderer can't draw.
import { basicCatalog } from '@a2ui/web_core/catalogs/basic/v1';
import { TextApi as V09TextApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Board } from './board.js';
import { Chat } from './chat.js';
import { Poll } from './poll.js';
import { Sketch } from './sketch.js';

export const NOTIONS_CATALOG_ID = 'https://tractat.us/bee/catalogs/notions/v1.json';

/**
 * Text, with v0.9.1's `variant`s back: `h1`–`h5` beside `caption` and `body` (bee#230). v1.0's
 * basic catalog narrows `variant` to `caption` and `body`, and refuses a heading. The shared
 * `<a2ui-basic-text>` element still draws `h1`–`h5`, and bee's join requests, guides, checkin
 * skill and saved boards use them, so Notions widens the API and keeps the element.
 */
const basicText = basicCatalog.components.get('Text');
export const Text = {
  ...basicText,
  schema: basicText.schema.extend({ variant: V09TextApi.schema.shape.variant }),
};

export const notionsCatalog = basicCatalog.derive({
  catalogId: NOTIONS_CATALOG_ID,
  components: [...basicCatalog.components.keys()].map((name) => (name === Text.name ? Text : name))
    .concat([Sketch, Poll, Board, Chat]),
});
