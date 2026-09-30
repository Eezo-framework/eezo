<!-- draft -->
# URLs as values, and what a mount moves

Why an emitted address is a value rather than a string, and how that lets a set of routes move under a prefix with every link intact.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the failure a string cannot prevent: a page whose links point where the route no longer is
- `Url.Mounted` and `Url.Absolute`; a plain `String` means absolute
- which attribute names may carry a `Url`, and why the list is closed
- `Route.under`: the pattern moves and the handler is wrapped; `Response.under` walks headers and page
- a second `under` moves it again; a live page reports its base at join
- what is deliberately left alone: another site, `mailto:`, a page outside the mount pointing in

## Where the material is

- `docs/adr/0004-an-emitted-url-is-a-value-and-a-string-is-absolute.md`
- `modules/core/src/main/scala/io/eezo/core/html/Url.scala`, `Attrs.scala` (`UrlAttrName`)
- `modules/http/src/main/scala/io/eezo/http/Route.scala`, `Response.scala`
