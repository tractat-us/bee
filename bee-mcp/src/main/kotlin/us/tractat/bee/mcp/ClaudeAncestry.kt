package us.tractat.bee.mcp

import java.nio.file.Path
import kotlin.jvm.optionals.getOrNull

/**
 * Finds the Claude Code process the bridge belongs to (A8).
 *
 * The rule is the nearest ancestor whose executable is named `claude`. `bin/bee-session-start-hook`
 * applies the same rule from the hook's side, so both name the same `~/.bee/run/<claudePid>.json`.
 */
object ClaudeAncestry {
    private const val CLAUDE = "claude"

    /** The pid of the nearest `claude` in [ancestors] (nearest first), or `null` when there is none. */
    fun claudePid(ancestors: () -> List<ProcessHandle> = ::ancestorsOfThisProcess): Long? =
        ancestors().firstOrNull { executableName(it) == CLAUDE }?.pid()

    /**
     * The basename of a process's executable: the command when the OS reports it, else the first
     * word of its command line.
     */
    private fun executableName(process: ProcessHandle): String? {
        val info = process.info()
        val executable = info.command().getOrNull() ?: info.commandLine().getOrNull()?.substringBefore(' ')
        return executable?.let { Path.of(it).fileName?.toString() }
    }

    private fun ancestorsOfThisProcess(): List<ProcessHandle> =
        generateSequence(ProcessHandle.current().parent().getOrNull()) { it.parent().getOrNull() }.toList()
}
