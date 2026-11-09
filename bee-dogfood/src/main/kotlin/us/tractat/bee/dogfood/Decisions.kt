package us.tractat.bee.dogfood

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An open issue or PR waiting on a design call, as `gh … list --json number,title,url,body,labels` gives it. */
data class Decision(val number: Int, val title: String, val url: String, val labels: Set<String>, val body: String)

/**
 * The open decisions (bee#21): one card per issue or PR labelled [DECISION_NEEDED] or
 * [NEEDS_DESIGN], newest first, each with its number, its title and the question its body asks.
 */
object Decisions {
    const val SURFACE_ID = "dogfood-decisions"
    const val TITLE = "Decisions"
    const val DECISION_NEEDED = "decision-needed"
    const val NEEDS_DESIGN = "needs-design"
    val LABELS = listOf(DECISION_NEEDED, NEEDS_DESIGN)
    const val QUESTION_CHARS = 200

    /** The decisions in one `gh … list --json` answer. */
    fun parse(ghJson: String): List<Decision> = Json.parseToJsonElement(ghJson).jsonArray.map { it.jsonObject.decision() }

    /** The surface for [decisions], deduplicated by number; [unavailable] says why there is no list. */
    fun surface(decisions: List<Decision>, unavailable: String? = null): SeedSurface {
        val shown = decisions.distinctBy { it.number }.sortedByDescending { it.number }
        val cards = shown.flatMap { decision ->
            val id = "d-${decision.number}"
            listOf(Components.card(id, "$id-text"), Components.text("$id-text", markdown(decision)))
        }
        val empty = unavailable ?: "No open issue or PR is labelled `$DECISION_NEEDED` or `$NEEDS_DESIGN`."
        val children = if (shown.isEmpty()) listOf("none") else shown.map { "d-${it.number}" }
        val none = if (shown.isEmpty()) listOf(Components.text("none", empty)) else emptyList()
        return SeedSurface(SURFACE_ID, TITLE, listOf(Components.column(SeedSurface.ROOT, children)) + cards + none)
    }

    /** One card's Markdown: `**[#150](url) Title** · design`, then the question, if the body asks one. */
    fun markdown(decision: Decision): String {
        val kind = if (DECISION_NEEDED in decision.labels) "decide" else "design"
        val head = "**[#${decision.number}](${decision.url}) ${decision.title}** · $kind"
        return question(decision.body)?.takeUnless { it == decision.title }?.let { "$head\n\n$it" } ?: head
    }

    /**
     * The first sentence of [body] that ends in a question mark, cut to [QUESTION_CHARS], or null
     * if it asks none. Quoted lines, such as an attribution, are skipped, and list markers and
     * bold markers are dropped.
     */
    fun question(body: String): String? = body.lines()
        .filterNot { it.trimStart().startsWith(">") }
        .map { it.trim().removePrefix("- ").removePrefix("* ").replace("**", "") }
        .firstNotNullOfOrNull { line -> QUESTION.find(line)?.value?.trim() }
        ?.let { if (it.length <= QUESTION_CHARS) it else "${it.take(QUESTION_CHARS - 1).trimEnd()}…" }

    private fun JsonObject.decision() = Decision(
        number = getValue("number").jsonPrimitive.content.toInt(),
        title = string("title"),
        url = string("url"),
        labels = this["labels"]?.jsonArray.orEmpty().map { it.jsonObject.string("name") }.toSet(),
        body = stringOrNull("body").orEmpty(),
    )

    /** A sentence ending in `?`: from the line's start or just after another sentence's end. */
    private val QUESTION = Regex("""(?:^|(?<=[.!:]\s))[^.!?:]*\?""")
}
