package us.tractat.bee.mcp

import us.tractat.kuilt.test.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Claude Code plugin's short commands, `/bee:draw` and `/bee:checkin`, are skills whose steps
 * are the server's own prompts with Claude Code's `$ARGUMENTS` as the topic. A skill can't fetch an
 * MCP prompt, so each carries a copy; this keeps the copy equal to [WhiteboardGuide.prompt] and
 * [CheckinGuide.prompt], so editing a prompt without its skill fails here.
 *
 * The skills live in `plugins/bee/skills/`, which `bee-mcp`'s test task declares as an input.
 */
class PluginCommandsTest {
    private val skills = File("../plugins/bee/skills")

    @Test
    fun theShortCommandsCarryTheServersPrompts() {
        assertAll(
            { assertCarries("draw", WhiteboardGuide.prompt(ARGUMENTS)) },
            { assertCarries("checkin", CheckinGuide.prompt(ARGUMENTS)) },
        )
    }

    private fun assertCarries(skill: String, prompt: String) {
        val file = skills.resolve("$skill/SKILL.md")
        val text = file.readText()
        assertTrue(
            prompt in text,
            "${file.path} no longer carries its prompt. Replace the steps under its front matter with:\n\n$prompt\n",
        )
    }

    private companion object {
        /** Claude Code puts what follows the command here. */
        const val ARGUMENTS = "\$ARGUMENTS"
    }
}
