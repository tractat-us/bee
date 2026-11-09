package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** The data write a Notions action makes beside its record: [value] at the JSON Pointer [path]. */
sealed interface NotionEdit {
    val path: String
    val value: JsonElement

    /** [value] written at [path], from the register just read (A13). */
    data class Write(override val path: String, override val value: JsonElement) : NotionEdit

    /** [value] appended as a new entry of the append-only array at [path] (A12). */
    data class Append(override val path: String, override val value: JsonElement) : NotionEdit
}

/**
 * What `Poll` and `Board` actions write (spec §5.6, plan Task 11), read from the surface the actor
 * sees. The writer applies the edit from the register just read (A13), then appends the record.
 *
 * - **`Poll`** binds `question` (an append-only array of revisions; the last shows), `options` (an
 *   append-only array; each entry a string, which is its own id, or `{id, label}`) and `answers`
 *   (an object keyed by `PeerIdentity.toString()`, each value an option id). The action
 *   [ANSWER], with context `{option}`, writes `answers/<actor>`, so one person counts once
 *   however many tabs they answer from.
 * - **`Board`** binds `columns` and `cards`, each an ordered collection `{items, order}`: `items`
 *   keyed by id (a column `{title, owner?}`, a card `{label, column, href?, detail?}`), and `order`
 *   an array of ids. The action [DROP], with context `{card, column}`, writes the card's `column` leaf.
 *
 * Every other action only records.
 */
object NotionActions {
    /** A `Poll` answer: context `{option}`. */
    const val ANSWER = "answer"

    /** A `Board` drop: context `{card, column}`. */
    const val DROP = "drop"

    /** A `Chat` send: context `{id, text}`. */
    const val SEND = "send"

    /** The most characters, in UTF-16 code units as a page's `maxlength` counts them, a Chat message holds. */
    const val MAX_MESSAGE_LENGTH = 2000

    /**
     * The edit [action] by [actor] makes on [view], or null for an action that only records.
     *
     * @throws IllegalArgumentException for an answer naming no option of the poll, or a drop naming
     *   a card or column the board doesn't hold
     */
    fun edit(view: SurfaceView, action: A2uiAction, actor: String, at: Instant): NotionEdit? {
        val component = view.components[action.sourceComponentId] ?: return null
        return when (component.type() to action.name) {
            SurfaceValidator.POLL to ANSWER -> answer(view, component, action, actor)
            SurfaceValidator.BOARD to DROP -> drop(view, component, action)
            else -> null
        }
    }

    /** Each option's id, in order: a string entry is its own id, an object entry names its `id`. */
    fun optionIds(options: JsonElement?): List<String> = (options as? JsonArray).orEmpty().mapNotNull(::optionId)

    private fun answer(view: SurfaceView, poll: JsonObject, action: A2uiAction, actor: String): NotionEdit {
        val option = action.string("option")
        require(option in optionIds(view.at(poll.path("options")))) {
            "Poll \"${action.sourceComponentId}\" has no option \"$option\"."
        }
        return NotionEdit.Write("${poll.path("answers")}/${SurfaceFormat.escapeSegment(actor)}", JsonPrimitive(option))
    }

    private fun drop(view: SurfaceView, board: JsonObject, action: A2uiAction): NotionEdit {
        val card = action.string("card")
        val column = action.string("column")
        val cards = board.path("cards")
        require(card in view.items(cards)) { "Board \"${action.sourceComponentId}\" has no card \"$card\"." }
        require(column in view.items(board.path("columns"))) { "Board \"${action.sourceComponentId}\" has no column \"$column\"." }
        return NotionEdit.Write("$cards/items/${SurfaceFormat.escapeSegment(card)}/column", JsonPrimitive(column))
    }

    private fun optionId(entry: JsonElement): String? = when (entry) {
        is JsonPrimitive -> entry.takeIf { it.isString }?.content
        is JsonObject -> (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }

    private fun JsonObject.type(): String? = (this["component"] as? JsonPrimitive)?.content

    /** The data path a property binds; the validator has checked it's a binding. */
    private fun JsonObject.path(property: String): String =
        requireNotNull(((this[property] as? JsonObject)?.get("path") as? JsonPrimitive)?.content) {
            "Component \"${this["id"]}\" binds no $property."
        }

    private fun SurfaceView.at(pointer: String): JsonElement? =
        SurfaceFormat.segments(pointer).fold(data as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

    private fun SurfaceView.items(pointer: String): Set<String> =
        ((at(pointer) as? JsonObject)?.get("items") as? JsonObject)?.keys.orEmpty()

    private fun A2uiAction.string(key: String): String =
        requireNotNull((context[key] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "A $name on \"$sourceComponentId\" has no $key."
        }
}
