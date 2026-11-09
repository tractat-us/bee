package us.tractat.bee.dogfood

import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The newest morning report, compact (bee#21): its title, its TL;DR and its section headings, never
 * the whole log. A report with no TL;DR section shows its first paragraph instead.
 */
object MorningReport {
    const val SURFACE_ID = "dogfood-morning"
    const val TITLE = "Morning report"
    const val TLDR_LINES = 6
    const val PLACEHOLDER = "No morning report yet. An overnight run writes `morning-report.md` at the repo root."

    /** The newest `morning-report*.md` in [dir] by modification time, leaving out `*.archive.md`. */
    fun newest(dir: Path): Path? = dir.listDirectoryEntries("morning-report*.md")
        .filter { it.isRegularFile() && !it.name.endsWith(".archive.md") }
        .maxByOrNull { it.getLastModifiedTime() }

    /** The surface for the report [markdown] read from [source], or the placeholder when there is none. */
    fun surface(markdown: String?, source: String?): SeedSurface {
        val text = if (markdown == null) PLACEHOLDER else digest(markdown, source)
        return SeedSurface(
            SURFACE_ID,
            TITLE,
            listOf(Components.card(SeedSurface.ROOT, "report"), Components.text("report", text)),
        )
    }

    /** [markdown]'s title, TL;DR (or first paragraph) and `##` headings, as compact Markdown. */
    fun digest(markdown: String, source: String?): String {
        val lines = markdown.lines()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        val gist = tldr(lines) ?: firstParagraph(lines)
        val sections = lines.filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
            .filterNot { TLDR.containsMatchIn(it) }
        return buildList {
            add(listOfNotNull(title?.let { "**$it**" }, source?.let { "`$it`" }).joinToString(" · "))
            if (gist.isNotEmpty()) add(gist.joinToString("\n"))
            if (sections.isNotEmpty()) add("Sections: ${sections.joinToString(" · ")}")
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** The lines under a heading that names a TL;DR, up to the next heading, or null if none does. */
    private fun tldr(lines: List<String>): List<String>? {
        val at = lines.indexOfFirst { it.startsWith("#") && TLDR.containsMatchIn(it) }
        if (at < 0) return null
        return lines.drop(at + 1).takeWhile { !it.startsWith("#") }.filter { it.isNotBlank() }.take(TLDR_LINES)
    }

    private fun firstParagraph(lines: List<String>): List<String> = lines
        .dropWhile { it.isBlank() || it.startsWith("#") }
        .takeWhile { it.isNotBlank() && !it.startsWith("#") }
        .take(TLDR_LINES)

    private val TLDR = Regex("""(?i)\btl;?\s?dr\b""")
}
