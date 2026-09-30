# site: eezo.io

The documentation site, an eezo application on the http edge with the live layer on it. It
renders the repository's own Markdown: the README, `CONTEXT.md`, everything under `docs/`, the
research notes and the example READMEs, so the site is never a second copy of anything. Which
files make pages, and in what order, is `src/main/scala/site/Pages.scala`; a new ADR or research
note is a page with no change there.

## Local development

You need JDK 25 and sbt, nothing else: no database, no Docker, no Node.

```bash
# 1. At the repository root, once, and again whenever the framework changes. eezo is not
#    released yet, so the site resolves it from the local ivy cache at the version this records.
sbt publishLocalForExample

# 2. In this directory.
cd site
sbt eezoDev                     # http://localhost:8080, restarts on every save
```

`eezoDev` recompiles and restarts the site when a Scala file changes, and the open tab reloads
itself. Markdown needs no restart at all: in development the site reads the repository on every
request, so an edit to `docs/live.md` shows on the next refresh. A new file appears in the tree
on the next refresh too.

Other things to run from here:

```bash
sbt run                         # serve once, without the watch
sbt test                        # every page renders, every link resolves, no file is left out
sbt scalafmtAll                 # format; CI runs scalafmtCheckAll
sbt "run routes"                # the five routes the site mounts
```

If 8080 is taken, add `override def port: Int = 8090` to `Main`.

## How it is put together

Five handwritten routes and no model: the front page, `/docs`, the catch-all that serves a page
by its address, the catch-all that serves the stylesheet, the fonts and the icons out of the jar,
and `POST /theme`. Markdown is parsed with commonmark-java and walked into eezo's own `Html`
nodes, so a page is one tree from the header to the footer; the code blocks are coloured on the
server.

The site ships no script of its own. What moves on a page is the live layer: the navigation
drawer on a narrow screen is a `Component[Boolean]`, and the front page's counter is a
`Component[Int]`, both patched over the framework's one socket. The theme toggle is a plain form,
because a choice that has to outlive the page belongs in the session, not in a live page's state.

## Deploying

`../bin/eezo deploy` from this directory, and `.github/workflows/deploy-site.yml` runs it on
every push to main with a `FLY_API_TOKEN` secret. `fly.toml` is the app's, committed; the one
thing it does not carry that the launcher would write is a release command, since there is no
database to migrate.
