---
name: notion
description: Use when the person asks the bee board for a kind of UI piece it may not have, before you answer that the board can't. Fires on "can the board show a timer", "I need a chart on the board", "put a QR code on bee", "can bee show a map", "is there a <component> on the board", "the board needs a <thing>". Builds the piece now as a Sketch, then offers to request it as a Notion.
---

# notion: a piece the board doesn't have yet

The board draws from bee's catalog of components, its **Notions**: the basic A2UI components plus
`Sketch`, `Poll` and `Board`. When the person asks for something else, such as a timer, a chart, a
QR code or a map, don't say no. Find it, or build it now as a Sketch, then offer to ask bee for a
real one.

The tools carry the plugin's prefix: `bee_examples` here means `mcp__plugin_bee_bee__bee_examples`.

## 1. Look first

Call `bee_examples`. It returns one working surface for each component the board renders. The
piece may already be there under another name, or a few Notions may make it: a list is a `Column`
of `Text`, a vote is a `Poll`, a kanban is a `Board`. Prefer that: a `Poll` or a `Board` keeps state that
every peer sees, and a Sketch doesn't.

## 2. Build it now as a Sketch

A `Sketch` is HTML that you write: CSS, inline SVG and script, run in a sandboxed frame on each
viewer's page. It's good enough for the meeting. Say what it is when you put it up, and know its
limits, because each one is a reason to ask for a real Notion:

- **No shared state.** Each viewer runs their own copy. A vote, a drag order or a counter changes
  on one screen only, and other peers never see it. A timer runs separately in each copy.
- **No network.** The frame may fetch nothing and load no script, image or font from a URL; images
  must be `data:` URLs. Whatever it needs travels inline in its `html`, so a QR code needs its
  encoder written into the page, and a map can't load tiles.
- **Choices are proposals.** A click on a `data-choice` element asks the viewer "Send <its
  text>?", and the choice reaches you only when they click the page's Send.
- **Opaque to other agents.** The board holds the sketch as one string of HTML. Another session
  reading `bee_board` sees markup, not the values in it.

If the piece needs none of these, the Sketch may be all it needs. Stop here.

## 3. Offer to request it as a Notion

When a limit bit, offer to ask bee for a real one. Look for an existing request first:

```sh
gh issue list --repo tractat-us/bee --search "<X> Notion in:title" --state all
```

- **One exists:** offer to add this use case to it as a comment.
- **None does:** draft an issue in the shape of
  [#130](https://github.com/tractat-us/bee/issues/130). Title it "A <X> Notion, so an agent can
  <what it lets an agent do>". In the body, give the use case, which limit of a Sketch fell short,
  and the Sketch as a prototype.

**The board may be private, and the issue is public.** Write the use case in generic terms: what
kind of component, and what an agent does with it. Never copy board content, people's names, or
anything from the person's work into the issue. Put the prototype's HTML in only if it carries
no board data; otherwise rebuild it with placeholder values, or leave it out.

Show the person the whole draft, title and body. File it, or post the comment, only when they say
yes to that draft. A yes to the Sketch is not a yes to the issue.

- If `gh auth status` succeeds, file it with `gh issue create --repo tractat-us/bee --title …
  --body-file …`, or comment with `gh issue comment`, and give the person the link.
- Otherwise, give them a link that opens the form already filled in:
  `https://github.com/tractat-us/bee/issues/new?title=<title>&body=<body>`, with both parts
  URL-encoded. If the body makes the link too long, leave the prototype out of it and hand the
  person the HTML to paste.

Then go back to the board. The Sketch stays up until the Notion ships.
