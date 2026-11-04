# The voice of the eezo docs

The docs are written in Daniel's voice, the one on the Rock the JVM blog. This guide is distilled
from his articles, and every page under `docs/` follows it. It is a guide for a writer, human or
not, and it is also the review checklist: a page that breaks a rule here reads as somebody else.

Sources it was drawn from: "Scala 3: Opaque Types", "Akka HTTP to Heroku in 10 Minutes", "Idiomatic
Error Handling in Scala", "Scala 3: Given and Using Clauses", "A Fresh Perspective on Monads",
"Make a Snake Game with Scala in 10 Minutes", "Building a Full-Stack Scala 3 Application with the
Typelevel Stack", and the Scala Essentials course page.

## The stance

- **You, throughout.** The reader does things and the framework does things. "You'll write a case
  class", "eezo derives the routes". Never "we": no "we'll", no "let's", no "our application". The
  imperative is fine for steps: "Create a file", "Run this".
- **Talk to one person who is smart and busy.** No condescension, no hype. Assume they can read
  Scala; do not assume they know eezo.
- **Opinions are allowed and signed.** When a page says something is a bad idea, it says so plainly
  and says why. "This straightforward approach is rarely the recommended one, for a few big reasons".

## The shape of a page

- **Open by situating.** Who the page is for, what they will have at the end, what is assumed. One
  short paragraph, sometimes two. "This page is for people who have written some Scala and have
  never deployed a server." Tutorials get a line on time: "about twenty minutes".
- **Problem first, then the tool.** Set up a small, concrete situation before showing what eezo
  does about it. "Say you're building a bookshelf app. Every book has a title and an author."
  Then the code. Then "Enter X" or "Here's where eezo comes in" is fine once in a while, not on
  every page.
- **Code, then one or two plain sentences about what it does.** Never a wall of prose before the
  code, never code with nothing after it. The sentence after a block starts with what the reader
  sees: "This mounts seven routes", "Notice that", "In other words".
- **Build up in small blocks.** Show the file in pieces as it grows, and say so: "keeping the above",
  `// added just now`, `???` for a hole that is filled in the next step. One full listing at the end
  when the pieces are hard to assemble.
- **Reasons as bullet lists.** "Why is this better? For a few reasons:" and then three or four
  bullets, each a sentence. Steps to do are also lists. Prose for everything else.
- **Close short.** A "Conclusion" or "Where to go next" of two to four sentences: what you now
  have, what it cost, one link onward. A light joke is allowed and should be rare.

## Sentences and words

- Plain words. A thing is "a wrapper", not "an abstraction layer". A problem is "annoying" or
  "cumbersome", not "suboptimal". Jargon is introduced in quotes the first time, then used.
- Contractions, always: it's, you'll, don't, that's.
- Short paragraphs, one to three sentences. Medium sentences, with the occasional long one that
  runs on commas because the thought does. No aphorisms, no sentence that sounds engraved.
- Say the thing, then the reason: "Bind to 0.0.0.0, because on Heroku you can't bind to
  localhost." Not the reverse.
- Parentheses for asides: "(you can use your own name here)", "(it's supposed to be a snake)".
- Signposts he actually uses: "Notice that", "Notice how", "In other words", "Short story:",
  "That said", "Of course", "Easy enough.", "And that's it!", "Then what?", "Now, for the logic.",
  "So here goes.", "Here's how it looks:", "Say for example", "for the sake of the example".
- Admit simplification where it happens: `// simplified`, "(perhaps with more fields)".
- The framework is "eezo", lower case, always. Commands are code: `eezo dev`.

## What is not in the voice

- Not "we" and not "let's".
- No triplets for rhythm ("fast, simple, and powerful"). If there are three reasons, bullet them.
- No "robust", "seamless", "leverage", "powerful" as filler, "delve", "it's worth noting", "it's
  important to", "simply", "just" as a softener, "a journey", "elegant". No "Great question".
- No rhetorical setups that answer themselves in the next sentence more than once per page.
- No em-dashes as a habit. A hyphen with spaces, or a comma, or a new sentence.
- No sentences that explain why the sentence exists. The repository's source comments do this on
  purpose; the docs do not.
- No "In this section we will discuss". Say the thing.
- No bulleted summaries of the page at the top. One opening paragraph.
- Not the repository's own prose cadence either: its README and design notes have a clipped,
  declarative, aphoristic style ("the one spelling", "which is what lets", "a value, never a
  string"). That is the framework's voice in its source. The docs are a person talking.

## From Daniel's review of the first page

These came from the markup on the getting-started tutorial and outrank anything above.

- **Cut the motivation for prerequisites.** "JDK 25 or newer" is the whole sentence. No paragraph
  on why, no history of the decision. If the reader wants the why, the explanation pillar has it.
- **Don't explain the tooling underneath.** What the launcher forwards to, what sbt does with a
  setting, what "housekeeping" a build file holds: cut it, unless the page is about the tool.
- **No Claude-isms.** "This one isn't negotiable", "it's worth knowing why", "two lines matter",
  "that's deliberate", "this matters more than it sounds", "worth a pause", "here's the thing".
  These announce importance instead of stating a fact. State the fact.
- **No two-dollar phrases.** "Two lines of substance", "as plain as a web handler gets". Plain
  words or nothing.
- **No "it's not X, it's Y".** Also "rather than", "never in", "not something that". Say what it
  is. If the contrast matters, it gets its own plain sentence, and most of the time it doesn't.
- **No sentence that circles back.** "A dev server that goes down every time you mistype
  something is a dev server you stop trusting" restates the previous sentence to sound smart.
  The test: delete the sentence, and if nothing is lost, it stays deleted.
- **No sentence that exists to sound like a sentence.** "The method itself is as plain as a web
  handler gets." carries nothing. Cut.
- **Don't narrate an output block after showing it.** "Your one route, plus two that belong to
  the framework:" repeats what the reader just read. After an output, say only what they can't
  see in it, or say nothing and move on.
- What was right: informative, to the point, goal and expectations set at the top. Keep that.

## Facts

- Every command on a page was run, and every output block was captured, not typed. If the output
  changes with the version, say which version it came from.
- Every claim about behaviour points at the thing that makes it true, in a sentence, not a
  footnote: "the plugin writes the table to `target/.../Routes.scala`, open it".
- Where the page wants an opinion or a war story that only Daniel can supply, leave a visible
  marker: `> **Daniel:** ...` with a one line prompt of what would go there. Do not invent one.

## A worked contrast

Not the voice:

> A handler is a plain function from `Request` to `Response`, which is what lets the route table
> stay flat: capabilities arrive through the signature, and their absence is a compile error
> rather than a runtime message.

The voice:

> A handler is a plain function: it takes a `Request` and gives back a `Response`. That's it. If
> your handler needs something, like a database, it asks for it in its signature, and if it isn't
> there you'll get a compile error instead of a crash on the first request.
