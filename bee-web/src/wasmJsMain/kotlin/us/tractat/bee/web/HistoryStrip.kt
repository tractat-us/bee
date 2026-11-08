package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.crdt.ReplicaId

/**
 * The history strip (spec §6.1): the board's closed surfaces, newest first, collapsed until the
 * person opens it. "Clear the board" closes surfaces and never deletes them, so a click on one
 * here reopens it with its content and its records ([BoardState.reopenSurface]).
 *
 * It's a `<details id="history">`, so its entries are hidden until opened. Each entry is a button
 * showing the surface's title as text, with `data-surface-id`.
 *
 * @param parent where the strip goes
 * @param author this page load's CRDT author, which writes the reopen
 * @param onProblem shows a reopen the board refused
 */
class HistoryStrip(
    private val client: BoardClient,
    private val author: ReplicaId,
    parent: Element,
    private val onProblem: (String) -> Unit,
) {
    private val strip = document.createElement("details").also {
        it.id = "history"
        parent.appendChild(it)
    }
    private val summary = document.createElement("summary").also { strip.appendChild(it) }
    private val list = document.createElement("ol").also { strip.appendChild(it) }

    /** Shows the closed surfaces as they change, until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        client.state.map(::closed).distinctUntilChanged().collect { show(it, scope) }
    }

    /** Each closed surface's id and title, newest first. */
    private fun closed(state: BoardState): List<Pair<String, String>> =
        state.closedSurfaces().map { id -> id to (state.meta(id)?.title ?: id) }

    private fun show(closed: List<Pair<String, String>>, scope: CoroutineScope) {
        summary.textContent = "Closed surfaces (${closed.size})"
        while (list.firstChild != null) list.firstChild?.let { list.removeChild(it) }
        closed.forEach { (id, title) ->
            val button = document.createElement("button").also {
                it.setAttribute("type", "button")
                it.setAttribute("data-surface-id", id)
                it.textContent = title
            }
            button.addEventListener("click", { scope.launch { reopen(id) } })
            list.appendChild(document.createElement("li").also { it.appendChild(button) })
        }
    }

    private suspend fun reopen(id: String) {
        try {
            client.mutate { it.reopenSurface(author, id) }
        } catch (refused: IllegalArgumentException) {
            onProblem("Couldn't reopen $id: ${refused.message}")
        }
    }
}
