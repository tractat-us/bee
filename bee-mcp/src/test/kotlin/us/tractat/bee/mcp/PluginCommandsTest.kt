package us.tractat.bee.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.test.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bee plugin, `plugins/bee/`, is one set of skills that Claude Code and Codex both load.
 *
 * - **The short commands carry the server's prompts.** `/bee:draw` and `/bee:checkin` are skills
 *   whose steps are the server's own prompts with Claude Code's `$ARGUMENTS` as the topic. A skill
 *   can't fetch an MCP prompt, and Codex offers no MCP prompts at all, so each carries a copy; this
 *   keeps the copy equal to [WhiteboardGuide.prompt] and [CheckinGuide.prompt].
 * - **Codex reads the same plugin through a manifest of its own.** Codex takes
 *   `.codex-plugin/plugin.json` over `.claude-plugin/plugin.json`. Without it, Codex would load the
 *   Claude manifest's `.mcp.json` and register a `bee` server at the unfilled URL
 *   `${user_config.board_url}`, since Codex fills in no plugin settings. So the Codex manifest
 *   names no server, and must stay the same plugin at the same version.
 * - **Every skill loads in Codex**, which skips a `SKILL.md` without a `name` of at most 64
 *   characters and a `description` of at most 1,024.
 *
 * `bee-mcp`'s test task declares `plugins/bee/` as an input.
 */
class PluginCommandsTest {
    private val plugin = File("../plugins/bee")
    private val skills = plugin.resolve("skills")

    @Test
    fun theShortCommandsCarryTheServersPrompts() {
        assertAll(
            { assertCarries("draw", WhiteboardGuide.prompt(ARGUMENTS)) },
            { assertCarries("checkin", CheckinGuide.prompt(ARGUMENTS)) },
        )
    }

    @Test
    fun codexLoadsTheSamePluginWithNoServerOfItsOwn() {
        val claude = manifest(".claude-plugin/plugin.json")
        val codex = manifest(".codex-plugin/plugin.json")
        assertAll(
            { assertEquals(claude["name"], codex["name"], "The two manifests name different plugins.") },
            { assertEquals(claude["version"], codex["version"], "Bump both manifests' version together, or one tool's installed copy won't update.") },
            { assertEquals(JsonObject(emptyMap()), codex["mcpServers"], "Codex would register the Claude Code server, at an unfilled URL.") },
            { assertEquals("./skills/", codex["skills"]?.toString()?.trim('"'), "Codex should load the same skills as Claude Code.") },
        )
    }

    @Test
    fun everySkillLoadsInCodex() {
        val files = skills.listFiles().orEmpty().map { it.resolve("SKILL.md") }.filter(File::isFile)
        assertTrue(files.size >= 3, "Found ${files.size} skills under ${skills.path}.")
        assertAll(*files.map { file -> { assertLoadsInCodex(file) } }.toTypedArray())
    }

    private fun assertCarries(skill: String, prompt: String) {
        val file = skills.resolve("$skill/SKILL.md")
        val text = file.readText()
        assertTrue(
            prompt in text,
            "${file.path} no longer carries its prompt. Replace the steps under its front matter with:\n\n$prompt\n",
        )
    }

    private fun assertLoadsInCodex(file: File) {
        val frontMatter = file.readText().substringAfter("---\n").substringBefore("\n---")
        val fields = frontMatter.lines().associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val name = assertNotNull(fields["name"], "${file.path} has no name.")
        val description = assertNotNull(fields["description"], "${file.path} has no description.")
        assertAll(
            { assertTrue(name.length in 1..CODEX_NAME_LIMIT, "${file.path}'s name is ${name.length} characters; Codex allows $CODEX_NAME_LIMIT.") },
            {
                assertTrue(
                    description.length in 1..CODEX_DESCRIPTION_LIMIT,
                    "${file.path}'s description is ${description.length} characters; Codex allows $CODEX_DESCRIPTION_LIMIT.",
                )
            },
        )
    }

    private fun manifest(path: String): JsonObject = Json.parseToJsonElement(plugin.resolve(path).readText()).jsonObject

    private companion object {
        /** Claude Code puts what follows the command here. */
        const val ARGUMENTS = "\$ARGUMENTS"

        /** Codex's limits on a skill's front matter (`codex-rs/ext/skills/src/loader/mod.rs`). */
        const val CODEX_NAME_LIMIT = 64
        const val CODEX_DESCRIPTION_LIMIT = 1024
    }
}
