// The Notions catalog (spec §5.6): the basic catalog's components, plus Sketch, Poll and Board,
// under bee's own id. Keep SurfaceValidator.NOTIONS_COMPONENT_TYPES (bee-a2ui) in step with this
// list: the bridge refuses what the renderer can't draw.
import { basicCatalog } from '@a2ui/lit/v0_9';
import { Board } from './board.js';
import { Poll } from './poll.js';
import { Sketch } from './sketch.js';

export const NOTIONS_CATALOG_ID = 'https://tractat.us/bee/catalogs/notions/v1.json';

export const notionsCatalog = basicCatalog.derive({
  catalogId: NOTIONS_CATALOG_ID,
  components: [...basicCatalog.components.keys(), Sketch, Poll, Board],
});
