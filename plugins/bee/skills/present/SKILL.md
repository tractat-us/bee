---
name: present
description: Use in a design conversation while a bee board is attached, when a question is clearer seen than read or the room should choose. Fires on "show me", "what should this look like", "mock it up", "compare these layouts", "draw the architecture", "walk us through the design", "visual companion", "let's see options", "survey the room", "take a vote", "which do you prefer", and a brainstorming question that is visual. Shows one question at a time as mockups or a poll, then reads the answers. Open whiteboarding goes to draw.
argument-hint: "[question]"
---

# present: the board as a design companion

In a design conversation, the board is where the people in the room see what you mean and answer
it. It takes the place of the brainstorming skill's visual companion, the local server that
serves HTML and records clicks. When a bee board is attached, use the board and start no server.
The board is already open to the room, so there's nothing to offer first.

You lead the conversation from the terminal, one question at a time. The board shows only the
question in front of the room. That differs from `draw`, where the room whiteboards freely until
someone clicks Done. If the person says "whiteboard this", that's `draw`.

The tools carry the plugin's prefix: `bee_board` here means `mcp__plugin_bee_bee__bee_board`. The
`bee` skill's rules hold here too.

## 1. Board or terminal: decide for each question

The test: **would the room understand this better by seeing it than by reading it?**

- **On the board**: mockups and layouts, architecture and data-flow diagrams, two or three designs
  side by side, look and feel (spacing, hierarchy, color), and anything spatial: a flow, a state
  machine, how parts relate.
- **In the terminal**: requirements and scope, choices between approaches described in words,
  tradeoff lists, technical decisions, and clarifying questions.

A question *about* a UI isn't always a visual question. "What kind of wizard do you want?" is
conceptual: ask it in the terminal. "Which of these wizard layouts feels right?" is visual: put it
on the board. A team choice that words settle, such as "Which of the three should we build first?",
can still go on the board as a survey (step 4), so everyone in the room has a say.

## 2. One question, one surface

- Before the first one: call `bee_board` to see what's up and who is here, set `bee_presence`
  ("presenting the signup design"), and call `bee_examples` if you haven't drawn a `Sketch` yet.
- Give the topic one surface, with a short surfaceId ("signup-design") and the topic as its title.
  The question goes on the surface as a heading.
- When the next question is visual too, `bee_surface_update` the same surface: change the
  heading, and upsert the components the new options need. Never open a second surface for the next
  question. A board of stale choices is clutter.
- When the conversation goes back to words, `bee_surface_close` the surface, so nobody answers a
  question you have moved past. A surfaceId serves once, so the next visual question opens a fresh
  one ("signup-design-2").
- Ask each question in both places. Say it in the terminal, with one line on what the board shows
  ("The board shows two layouts, A and B"), and put it on the board as the heading.

## 3. Showing a design

Draw each option as its own compact `Sketch`, labelled A, B, C, with a Button under each to choose
it. Two to four options at most. The board doesn't show a Sketch's `title`, so put the label in its
`html` too, as a caption ("A: one column"). Keep diagrams sans-serif (`font-family='system-ui,sans-serif'`)
and compact: nodes in one or two rows, and a viewBox only as tall as they need. Make the mockup as
detailed as the question, a wireframe for layout, polish only for a polish question.

```json
[{"id":"root","component":"Column","children":["ask","options","other","send"]},
 {"id":"ask","component":"Text","variant":"h4","text":"Which layout reads better?"},
 {"id":"options","component":"Row","children":["a","b"]},
 {"id":"a","component":"Column","children":["a-sketch","a-choose"]},
 {"id":"a-sketch","component":"Sketch","title":"A: one column","html":"<svg …>"},
 {"id":"a-choose","component":"Button","child":"a-label","action":{"event":{"name":"choose","context":{"option":"a"}}}},
 {"id":"a-label","component":"Text","text":"Choose A"},
 {"id":"b","component":"Column","children":["b-sketch","b-choose"]},
 {"id":"b-sketch","component":"Sketch","title":"B: sidebar","html":"<svg …>"},
 {"id":"b-choose","component":"Button","child":"b-label","action":{"event":{"name":"choose","context":{"option":"b"}}}},
 {"id":"b-label","component":"Text","text":"Choose B"},
 {"id":"other","component":"TextField","label":"Or say something else","value":{"path":"/other"}},
 {"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"comment","context":{"text":{"path":"/other"}}}}},
 {"id":"send-label","component":"Text","text":"Send"}]
```

Open it with data `{"other":""}`. A `Row` of options suits wide mockups poorly. Stack them in the
root `Column`, one per card, when each needs the full width. A walk through one design is one
`Sketch` with no choose buttons. Update its `html` as you talk through each part.

## 4. Surveying the room

When the room should choose together, open a `Poll` on its own surface ("signup-vote"). A Poll's
options are append-only, so it can't turn into the next question.

- Say how long it stays open, in the question and in the terminal: "until everyone here has
  voted", or "until 3:40".
- Each vote reaches you as an "answer" record with `{option}`. A person can change their vote, and
  `/answers/<peer>` in `bee_board` holds each person's latest one. Count from there, not from the
  records.
- "Everyone here" means the `human:` identities in `bee_board`'s `attached`. Agents don't vote.
- When it closes, settle it: append the outcome as a new question revision,
  `{"/question": ["Settled: B, 3 of 4"]}`. Then sum it up in the terminal: the winner, the count,
  and any split worth raising. Close the surface when the conversation moves on.

## 5. Reading the answers

Wait as the `bee` skill says. Loop `bee_wait_action` with timeoutSeconds 20 and the cursor it
returned. `timedOut` only means call again. Act on each record once, then `bee_action_done` it.

- "choose" carries `{option}`; "comment" carries `{text}`; a Poll's "answer" carries `{option}`.
- A `data-choice` click inside a Sketch is only a proposal. It reaches you as "choice" with
  `{choice, text}` once the viewer clicks Send under the frame. Until then, it isn't an answer.
- What the person types in the terminal counts as much as a click. Merge the two before you move on.
- Show the result on the board: mark the chosen option in the heading ("Chose B"), then move to the
  next question or close the surface.

## 6. No board attached

If the bee tools aren't there, or `bee_board` fails, this skill doesn't apply. Hand back to the
brainstorming skill's own companion, if the person uses it, or ask in the terminal. Never start a
server of your own. If the board is up but no person is attached, ask the person in the terminal to
open the board. Don't print its address, which can carry the join key.

## Never on the board

Everyone attached sees all of it, over plain HTTP. Mockups use placeholder data, never a secret,
token, key, password, environment value or the board URL. Name a file; don't paste it.

When the design is settled, close your surfaces and clear your `bee_presence` status.
