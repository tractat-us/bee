package us.tractat.bee.companion

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenListingTest {
    /**
     * A screen deleted between the listing and the stat is skipped, not thrown on: the agent may
     * replace a screen at any moment. The listing is handed in, so the vanished file is forced.
     */
    @Test
    fun screenThatVanishedSinceTheListingIsSkipped() {
        val dir = Files.createTempDirectory("w38-screens-")
        val kept = dir.resolve("kept.html").also {
            it.writeText("<p>kept</p>")
            Files.setLastModifiedTime(it, FileTime.fromMillis(1_000))
        }
        val gone = dir.resolve("gone.html")

        assertEquals(kept, CompanionAdapter.newestScreen(listOf(kept, gone))?.path)
    }

    /** Only regular, non-dot files are screens, newest by mtime. */
    @Test
    fun newestRegularFileIsTheScreen() {
        val dir = Files.createTempDirectory("w38-screens-")
        fun file(name: String, mtime: Long) = dir.resolve(name).also {
            it.writeText("<p>$name</p>")
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }
        val older = file("older.html", 1_000)
        val newer = file("newer.html", 2_000)
        val hidden = file(".hidden.html", 3_000)
        val folder = dir.resolve("folder.html").createDirectory().also { Files.setLastModifiedTime(it, FileTime.fromMillis(4_000)) }

        assertEquals(newer, CompanionAdapter.newestScreen(listOf(older, newer, hidden, folder))?.path)
    }
}
