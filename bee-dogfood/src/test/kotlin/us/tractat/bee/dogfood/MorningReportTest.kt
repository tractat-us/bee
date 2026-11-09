package us.tractat.bee.dogfood

import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningReportTest {
    @Test
    fun aReportShowsItsTitleTldrAndSectionHeadingsOnly() {
        val digest = MorningReport.digest(
            """
            # bee: overnight

            Started 21:20 ET.

            ## TL;DR
            - Three PRs merged.
            - One waits on Iain.

            ## In flight
            - #159 under review.

            ## Log
            - 21:35 a long line nobody reads in the morning.
            """.trimIndent(),
            "morning-report.md",
        )
        assertEquals(
            "**bee: overnight** · `morning-report.md`\n\n- Three PRs merged.\n- One waits on Iain.\n\nSections: In flight · Log",
            digest,
        )
    }

    @Test
    fun aReportWithoutATldrShowsItsFirstParagraph() {
        val digest = MorningReport.digest("# Night\n\nStarted late.\nQuota fine.\n\n## Log\n- detail", null)
        assertEquals("**Night**\n\nStarted late.\nQuota fine.\n\nSections: Log", digest)
    }

    @Test
    fun theNewestReportIsPickedArchivesLeftOutAndNoneMeansAPlaceholder() {
        val dir = Files.createTempDirectory("bee-dogfood-reports-")
        try {
            val empty = MorningReport.newest(dir)
            val old = dir.resolve("morning-report.md").apply { writeText("# old") }
            val new = dir.resolve("morning-report-hosted.md").apply { writeText("# new") }
            val archive = dir.resolve("morning-report-2026-11-08.archive.md").apply { writeText("# archive") }
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))
            Files.setLastModifiedTime(new, FileTime.fromMillis(2_000))
            Files.setLastModifiedTime(archive, FileTime.fromMillis(3_000))
            val placeholder = MorningReport.surface(null, null).components.first { it.string("id") == "report" }.string("text")
            assertAll(
                { assertNull(empty, "no report in an empty dir") },
                { assertEquals(new, MorningReport.newest(dir), "the newest, archives left out") },
                { assertEquals(MorningReport.PLACEHOLDER, placeholder) },
                { assertTrue(MorningReport.surface("# x", "y").components.any { it.string("id") == SeedSurface.ROOT }) },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
