<!-- draft -->
# Share state between live pages

Make two browsers converge on one value with a `Topic`, and keep `handle` honest while they do.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `Topic[A]`, subscribing in `init` and nowhere else
- the discipline: `handle` publishes and leaves the shared state alone; the publisher's own page updates through its subscription
- coalescing under load, and what a chatty topic costs
- a keyed list: `key(...)` on rows so a reorder moves nodes instead of re-rendering them
- testing it: two sockets on one server

## Where the material is

- `docs/tutorials/a-live-page.md` §4
- `examples/blog/src/main/scala/components/Guestbook.scala`, `Board.scala`
- `modules/live/src/main/scala/io/eezo/live/Topic.scala`, `Page.scala`
