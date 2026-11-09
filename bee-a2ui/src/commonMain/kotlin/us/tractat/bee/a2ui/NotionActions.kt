package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * - **`Chat`** binds `messages`, an append-only array of `{id, author, text, at}`. The action
 *   [SEND], with context `{id, text}`, appends the message as the actor's, stamped with the
 *   record's time, so two people sending at once both land, each once (bee#214). A session
 *   posts only its text through the bridge's `append`, which stamps it the same way ([post]).
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
     * @param at when the actor acted, the time its record carries: a Chat message is stamped with it
     * @throws IllegalArgumentException for an answer naming no option of the poll, a drop naming
     *   a card or column the board doesn't hold, or a message with no id or a [textProblem]
     */
    fun edit(view: SurfaceView, action: A2uiAction, actor: String, at: Instant): NotionEdit? {
        val component = view.components[action.sourceComponentId] ?: return null
        return when (component.type() to action.name) {
            SurfaceValidator.POLL to ANSWER -> answer(view, component, action, actor)
            SurfaceValidator.BOARD to DROP -> drop(view, component, action)
            SurfaceValidator.CHAT to SEND -> send(view, component, action, actor, at)
            else -> null
        }
    }

    /**
     * What's wrong with a Chat message's [text], completing "A message …", or null when it may be
     * sent: it must hold something besides whitespace, and at most [MAX_MESSAGE_LENGTH] characters.
     */
    fun textProblem(text: String): String? = when {
        text.isBlank() -> "is empty"
        text.length > MAX_MESSAGE_LENGTH -> "is ${text.length} characters, past the $MAX_MESSAGE_LENGTH a message holds"
        else -> null
    }

    /** The id of the Chat in [view] whose `messages` binds [pointer], or null if no Chat binds it. */
    fun chatAt(view: SurfaceView, pointer: String): String? {
        val at = normalized(pointer) ?: return null
        return view.components.entries.firstOrNull { (_, component) ->
            component.type() == SurfaceValidator.CHAT && component.boundPath("messages")?.let(::normalized) == at
        }?.key
    }

    /**
     * The message [actor], a session, posts to Chat [chat] with [entry], `{"text": …}`: stamped
     * with [id], [actor] as its author, and [at], as a page's [SEND] stamps one. A session names
     * nothing but its text, so it can't post as anyone else (bee#214).
     *
     * @throws IllegalArgumentException for an entry with no text, one naming another author, one
     *   bringing its own id or time, or a [textProblem]
     */
    fun post(chat: String, entry: JsonElement, actor: String, id: String, at: Instant): JsonObject {
        val fields = entry as? JsonObject
        val text = fields?.get("text")?.stringOrNull()
        require(fields != null && text != null) { "A message on Chat \"$chat\" is {\"text\": \"…\"}; got $entry." }
        require(fields.keys.all { it in POSTED_FIELDS }) {
            "A message on Chat \"$chat\" carries only text: its id, author and at are stamped for it."
        }
        fields["author"]?.let { named ->
            require(named == JsonPrimitive(actor)) { "A message on Chat \"$chat\" can't name author $named: it posts as $actor." }
        }
        textProblem(text)?.let { throw IllegalArgumentException("A message on Chat \"$chat\" $it.") }
        return message(id, actor, text, at)
    }

    /** What a session may put in a message it posts: its text, and its own identity as author. */
    private val POSTED_FIELDS = setOf("text", "author")

    /** A message from [actor], stamped [at], appended to the Chat's messages: `{id, author, text, at}`. */
    private fun send(view: SurfaceView, chat: JsonObject, action: A2uiAction, actor: String, at: Instant): NotionEdit {
        val id = action.string("id")
        val text = action.string("text")
        val messages = chat.path("messages")
        require(id.isNotBlank()) { "A message on Chat \"${action.sourceComponentId}\" has no id." }
        require(id !in messageIds(view.at(messages))) {
            "A message on Chat \"${action.sourceComponentId}\" reuses id \"$id\", which an earlier message holds."
        }
        textProblem(text)?.let { throw IllegalArgumentException("A message on Chat \"${action.sourceComponentId}\" $it.") }
        return NotionEdit.Append(messages, message(id, actor, text, at))
    }

    private fun message(id: String, author: String, text: String, at: Instant) = buildJsonObject {
        put("id", id)
        put("author", author)
        put("text", text)
        put("at", at.toString())
    }

    private fun messageIds(messages: JsonElement?): Set<String> =
        (messages as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id"))?.stringOrNull() }.toSet()

    /** [pointer] in its one written form, or null if it isn't a JSON Pointer. */
    private fun normalized(pointer: String): String? = try {
        SurfaceFormat.pointer(SurfaceFormat.segments(pointer))
    } catch (_: IllegalArgumentException) {
        null
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
        requireNotNull(boundPath(property)) { "Component \"${this["id"]}\" binds no $property." }

    /** The data path [property] binds, or null if it binds none. */
    private fun JsonObject.boundPath(property: String): String? = ((this[property] as? JsonObject)?.get("path"))?.stringOrNull()

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun SurfaceView.at(pointer: String): JsonElement? =
        SurfaceFormat.segments(pointer).fold(data as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

    private fun SurfaceView.items(pointer: String): Set<String> =
        ((at(pointer) as? JsonObject)?.get("items") as? JsonObject)?.keys.orEmpty()

    private fun A2uiAction.string(key: String): String =
        requireNotNull((context[key] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "A $name on \"$sourceComponentId\" has no $key."
        }
}
