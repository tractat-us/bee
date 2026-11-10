package us.tractat.bee.mcp

/**
 * The agent tool an MCP client over HTTP is, by the name it gives in `initialize`'s `clientInfo`:
 * the [suffix] of its session's name ([HttpBridges.sessionNameFrom]), so a person's Claude and
 * their Codex show as two sessions, each for what it is (bee#224).
 */
enum class AgentTool(val suffix: String) {
    /** Claude Code, whose `clientInfo` is `claude-code`: `-claude`, as every session was named before. */
    Claude("claude"),

    /** Codex, whose `clientInfo` is `codex-mcp-client`. */
    Codex("codex"),

    /** Any other client, or one that gave no name: a neutral suffix that claims no tool. */
    Other("agent"),
    ;

    companion object {
        /** The tool a client that calls itself [clientName] is: matched loosely, as names carry versions and dashes. */
        fun of(clientName: String?): AgentTool {
            val name = clientName.orEmpty().lowercase()
            return when {
                "claude" in name -> Claude
                "codex" in name -> Codex
                else -> Other
            }
        }
    }
}
