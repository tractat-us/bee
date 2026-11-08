package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * How to be a good whiteboard participant, as the bridge tells a Claude session: the server's
 * [INSTRUCTIONS], sent in its `initialize` result, and the [PROMPT] prompt, which Claude Code
 * offers as `/mcp__bee__whiteboard`. This file is the one source of both texts.
 *
 * Every cheat-sheet entry in [EXAMPLES] is a surface the Notions validator passes, and the
 * instructions show each one exactly as it is written here; `WhiteboardGuideTest` opens every
 * one through `bee_surface_open`.
 */
object WhiteboardGuide {
    /** The prompt's name: `/mcp__bee__whiteboard` in Claude Code. */
    const val PROMPT = "whiteboard"

    /** The prompt's one argument, optional: what to draw first. */
    const val TOPIC = "topic"

    /** One cheat-sheet entry: a surface's [components] and [data] as JSON text, and what it's for. */
    data class Example(val name: String, val components: String, val data: String?, val note: String)

    /** The cheat-sheet, one minimal surface per kind of component the Notions catalog renders. */
    val EXAMPLES: List<Example> = listOf(
        Example(
            name = "Text, with Markdown",
            components = """[{"id":"root","component":"Text","text":"**Goal:** one login for web and phone.\n- SSO first\n- passkeys later"}]""",
            data = null,
            note = "The default body variant renders Markdown; variant h1 to h5 or caption shows the text as it is.",
        ),
        Example(
            name = "Layout and a Button",
            components = """[{"id":"root","component":"Column","children":["q","yes"]},""" +
                """{"id":"q","component":"Text","variant":"h4","text":"Ship on Friday?"},""" +
                """{"id":"yes","component":"Button","child":"yes-label","action":{"event":{"name":"ship","context":{"day":"friday"}}}},""" +
                """{"id":"yes-label","component":"Text","text":"Ship it"}]""",
            data = null,
            note = "A click records name \"ship\" with that context. Row lays children across, Card wraps one child, " +
                "and Tabs, List and Divider work too: a container names its children by id, never inline.",
        ),
        Example(
            name = "A field people type into",
            components = """[{"id":"root","component":"Column","children":["idea","send"]},""" +
                """{"id":"idea","component":"TextField","label":"Your idea","value":{"path":"/idea"}},""" +
                """{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"submit","context":{"idea":{"path":"/idea"}}}}},""" +
                """{"id":"send-label","component":"Text","text":"Send"}]""",
            data = """{"idea":""}""",
            note = "What a person types reaches /idea live, for everyone (bee_board shows it); the Button's context path " +
                "carries the typed text in its record. Variant longText gives a paragraph box. CheckBox (a boolean), " +
                "ChoicePicker, Slider and DateTimeInput bind their value the same way.",
        ),
        Example(
            name = "Poll",
            components = """[{"id":"root","component":"Poll","question":{"path":"/question"},"options":{"path":"/options"},"answers":{"path":"/answers"}}]""",
            data = """{"question":["Which first?"],"options":["SSO","Passkeys"],"answers":{}}""",
            note = "The last entry of question shows. Each person's answer lands at /answers/<peer>, so one person " +
                "counts once, and records \"answer\" with context {option}.",
        ),
        Example(
            name = "Board: cards in columns",
            components = """[{"id":"root","component":"Board","columns":{"path":"/columns"},"cards":{"path":"/cards"}}]""",
            data = """{"columns":{"items":{"todo":{"title":"To do"},"done":{"title":"Done"}},"order":["todo","done"]},""" +
                """"cards":{"items":{"c1":{"label":"Draft the API","column":"todo"}},"order":["c1"]}}""",
            note = "People drag cards between columns; each drop moves the card and records \"drop\" with " +
                "{card, column}. Add a card by writing /cards/items/<id> and a new /cards/order.",
        ),
        Example(
            name = "Sketch: diagrams and mockups",
            components = """[{"id":"root","component":"Sketch","title":"Login flow","html":"<svg viewBox='0 0 220 60' width='100%'>""" +
                """<rect x='5' y='15' width='80' height='30' rx='6' fill='#def'/><text x='45' y='35' text-anchor='middle'>Login</text>""" +
                """<path d='M85 30h45' stroke='#333'/><rect x='130' y='15' width='80' height='30' rx='6' fill='#dfd'/>""" +
                """<text x='170' y='35' text-anchor='middle'>Home</text></svg>""" +
                """<button data-choice='sso'>Use SSO</button> <button data-choice='pw'>Keep passwords</button>"}]""",
            data = null,
            note = "The best tool for a diagram, a flow, a mockup or anything laid out by hand: any HTML, CSS, inline " +
                "SVG and inline script, in a sandboxed frame with no network (no fetch, no outside images or fonts; " +
                "data: images only). A click on an element with data-choice shows the viewer \"Send <its text>?\"; " +
                "their Send records \"choice\" with {choice, text}. Redraw it by updating the whole html.",
        ),
    )

    /** The server's `instructions`: what the board is, how to take part, and the cheat-sheet. */
    val INSTRUCTIONS: String = """
        |bee is a shared whiteboard. The people in the room and their agents' sessions all see the same board, live, in their browsers. It outlives every session: what you draw stays after you leave. Everything you put on it is visible to everyone attached.
        |
        |How to take part
        |- Look before you draw. Call bee_board for the open surfaces, who is attached and what each is working on, and build on what is there rather than starting over.
        |- Say what you are doing: bee_presence with a short status ("drawing the login flow"); call it with no status when you stop.
        |- One surface per topic. Give it a short, readable surfaceId ("login-flow") and a title. As the topic moves, bee_surface_update that surface rather than opening another. A surfaceId is used once, even after its surface closes.
        |- Keep surfaces small: a heading, a few lines, one diagram or one control. Several small surfaces read better than one long one.
        |- Respect other people's work. Change a surface someone else opened only when asked. Never bee_board_clear a board others are using. Close your own surfaces when their topic is settled: bee_surface_close keeps them in the page's history strip, where anyone can reopen them.
        |- Claim shared work before you take it. Claims are on Board cards (card:<surfaceId>/<cardId>) and action records (record:<author>:<seq>): bee_claim one before you act on it, so two sessions don't both do it, and bee_release it if you stop. A lost claim names its holder: leave that work to them.
        |- Close the loop on anything interactive. After drawing a Button, field, Poll, Board or a Sketch with choices, loop bee_wait_action on that surface, passing back the cursor each call; timedOut only means call it again. Each record arrives already claimed for you, with a token: act on it (usually by updating the board), then bee_action_done with its recordId and token. Between waits, bee_list_actions shows what landed on your other surfaces.
        |- A refused write names the bad id or type and changes nothing: fix it and send it again.
        |
        |Surfaces
        |A surface is a flat list of components, one of them with id "root"; containers name their children by id. A property can bind to the surface's data with {"path": "/…"}; bee_surface_update writes data by JSON Pointer, as {"/idea": "new text"}, and upserts components by id. Each entry below opens as it stands with bee_surface_open.
        |${EXAMPLES.joinToString("\n") { it.render() }}
    """.trimMargin()

    /** The prompt's text: join as a whiteboarding partner, start on [topic] (or ask for one), keep the board current, loop. */
    fun prompt(topic: String?): String {
        val start = topic?.takeIf { it.isNotBlank() }
            ?.let { "Draw the topic: $it. Start with one small surface that frames it, then build on it as people talk." }
            ?: "Ask, on the board, what to work on: a surface with a field and a Send button (the field entry in the bee server's instructions). Wait for the answer, then draw it."
        return """
            |Join the bee board as a whiteboarding partner for the people in the room. The bee server's instructions say how to take part and which components render; follow them.
            |
            |1. Call bee_board to see what is up and who is here, and set your bee_presence status.
            |2. $start
            |3. Keep the board current as the conversation moves: update your surfaces in place as things are decided, open a new surface for a new topic, close the ones that are settled.
            |4. Put a Done button on your main surface. Then loop: bee_wait_action on your interactive surfaces, act on each record, bee_action_done it, and redraw. Re-read bee_board now and then for what others changed.
            |5. Stop when someone clicks Done or says, here or on the board, that they are done. Leave a short summary surface of what was decided, clear your bee_presence status, and say here that you have stopped.
        """.trimMargin()
    }

    /** Adds the [PROMPT] prompt to [server], which must declare the prompts capability. */
    fun register(server: Server) {
        server.addPrompt(
            name = PROMPT,
            description = "Join the bee board as a whiteboarding partner: draw a topic, or ask on the board what to work on, " +
                "keep the board current, and react to clicks and typing until someone says done.",
            arguments = listOf(PromptArgument(name = TOPIC, description = "What to draw first. Omit it to ask on the board.", required = false)),
        ) { request ->
            GetPromptResult(
                description = "Whiteboard with the people in the room",
                messages = listOf(PromptMessage(Role.User, TextContent(prompt(request.arguments?.get(TOPIC))))),
            )
        }
    }

    private fun Example.render(): String {
        val dataPart = data?.let { "\n  data: $it" }.orEmpty()
        return "- $name. $note\n  components: $components$dataPart"
    }
}
