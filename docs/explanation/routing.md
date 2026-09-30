<!-- draft -->
# Routing by file layout

How a file under `app/` becomes a row in a generated table, how the table is ordered and matched, and why nothing in it is found by reflection.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the generator runs before the compiler: textual on purpose, what it can and cannot warn about
- the seven REST names, custom names, directory segments; `Provenance.Handwritten` and `Derived`
- emit order: most static first, ties by path then method; why sorting happens at generation and not at boot
- matching: first match in `Seq` order, the 405 with its `Allow` header, the 404
- a handwritten route wins a derived twin; shadowing is legal and reported; a duplicate is a boot error
- `Routes.table()` as a `def`: stores minted per call, and what that means for tests
- `identify`: who the table says is behind a socket upgrade
- the reserved `/eezo` prefix and the framework's own routes
- why the model scan tracks structure, not columns: the indented model that mounted nothing

## Where the material is

- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala`
- `modules/http/src/main/scala/io/eezo/http/Route.scala`, `PathPattern.scala`
- `docs/adr/0003-skeleton-one-landed-the-http-surface-later-tickets-had-already-decided.md`
- `docs/tutorials/deploy-to-fly.md`, Troubleshooting (fewer routes than expected)
