package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * How to be a good whiteboard participant, as the bridge tells a Claude session: the server's
 * [INSTRUCTIONS], sent in its `initialize` result, the [PROMPT] prompt, which Claude Code offers
 * as `/mcp__bee__whiteboard`, and the [EXAMPLES_TOOL] tool, whose result is the full
 * [CHEAT_SHEET]. This file is the one source of all three texts.
 *
 * Claude Code cuts server instructions and tool descriptions at [CLAUDE_CODE_TEXT_LIMIT]
 * characters, so [INSTRUCTIONS] carries the rules and the [ESSENTIAL] examples only; the rest of
 * the cheat-sheet travels in a tool *result*, which is not cut.
 *
 * Every entry in [EXAMPLES] passes the bridge's structural validator (`SurfaceValidator`: known
 * types, a root, children that exist, Poll and Board bindings), and the texts show each one
 * exactly as it is written here; `WhiteboardGuideTest` opens every one through
 * `bee_surface_open`. That is not the renderer's schema: an entry can pass and still render a
 * placeholder.
 */
object WhiteboardGuide {
    /** The prompt's name: `/mcp__bee__whiteboard` in Claude Code. */
    const val PROMPT = "whiteboard"

    /** The prompt's one argument, optional: what to draw first. */
    const val TOPIC = "topic"

    /** The read-only tool whose result is the full [CHEAT_SHEET]. */
    const val EXAMPLES_TOOL = "bee_examples"

    /**
     * Claude Code's default `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`: it truncates a server's
     * instructions, and each tool's description, past this many characters.
     */
    const val CLAUDE_CODE_TEXT_LIMIT = 4096

    /**
     * One cheat-sheet entry: a surface's [components], [data] and [append] as JSON text, and what
     * it's for. [append] maps each pointer to the entries appended there, as `bee_surface_open` takes it.
     */
    data class Example(val name: String, val components: String, val data: String?, val append: String?, val note: String)

    private val SKETCH = Example(
        name = "Sketch: diagrams and mockups",
        components = """[{"id":"root","component":"Sketch","title":"Login flow","html":"<svg viewBox='0 0 220 60' width='100%' font-family='system-ui,sans-serif' font-size='14'>""" +
            """<rect x='5' y='15' width='80' height='30' rx='6' fill='#def'/><text x='45' y='35' text-anchor='middle'>Login</text>""" +
            """<path d='M85 30h45' stroke='#333'/><rect x='130' y='15' width='80' height='30' rx='6' fill='#dfd'/>""" +
            """<text x='170' y='35' text-anchor='middle'>Home</text></svg>""" +
            """<button data-choice='sso'>Use SSO</button> <button data-choice='pw'>Keep passwords</button>"}]""",
        data = null,
        append = null,
        note = "The tool for a diagram, flow or mockup: any HTML, CSS, inline SVG and script, sandboxed with no " +
            "network (data: images only). A click on a data-choice element asks the viewer \"Send <its text>?\"; " +
            "Send records \"choice\" with {choice, text}. Keep diagrams compact: nodes in one or two rows, a viewBox " +
            "just tall enough for them. Redraw by updating the whole html.",
    )

    private val FIELD = Example(
        name = "A field people type into",
        components = """[{"id":"root","component":"Column","children":["idea","send"]},""" +
            """{"id":"idea","component":"TextField","label":"Your idea","value":{"path":"/idea"}},""" +
            """{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"submit","context":{"idea":{"path":"/idea"}}}}},""" +
            """{"id":"send-label","component":"Text","text":"Send"}]""",
        data = """{"idea":""}""",
        append = null,
        note = "Typing reaches /idea live for everyone (bee_board shows it); Send's record carries it in its " +
            "context. Variant longText is a paragraph box. Only TextField syncs live: CheckBox, ChoicePicker, " +
            "Slider (give it a max) and DateTimeInput hold each viewer's own value, which reaches you only in " +
            "the record of a Button whose context reads its path.",
    )

    /** The entries [INSTRUCTIONS] shows: Sketch first, the demo's diagram tool, then the way to ask. */
    val ESSENTIAL: List<Example> = listOf(SKETCH, FIELD)

    /** The cheat-sheet, one minimal surface per kind of component the Notions catalog renders. */
    val EXAMPLES: List<Example> = ESSENTIAL + listOf(
        Example(
            name = "Text, with Markdown",
            components = """[{"id":"root","component":"Text","text":"**Goal:** one login for web and phone.\n- SSO first\n- passkeys later"}]""",
            data = null,
            append = null,
            note = "The default body variant renders Markdown; variant h1 to h5 or caption shows the text as it is.",
        ),
        Example(
            name = "Layout and a Button",
            components = """[{"id":"root","component":"Column","children":["q","yes"]},""" +
                """{"id":"q","component":"Text","variant":"h4","text":"Ship on Friday?"},""" +
                """{"id":"yes","component":"Button","child":"yes-label","action":{"event":{"name":"ship","context":{"day":"friday"}}}},""" +
                """{"id":"yes-label","component":"Text","text":"Ship it"}]""",
            data = null,
            append = null,
            note = "A click records name \"ship\" with that context. Row lays children across, Card wraps one child, " +
                "and Tabs, List and Divider work too: a container names its children by id, never inline.",
        ),
        Example(
            name = "Poll",
            components = """[{"id":"root","component":"Poll","question":{"path":"/question"},"options":{"path":"/options"},"answers":{"path":"/answers"}}]""",
            data = """{"answers":{}}""",
            append = """{"/question":["Which first?"],"/options":["SSO","Passkeys"]}""",
            note = "Question revisions and options go in append, never data, so a vote's basis names the options " +
                "its voter saw; the last question shows. Add an option with bee_surface_update append " +
                "{\"/options\": [\"Passwords\"]}. Each person's answer lands at /answers/<peer>, so one person " +
                "counts once, and records \"answer\" with context {option}.",
        ),
        Example(
            name = "Board: cards in columns",
            components = """[{"id":"root","component":"Board","columns":{"path":"/columns"},"cards":{"path":"/cards"}}]""",
            data = """{"columns":{"items":{"todo":{"title":"To do"},"done":{"title":"Done"}},"order":["todo","done"]},""" +
                """"cards":{"items":{"c1":{"label":"Draft the API","column":"todo",""" +
                """"href":"https://example.com/api-draft","detail":"Endpoints and errors first"}},"order":["c1"]}}""",
            append = null,
            note = "People drag cards between columns; each drop moves the card and records \"drop\" with " +
                "{card, column}. Add a card by writing /cards/items/<id> and a new /cards/order. A card's " +
                "optional href (http: or https: only) makes its label a link, and its optional detail is one " +
                "short line under the label: keep the label short and put the rest there.",
        ),
    )

    private const val SURFACES =
        "A surface is a flat list of components, one with id \"root\"; containers name their children by id. " +
            "A property binds to the surface's data with {\"path\": \"/…\"}; bee_surface_update writes data by " +
            "JSON Pointer, as {\"/idea\": \"new text\"}, and upserts components by id. Each entry opens as it " +
            "stands with bee_surface_open."

    /** The server's `instructions`: the rules and the [ESSENTIAL] examples, within [CLAUDE_CODE_TEXT_LIMIT]. */
    val INSTRUCTIONS: String = """
        |bee is a shared whiteboard: the people in the room and their agents' sessions see the same board, live, in their browsers. It outlives every session, and everyone attached sees everything on it.
        |
        |How to take part
        |- Look first: bee_board shows the open surfaces and who is attached. Build on what is there.
        |- bee_presence with a short status ("drawing the login flow"); no status when you stop.
        |- One small surface per topic, with a short surfaceId ("login-flow") and a title. As the topic moves, bee_surface_update it rather than opening another. A surfaceId is used once, even after it closes.
        |- Change a surface someone else opened only when asked. Never bee_board_clear a board others use. bee_surface_close your own when settled; anyone can reopen it from the history strip.
        |- Before you work a Board card, bee_claim card:<surfaceId>/<cardId>; bee_release it, with the token bee_claim returned, if you stop. A lost claim names its holder: leave that work to them.
        |- People at the browser can't type into your terminal. When you ask them something, give them a way to answer on the board: a Button per option, or a TextField and a Send Button.
        |- After drawing anything interactive, loop bee_wait_action with no surfaceId and timeoutSeconds 20: one wait covers all your own surfaces, including ones you open later. Pass back the cursor it returned each call; timedOut only means call again. A message typed in your terminal waits until the wait returns, hence the short timeout. Each record names its surfaceId and arrives claimed for you with a token: act on it, then bee_action_done with its recordId and token. Other sessions' surfaces stay out of your wait; pass a surfaceId only to handle one of theirs you were asked to.
        |- bee_list_actions is a glance only: its records carry no token and are not claimed. Never act on a listed record; handle records only from bee_wait_action.
        |- A refused write names the bad id or type and changes nothing: fix it and resend.
        |
        |Surfaces
        |$SURFACES
        |${ESSENTIAL.joinToString("\n") { it.render() }}
        |Call $EXAMPLES_TOOL for the rest: Text with Markdown, layout and a Button, Poll, and Board.
    """.trimMargin()

    /** The [EXAMPLES_TOOL] tool's result: every entry in [EXAMPLES]. */
    val CHEAT_SHEET: String = "$SURFACES\n${EXAMPLES.joinToString("\n") { it.render() }}"

    /** The prompt's text: join as a whiteboarding partner, start on [topic] (or ask for one), keep the board current, loop. */
    fun prompt(topic: String?): String {
        val start = topic?.takeIf { it.isNotBlank() }
            ?.let {
                "The topic is what the person typed after the command: \"$it\", though that may be only its first word, " +
                    "so take the whole of what they typed. Start with one small surface that frames it, then build on it " +
                    "as people talk. If the topic is unclear, ask on the board."
            }
            ?: "Ask, on the board, what to work on: a surface with a field and a Send button (the field entry in the bee server's instructions). Wait for the answer, then draw it."
        return """
            |Join the bee board as a whiteboarding partner for the people in the room. The bee server's instructions say how to take part; follow them.
            |
            |1. Call bee_board to see what is up and who is here, set your bee_presence status, and call $EXAMPLES_TOOL for every component that renders.
            |2. $start
            |3. Keep the board current as the conversation moves: update your surfaces in place as things are decided, open a new surface for a new topic, close the ones that are settled. When you ask a question, put the way to answer on the board too: Buttons for the options, or a field and a Send button.
            |4. Put a Done button on your main surface. Then loop: bee_wait_action with no surfaceId (all your surfaces) and timeoutSeconds 20, passing back the cursor it returned, act on each record, bee_action_done it, and redraw. Re-read bee_board now and then for what others changed.
            |5. Stop when someone clicks Done or says, here or on the board, that they are done; when bee_board shows no person attached; or when no record has arrived for about 10 minutes. Leave a short summary surface of what was decided, clear your bee_presence status, and say here that you have stopped.
        """.trimMargin()
    }

    /** Adds the [PROMPT] prompt and the [EXAMPLES_TOOL] tool to [server], which must declare the prompts capability. */
    fun register(server: Server) {
        server.addPrompt(
            name = PROMPT,
            description = "Join the bee board as a whiteboarding partner: draw a topic, or ask on the board what to work on, " +
                "keep the board current, and react to clicks and typing until someone says done.",
            arguments = listOf(
                PromptArgument(
                    name = TOPIC,
                    description = "What to draw first; a longer topic can follow in the next message. Omit it to ask on the board.",
                    required = false,
                ),
            ),
        ) { request ->
            GetPromptResult(
                description = "Whiteboard with the people in the room",
                messages = listOf(PromptMessage(Role.User, TextContent(prompt(request.arguments?.get(TOPIC))))),
            )
        }
        server.addTool(
            name = EXAMPLES_TOOL,
            description = "Reads the cheat-sheet: one minimal surface for each kind of component the board renders, " +
                "each ready for bee_surface_open as it stands, with what it records when people use it.",
            inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
        ) { _ -> CallToolResult(content = listOf(TextContent(CHEAT_SHEET))) }
    }

    private fun Example.render(): String {
        val dataPart = data?.let { "\n  data: $it" }.orEmpty()
        val appendPart = append?.let { "\n  append: $it" }.orEmpty()
        return "- $name. $note\n  components: $components$dataPart$appendPart"
    }
}
