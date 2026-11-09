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
    /** Shows Button [componentId]'s [instance] (every instance if null) on [surfaceId] as sent (`aria-disabled`), or not. */
    fun markSent(rootElement: Element, surfaceId: String, componentId: String, instance: String?, sent: Boolean)
}

/**
 * One Button on the page: component [componentId] on [surfaceId], at data path [instance]. A list
 * template's rows share their component id and differ by path (`/items/0`, `/items/1`); a Button
 * outside a template is at `/`. Null when the page couldn't tell which instance was pressed.
 */
data class ButtonPress(val surfaceId: String, val componentId: String, val instance: String?)

/**
 * The Buttons this page pressed whose records wait to be handled (bee#213). An agent's answer can
 * take minutes, or never come, so the page acknowledges a press itself: the button reads sent
 * ([NotionsSent.markSent]) from the moment its record is written, and a press on it then is
 * refused, so a double click writes one record. Each instance of a templated Button is its own.
 * It reads sent until:
 *
 * - **the record is handled:** the host mirrors its claim as [ClaimView.DONE] once an agent calls
 *   `bee_action_done`.
 * - **the surface changes under it, while no agent holds the record:** its view isn't the one the
 *   record was written against. A handler that answers by redrawing without a claim, as the host's
 *   join request does after a wrong code, never marks the record done, and the person must be able
 *   to press again. While the claim reads [ClaimView.HELD], an agent is working on the press, and
 *   the writes it makes meanwhile (a check-in card's `/now`) don't clear it.
 * - **the surface isn't shown:** closed, or unreadable or invalid and shown as a placeholder.
 *
 * Only the presser's page shows it. Another page can't tell whether the surface changed since the
 * press: a record's basis names appended entries, not the components or data it was written
 * against. And another person may press the same button for themselves (`two-browsers.spec.mjs`).
 *
 * @param mark shows [ButtonPress]'s button as sent, or not
 */
class SentButtons(private val mark: (press: ButtonPress, sent: Boolean) -> Unit) {
    /** A press's record, and the surface as the board held it when the record was written. */
    private data class Waiting(val record: RecordId, val against: SurfaceView)

    private val waiting = mutableMapOf<ButtonPress, Waiting>()

    /** Whether [press]'s button was pressed here and waits, so a press on it is refused. */
    fun waits(press: ButtonPress): Boolean = press in waiting

    /**
     * [press] wrote [record], against [against], the surface as the board held it then; [seen] is
     * the surface as the person saw it. Shown as sent if it's a Button.
     */
    fun pressed(press: ButtonPress, seen: SurfaceView?, record: RecordId, against: SurfaceView) {
        if (!seen.isButton(press.componentId)) return
        waiting[press] = Waiting(record, against)
        mark(press, true)
    }

    /**
     * Clears each press on [surfaceId] that [state] shows handled, or that [view] moved on from
     * while no agent holds its record; [view] is null for a surface not shown, which clears them all.
     */
    fun settle(surfaceId: String, view: SurfaceView?, state: BoardState) {
        waiting.entries
            .filter { (press, wait) -> press.surfaceId == surfaceId && wait.over(view, state) }
            .map { it.key }
            .forEach { press ->
                waiting.remove(press)
                mark(press, false)
            }
    }

    private fun Waiting.over(view: SurfaceView?, state: BoardState): Boolean {
        val claim = state.claimView(record.claimTarget)?.status
        return view == null || claim == ClaimView.DONE || (against != view && claim != ClaimView.HELD)
    }

    private companion object {
        fun SurfaceView?.isButton(componentId: String): Boolean =
            (this?.components?.get(componentId)?.get("component") as? JsonPrimitive)?.content == "Button"
    }
}
