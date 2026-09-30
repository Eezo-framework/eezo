<!-- draft -->
# Mount routes under a prefix

Serve part of the table under `/admin`, with the pages' own links, forms and redirects moving with it.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `Route.under("/admin")(routes)` and splitting a table on `Provenance`
- rebuilding the table with `RouteTable(rearranged, table.identify)`, and what is lost by forgetting `identify`
- `Url.Mounted` travels, a `String` and `Url.Absolute` stay: which to write where
- linking into a mount from a page outside it
- mounting the same route twice at two prefixes, and what a live page's self link reads under each

## Where the material is

- `examples/blog/src/main/scala/Main.scala` and `app/Index.scala`
- `modules/http/src/main/scala/io/eezo/http/Route.scala` (`Route.under`)
- `modules/core/src/main/scala/io/eezo/core/html/Url.scala`
- `docs/adr/0004-an-emitted-url-is-a-value-and-a-string-is-absolute.md`
