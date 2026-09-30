<!-- draft -->
# Your first eezo application

Scaffold an application, run it, add a page, and watch it restart on save. Twenty minutes, no database.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- Prerequisites: JDK 25 and why (JEP 491, the floor does not move down), sbt, a local publish while eezo is unreleased
- `eezo new <name>`: what the scaffold writes and why each file exists
  - `build.sbt` and the two lines that matter: `enablePlugins(EezoPlugin)` and the `eezo` dependency
  - `project/plugins.sbt` and the `sbt-eezo` plugin
  - `Main.scala`: `object Main extends EezoApp`, `schema` and `routes`, what `main` dispatches
  - `app/Index.scala`: the filename is the route
  - the `models/` directory, empty for now
- `eezo dev`: the dev loop
  - what the console prints at boot: the route listing, `GET /`, the framework's own routes under `/eezo`
  - edit the page, save, refresh: recompile and restart, the reload client
  - break the compile on purpose: the old server keeps serving
  - `/eezo/health`
- A second page by filename
  - `app/Hello.scala` with `def hello(request: Request): Response` mounts `GET /hello`
  - `request.queryParam`, `request.path`, `Response.Ok`
  - the HTML DSL in one paragraph: `Html.doctype ++ html(head(...), body(...))`, `Attrs.href := ...`
- `eezo routes`: reading the table, and where the generated `Routes.scala` is written
- What was not needed: a router, a controller, a template language, a config file
- Where to go next: the model tutorial, then the deploy walkthrough

## Where the material is

- `bin/eezo` (`scaffold`) for exactly what `eezo new` writes
- `examples/hello` and its README
- `examples/todo/README.md` §5 for the dev loop, with real output
- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala` for the filename rules
