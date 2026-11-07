package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/** One process above the bridge, as `ps` names it: its pid and its `comm`. */
data class Ancestor(val pid: Long, val comm: String)

/**
 * Finds the Claude Code process the bridge belongs to (A8).
 *
 * The rule is the nearest ancestor whose `ps -o comm=` basename is `claude`. The bridge reads `ps`
 * itself, not `ProcessHandle.info()`, because `bin/bee-session-start-hook` can only read `ps`. Then
 * both sides apply literally one rule, and name the same `~/.bee/run/<claudePid>.json`. The two
 * sources disagree where it matters: on an npm install, the JDK reports the executable `node`.
 */
object ClaudeAncestry {
    private const val CLAUDE = "claude"
    private const val PS_TIMEOUT_SECONDS = 5L

    /** The pid of the nearest `claude` in [ancestors] (nearest first), or `null` when there is none. */
    fun claudePid(ancestors: () -> List<Ancestor> = ::ancestorsOfThisProcess): Long? =
        ancestors().firstOrNull { it.comm.substringAfterLast('/') == CLAUDE }?.pid

    /** Parses `ps -A -o pid=,ppid=,comm=` into pid → (ppid, comm). A `comm` may hold spaces. */
    internal fun parsePs(output: String): Map<Long, Pair<Long, String>> =
        output.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 3)
            val pid = fields.getOrNull(0)?.toLongOrNull()
            val ppid = fields.getOrNull(1)?.toLongOrNull()
            val comm = fields.getOrNull(2)
            if (pid == null || ppid == null || comm == null) null else pid to (ppid to comm)
        }.toMap()

    /** The ancestors of [pid], nearest first, walking `ppid` until pid 1 or a process `ps` doesn't list. */
    internal fun ancestorsOf(pid: Long, table: Map<Long, Pair<Long, String>>): List<Ancestor> {
        val seen = mutableSetOf(pid)
        return generateSequence(table[pid]?.first) { table[it]?.first }
            .takeWhile { it > 1 && seen.add(it) }
            .mapNotNull { ancestor -> table[ancestor]?.let { Ancestor(ancestor, it.second) } }
            .toList()
    }

    private fun ancestorsOfThisProcess(): List<Ancestor> {
        val ps = ProcessBuilder("ps", "-A", "-o", "pid=,ppid=,comm=").redirectErrorStream(false).start()
        val output = ps.inputStream.bufferedReader().readText()
        if (!ps.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || ps.exitValue() != 0) {
            logger.warn { "ps failed; the bridge can't find its claude process." }
            return emptyList()
        }
        return ancestorsOf(ProcessHandle.current().pid(), parsePs(output))
    }
}
