package us.tractat.bee.mcp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeAncestryTest {
    @Test
    fun findsTheNearestClaudeAncestor() {
        val ancestors = listOf(
            Ancestor(101, "/bin/sh"),
            Ancestor(102, "claude"),
            Ancestor(103, "-zsh"),
            Ancestor(104, "/opt/claude/bin/claude"),
        )
        assertEquals(102L, ClaudeAncestry.claudePid { ancestors })
    }

    @Test
    fun noClaudeAncestorIsNull() {
        val ancestors = listOf(
            Ancestor(201, "/bin/sh"),
            // A name that only contains "claude" is not claude.
            Ancestor(202, "/usr/local/bin/claude-code-helper"),
            Ancestor(203, "node"),
        )
        assertNull(ClaudeAncestry.claudePid { ancestors })
    }

    @Test
    fun walksPsOutputNearestFirstAndKeepsSpacesInNames() {
        val ps = """
              1     0 /sbin/launchd
            400     1 /Applications/My Tools/claude
            500   400 /bin/zsh
            600   500 /bin/sh
            700   600 java
            999   998 orphan
        """.trimIndent()

        val table = ClaudeAncestry.parsePs(ps)
        val ancestors = ClaudeAncestry.ancestorsOf(700, table)

        assertAll(
            { assertEquals(listOf(600L, 500L, 400L), ancestors.map { it.pid }) },
            { assertEquals("/Applications/My Tools/claude", ancestors.last().comm) },
            { assertEquals(400L, ClaudeAncestry.claudePid { ancestors }) },
            { assertEquals(emptyList(), ClaudeAncestry.ancestorsOf(999, table)) },
        )
    }
}
