# eezo, for agents

Where to look before touching code:

- `CONTEXT.md` is the vocabulary. Use its words for every name you introduce, and the ones it marks _Avoid_ never.
- `docs/adr` holds the decisions that bind the code; `docs/explanation` says how the pieces fit. `bin/adr-next` gives a new ADR its number, counting the ones drafted on other branches.
- `README.md`, under Contributing, has the build and the branch rules: `dev` is the trunk every pull request targets, `main` is the latest release and nobody commits on it.

The build is three sbt builds: the modules at the root, the examples under `examples/`, the site under `site/`. The gate is `bin/gate`. It runs what CI runs, in CI's order, serialises concurrent runs, and its header lists the gotchas the build does not confess. Run it before claiming green. Every other sbt command goes through `bin/fast`, which sets up the same environment and runs the commands it is given in one sbt start: `bin/fast 'http/testOnly io.eezo.http.ApiSuite'` for one suite, quoted or sbt runs the whole module, `bin/fast --examples shop/test` for the examples build. The project id is the directory name under `modules`, so `upickle`, not the lazy val name `eezoUpickle`, except `sbt-plugin`, whose id is `sbtEezo`.

Two things the gate learns late: an example's README is a site page, so a new one is listed in `site/src/main/scala/site/Pages.scala`; an example's suite drives `Main.routes.dispatch`, because `HttpServer` and `HttpConfig` are private to eezo.

The shell is zsh: quote globs (`--include='*.scala'`) and never start a word with `=`.

Prose, in docs, scaladoc and commit messages, contains no dash characters; `bin/prose-check <paths>` lists the offenders.
