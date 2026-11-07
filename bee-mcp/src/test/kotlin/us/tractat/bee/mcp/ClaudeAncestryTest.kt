package us.tractat.bee.mcp

import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeAncestryTest {
    @Test
    fun findsTheNearestClaudeAncestor() {
        val ancestors = listOf(
            FakeProcess(101, command = "/bin/sh"),
            FakeProcess(102, command = "/Users/someone/.local/bin/claude"),
            FakeProcess(103, command = "/bin/zsh"),
            FakeProcess(104, command = "/opt/claude/bin/claude"),
        )
        assertEquals(102L, ClaudeAncestry.claudePid { ancestors })
    }

    @Test
    fun noClaudeAncestorIsNull() {
        val ancestors = listOf(
            FakeProcess(201, command = "/bin/sh"),
            // A name that only contains "claude" is not claude.
            FakeProcess(202, command = "/usr/local/bin/claude-code-helper"),
            FakeProcess(203, command = null, commandLine = null),
        )
        assertNull(ClaudeAncestry.claudePid { ancestors })
    }

    @Test
    fun theCommandLineStandsInWhenTheCommandIsHidden() {
        val ancestors = listOf(FakeProcess(301, command = null, commandLine = "claude --resume"))
        assertEquals(301L, ClaudeAncestry.claudePid { ancestors })
    }
}

/** A process with only a pid and the executable the OS reports. */
private class FakeProcess(
    private val pid: Long,
    private val command: String?,
    private val commandLine: String? = command,
) : ProcessHandle {
    override fun pid(): Long = pid
    override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
        override fun command(): Optional<String> = Optional.ofNullable(command)
        override fun commandLine(): Optional<String> = Optional.ofNullable(commandLine)
        override fun arguments(): Optional<Array<String>> = Optional.empty()
        override fun startInstant(): Optional<Instant> = Optional.empty()
        override fun totalCpuDuration(): Optional<Duration> = Optional.empty()
        override fun user(): Optional<String> = Optional.empty()
    }

    override fun parent(): Optional<ProcessHandle> = unsupported()
    override fun children(): Stream<ProcessHandle> = unsupported()
    override fun descendants(): Stream<ProcessHandle> = unsupported()
    override fun onExit(): CompletableFuture<ProcessHandle> = unsupported()
    override fun supportsNormalTermination(): Boolean = unsupported()
    override fun destroy(): Boolean = unsupported()
    override fun destroyForcibly(): Boolean = unsupported()
    override fun isAlive(): Boolean = unsupported()
    override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())

    private fun unsupported(): Nothing = throw UnsupportedOperationException("FakeProcess $pid")
}
