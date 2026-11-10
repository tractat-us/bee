package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every tool the bridge serves says whether it only reads, as a session's client sees it in
 * `tools/list`. A tool added without its entry here fails, so a new tool can't arrive unannotated.
 */
class ToolAnnotationsTest {
    private val options = RequestOptions(timeout = 5.minutes)

    private val expected: Map<String, ToolAnnotations> = mapOf(
        BeeTools.BOARD to read,
        WaitTool.LIST to read,
        WhiteboardGuide.EXAMPLES_TOOL to read,
        // A wait claims the record it returns, and shows the session waiting.
        WaitTool.WAIT to write(destructive = false, idempotent = false),
        BeeTools.OPEN to write(destructive = false, idempotent = true),
        BeeTools.UPDATE to write(destructive = true, idempotent = false),
        BeeTools.CLOSE to write(destructive = true, idempotent = true),
        BeeTools.CLEAR to write(destructive = true, idempotent = true),
        BeeTools.DONE to write(destructive = false, idempotent = true),
        BeeTools.CLAIM to write(destructive = false, idempotent = false),
        BeeTools.RELEASE to write(destructive = false, idempotent = true),
        BeeTools.PRESENCE to write(destructive = false, idempotent = true),
    )

    @Test
    fun everyServedToolDeclaresWhetherItOnlyReads() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val served = rig.session("ada").mcp.listTools(options = options).tools.associate { it.name to it.annotations }

        assertAll(
            { assertEquals(expected, served, "each tool's annotations, as tools/list serves them") },
            { assertEquals(emptySet(), served.filterValues { it?.readOnlyHint == null }.keys, "tools that don't say whether they only read") },
            {
                // Codex's default `auto` mode runs a write without asking when it is neither destructive nor open-world.
                val unasked = served.filterValues { it?.readOnlyHint == false && it.destructiveHint == false && it.openWorldHint == false }
                assertEquals(emptySet(), unasked.keys, "writes Codex would run without asking")
            },
        )
        rig.close()
    }

    private companion object {
        val read = ToolAnnotations(readOnlyHint = true)

        fun write(destructive: Boolean, idempotent: Boolean) =
            ToolAnnotations(readOnlyHint = false, destructiveHint = destructive, idempotentHint = idempotent)
    }
}
