package us.tractat.bee.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

/** The patch that opens surface [id], titled [title], as [author] at [at]: one Text component. */
internal fun BoardState.open(author: ReplicaId, id: String, title: String, at: Instant): Patch<BoardState> = openSurface(
    author,
    id,
    SurfaceMeta(title, NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), at),
    listOf(
        buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", title)
        },
    ),
    JsonObject(emptyMap()),
)
