package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * The [PROMPT] prompt, which Claude Code offers as `/mcp__bee__checkin`: a session puts one status
 * card for itself on the board and keeps it current while it works, so one person running bee on
 * their own machine sees every session at a glance and answers what each needs from them.
 *
 * It is a prompt only. The rules for taking part stay in [WhiteboardGuide.INSTRUCTIONS], which the
 * prompt points to, so it adds nothing to the server instructions Claude Code cuts at
 * [WhiteboardGuide.CLAUDE_CODE_TEXT_LIMIT]. The card it shows, [CARD_COMPONENTS] with
 * [CARD_DATA], passes `bee_surface_open` as it stands; `CheckinGuideTest` opens it.
 */
object CheckinGuide {
    /** The prompt's name: `/mcp__bee__checkin` in Claude Code. */
    const val PROMPT = "checkin"

    /** The prompt's one argument, optional: the task the card is about. */
    const val FOCUS = "focus"

    /** The card's components: what the session is doing, its progress, the decision it needs, and Done and Pause. */
    const val CARD_COMPONENTS: String =
        """[{"id":"root","component":"Column","children":["now","progress","decision","stop"]},""" +
            """{"id":"now","component":"Text","text":{"@path":"/now"}},""" +
            """{"id":"progress","component":"Text","text":{"@path":"/progress"}},""" +
            """{"id":"decision","component":"Column","children":["ask","options","answer","send"]},""" +
            """{"id":"ask","component":"Text","variant":"h5","text":"Squash the fix into one commit?"},""" +
            """{"id":"options","component":"Row","children":["opt-1","opt-2"]},""" +
            """{"id":"opt-1","component":"Button","child":"opt-1-label","action":{"event":{"name":"decide","context":{"choice":"squash"}}}},""" +
            """{"id":"opt-1-label","component":"Text","text":"Squash"},""" +
            """{"id":"opt-2","component":"Button","child":"opt-2-label","action":{"event":{"name":"decide","context":{"choice":"keep"}}}},""" +
            """{"id":"opt-2-label","component":"Text","text":"Keep the commits"},""" +
            """{"id":"answer","component":"TextField","label":"Or say something else","value":{"@path":"/answer"}},""" +
            """{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"answer","context":{"text":{"@path":"/answer"}}}}},""" +
            """{"id":"send-label","component":"Text","text":"Send"},""" +
            """{"id":"stop","component":"Row","children":["done","pause"]},""" +
            """{"id":"done","component":"Button","child":"done-label","action":{"event":{"name":"done","context":{}}}},""" +
            """{"id":"done-label","component":"Text","text":"Done"},""" +
            """{"id":"pause","component":"Button","child":"pause-label","action":{"event":{"name":"pause","context":{}}}},""" +
            """{"id":"pause-label","component":"Text","text":"Pause"}]"""

    /** The card's data: the lines a checkpoint rewrites by pointer, and the field's text. */
    const val CARD_DATA: String =
        """{"now":"Fixing the flaky retry test","progress":"- done: found the race in RetryPolicy\n- next: fix it, run the suite","answer":""}"""

    /** The prompt's text: name the card after the repo, open it, keep it current at checkpoints, ask on it, stop on Done or Pause. */
    fun prompt(focus: String?): String {
        val task = focus?.takeIf { it.isNotBlank() }
            ?.let {
                "the task is what the person typed after the command: \"$it\", though that may be only its first word, " +
                    "so take the whole of what they typed."
            }
            ?: "the task is the one you are on in this conversation. If you have none yet, it is \"waiting for a task\": " +
            "ask on the card what to work on (an open question), wait for the answer as in step 5, then do it."
        return """
            |Check in on the bee board: one status card for this session, kept current while you work, so the person sees from one page what each of their sessions is doing and answers what you need from them. The bee server's instructions say how to take part; follow them.
            |
            |Everything on the card is visible to anyone attached, over plain HTTP. Write short summaries only: never a secret, token, key, password or environment value, and no file contents beyond a one-line summary. Name a file; don't paste it.
            |
            |1. Name the card. The repo is the directory that holds this repository's shared .git: run `git rev-parse --path-format=absolute --git-common-dir` and take the name of the directory above `.git`, so a worktree uses its repository's name; outside git, use the working directory's name. The surfaceId is that name in lower case, each character other than a-z, 0-9 and - turned into -, as "fireworks-compose". Call bee_board: if it lists that id, open or closed, take the first of <id>-2, <id>-3, … it doesn't list. Keep that id for the whole session. The title is "<repo> — <task>", the task in one line: $task
            |2. Open the card with ${BeeTools.OPEN}, that surfaceId and title, these components and data, your own words in place of the example's:
            |  components: $CARD_COMPONENTS
            |  data: $CARD_DATA
            |  now is what you are doing, in one line. progress is a short list: "- done: …" lines, then "- next: …" lines. decision holds what you need from the person: ask says it in one line, and options holds a Button per option, each recording "decide" with {choice}. For an open question keep answer and send, which record "answer" with {text}; when the options cover it, drop them from decision's children. With nothing to ask, decision's children are []. Done and Pause always stay.
            |3. Set ${BeeTools.PRESENCE} to "<repo>: <now>", then go on with your real work in the repo.
            |4. At each natural checkpoint (a step finished, a test run, before a commit), first glance for a click: ${WaitTool.WAIT} with your surfaceId and timeoutSeconds 1, passing back the cursor it returned last time, and handle any record as in steps 5 and 6. Glance before you write: the wait claims the click, and a claimed click keeps its button reading sent through your writes. Then ${BeeTools.UPDATE} the same card, never a new surface: data {"/now": …, "/progress": …}, and components only when the decision changes. Update ${BeeTools.PRESENCE} to match.
            |5. When you need a decision, ask it on the card: upsert ask and options, and set decision's children. Then loop ${WaitTool.WAIT} with your surfaceId and timeoutSeconds 20, passing back the cursor each call; timedOut only means call again. Act on the record: "decide" carries {choice}, "answer" carries {text}. ${BeeTools.DONE} it with its recordId and token, set decision's children to [], note the outcome in progress, and carry on. Don't hold a wait for a decision you don't need yet: ask, keep working, and pick the answer up at a checkpoint.
            |6. A "done" or "pause" record ends the check-ins: ${BeeTools.DONE} it, write one line into now saying what you finished and what is left, set decision's children to [], clear your ${BeeTools.PRESENCE} status, and say here that you have stopped checking in. Leave the card open and stop writing to it.
        """.trimMargin()
    }

    /** Adds the [PROMPT] prompt to [server], which must declare the prompts capability. */
    fun register(server: Server) {
        server.addPrompt(
            name = PROMPT,
            description = "Check in on the bee board: one status card for this session, named after its repo, kept current " +
                "while it works, asking the person for decisions with buttons, until they click Done or Pause.",
            arguments = listOf(
                PromptArgument(
                    name = FOCUS,
                    description = "The task the card is about, in a few words. Omit it to use the task already under way.",
                    required = false,
                ),
            ),
        ) { request ->
            GetPromptResult(
                description = "Keep a status card on the bee board",
                messages = listOf(PromptMessage(Role.User, TextContent(prompt(request.arguments?.get(FOCUS))))),
            )
        }
    }
}
