package us.tractat.bee.app

import us.tractat.bee.core.BoardState

/** The titles of the board's open surfaces, in the order they were opened. */
fun BoardState.openSurfaceTitles(): List<String> = openSurfaces().map { id ->
    // openSurfaces lists only surfaces whose open, and so whose meta, has arrived.
    checkNotNull(meta(id)) { "Open surface $id has no meta." }.title
}
