<!-- draft -->
# Two edges, and the artifacts that carry them

An application has a database edge, an http edge, or both, and says so by dependency. This is what that buys and what it forbids.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the database edge and the http edge: what each brings up and takes down
- `eezo-http`, `eezo-db`, `eezo`: `HttpApp`, `DbApp`, `EezoApp`, and `LiveApp` between them
- a derivation belongs to one edge; deriving for an edge you do not have is a compile error, and what the error looks like
- `Dispatch`: `main`, the commands each edge adds, `help`, `--json`
- how the umbrella resolves two `program`s into one order, and why `override` is withheld on the edges
- `eezo-auth`, `eezo-live`, `eezo-testkit` and which edge each sits on

## Where the material is

- `CONTEXT.md`, Application
- `build.sbt` at the root, the comments on each module
- `modules/core/src/main/scala/io/eezo/core/Dispatch.scala`, `modules/http/.../HttpApp.scala`, `modules/eezo/.../EezoApp.scala`
- `docs/adr/0005-the-entry-traits-landed-five-things-their-decision-tickets-had-settled-otherwise.md`
- the three example READMEs, each showing the missing edge's derivation failing
