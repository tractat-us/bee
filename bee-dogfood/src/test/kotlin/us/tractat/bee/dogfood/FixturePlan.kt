package us.tractat.bee.dogfood

import kotlinx.serialization.json.JsonObject

/**
 * A small plan in `plan.yaml`'s shape, so the mapping tests don't move with the live plan: seven
 * done items (two more than [PlanBoard.DONE_SHOWN]), and one each in flight, waiting on Iain and next.
 */
internal object FixturePlan {
    const val IN_FLIGHT = "bee-21"
    const val NEXT = "bee-30"
    const val DONE_ITEMS = 7

    val yaml: String = buildString {
        appendLine("schema_version: 1")
        appendLine("columns:")
        appendLine("  done: { title: \"Done\" }")
        appendLine("  in-flight: { title: \"In flight\" }")
        appendLine("  waiting-on-iain: { title: \"Waiting on Iain\", owner: \"iain\" }")
        appendLine("  next: { title: \"Next\" }")
        appendLine("column_order: [done, in-flight, waiting-on-iain, next]")
        val done = (1..DONE_ITEMS).map { "task-$it" }
        appendLine("order: [${(done + listOf(IN_FLIGHT, "bee-25", NEXT)).joinToString()}]")
        appendLine("items:")
        done.forEachIndexed { i, id ->
            appendLine("  $id: { kind: task, title: \"Task ${i + 1}\", status: done, landed: \"2026-11-0${i + 1}T12:00Z\" }")
        }
        appendLine("  $IN_FLIGHT:")
        appendLine("    kind: issue")
        appendLine("    title: \"bee's own development board\"")
        appendLine("    status: in-flight")
        appendLine("    summary: \"Stand it up locally. Then the attention-mode switch.\"")
        appendLine("    links: [{ title: \"bee#21\", url: \"https://github.com/tractat-us/bee/issues/21\" }]")
        appendLine("  bee-25: { kind: issue, title: \"A call for Iain\", status: waiting-on-iain }")
        appendLine("  $NEXT: { kind: issue, title: \"Card links\", status: next }")
    }

    fun parse(): JsonObject = PlanYaml.parse(yaml)
}
