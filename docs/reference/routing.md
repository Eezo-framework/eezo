<!-- draft -->
# Routing conventions

The file name table and the path grammar: what a file under `app/` mounts, and in what order the table is matched.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done. The types and members themselves
> are documented in [the generated API](/api/); this page holds what scaladoc cannot say.

## What to cover

- the file name table: `Index`, `New`, `Show`, `Edit`, `Create`, `Update`, `Destroy`, custom; method, `def` name and path for each
- directory segments: literal, `_name` for `:name`, `__name` for `*name`; a catch-all is last or a boot error
- emit order: most static first, ties by path then method
- precedence: a handwritten route over a derived twin; shadowing reported, a duplicate refused
- the generated file: location, the `// from` comments, when `storeFor` is emitted, when `Guarded` is demanded
- the reserved `/eezo` prefix and what the framework serves under it
- the `Route`, `RouteTable` and `PathPattern` types are in the API: link them rather than restate them

## Where the material is

- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala`
- `modules/http/src/main/scala/io/eezo/http/Route.scala`, `PathPattern.scala`
