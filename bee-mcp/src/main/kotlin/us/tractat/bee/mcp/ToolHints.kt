package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * The MCP annotations every bee tool declares, which a client reads to decide what to ask before
 * a call. Codex, in its default `auto` mode, runs a [READ] without asking and asks before a write.
 *
 * A write leaves `openWorldHint` unset, which MCP reads as true: what it puts on the board reaches
 * everyone attached. Don't set it false: Codex's `auto` mode runs a write that is neither
 * destructive nor open-world without asking.
 */
internal object ToolHints {
    /** Changes nothing on the board or in its claims. */
    val READ = ToolAnnotations(readOnlyHint = true)

    /**
     * Changes the board or its claims. [destructive]: it can overwrite what a surface shows, or
     * take a surface off the board. [idempotent]: calling it again with the same arguments changes
     * nothing more.
     */
    fun write(destructive: Boolean, idempotent: Boolean) =
        ToolAnnotations(readOnlyHint = false, destructiveHint = destructive, idempotentHint = idempotent)
}
