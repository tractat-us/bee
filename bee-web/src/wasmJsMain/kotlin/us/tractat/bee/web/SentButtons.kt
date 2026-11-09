package us.tractat.bee.web

import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import us.tractat.bee.a2ui.SurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.RecordId

/** bee-notions' sent-button hook (bee-notions/src/renderer-host.js). Only strings cross. */
@JsModule("@bee/notions")
external object NotionsSent : JsAny {
    /** Shows Button [componentId] on [surfaceId] as sent (`aria-disabled`), or not. */
    fun markSent(rootElement: Element, surfaceId: String, componentId: String, sent: Boolean)
}

/**
 * The Buttons this page pressed whose records wait to be handled (bee#213). An agent's answer can
 * take minutes, or never come, so the page acknowledges a press itself: the button reads sent
 * ([NotionsSent.markSent]) from the moment its record is written, and a press on it then is
 * refused, so a double click writes one record. It reads sent until either:
 *
 * - **the record is handled:** the host mirrors its claim as [ClaimView.DONE] once an agent calls
 *   `bee_action_done`. A claim held while an agent works on it isn't handled.
 * - **the surface changes under it:** its view isn't the one the record was written against, or it
 *   isn't shown at all. A handler that answers by redrawing, as the host's join request does after
 *   a wrong code, never marks the record done, and the person must be able to press again.
 *
 * Only the presser's page shows it. Another page can't tell whether the surface changed since the
 * press: a record's basis names appended entries, not the components or data it was written
 * against. And another person may press the same button for themselves (`two-browsers.spec.mjs`).
 */
class SentButtons(private val mark: (surfaceId: String, componentId: String, sent: Boolean) -> Unit) {
    private data class Pressed(val surfaceId: String, val componentId: String)

    /** A press's record, and the surface as the board held it when the record was written. */
    private data class Waiting(val record: RecordId, val against: SurfaceView)

    private val waiting = mutableMapOf<Pressed, Waiting>()

    /** Whether [componentId] on [surfaceId] was pressed here and waits, so a press on it is refused. */
    fun waits(surfaceId: String, componentId: String): Boolean = Pressed(surfaceId, componentId) in waiting

    /**
     * [componentId] on [against]'s surface was pressed and [record] written for it, against
     * [against]; [seen] is the surface as the person saw it. Shown as sent if it's a Button.
     */
    fun pressed(componentId: String, seen: SurfaceView?, record: RecordId, against: SurfaceView) {
        if (!seen.isButton(componentId)) return
        waiting[Pressed(against.id, componentId)] = Waiting(record, against)
        mark(against.id, componentId, true)
    }

    /**
     * Clears each press on [surfaceId] that [state] shows handled, or whose surface now reads
     * [view] rather than what it was written against; [view] is null for a surface not shown.
     */
    fun settle(surfaceId: String, view: SurfaceView?, state: BoardState) {
        waiting.entries
            .filter { (pressed, wait) -> pressed.surfaceId == surfaceId && (wait.against != view || state.handled(wait.record)) }
            .map { it.key }
            .forEach { pressed ->
                waiting.remove(pressed)
                mark(pressed.surfaceId, pressed.componentId, false)
            }
    }

    private companion object {
        fun BoardState.handled(record: RecordId): Boolean = claimView(record.claimTarget)?.status == ClaimView.DONE

        fun SurfaceView?.isButton(componentId: String): Boolean =
            (this?.components?.get(componentId)?.get("component") as? JsonPrimitive)?.content == "Button"
    }
}
