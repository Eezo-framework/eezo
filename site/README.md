# site: eezo.io

The documentation site, an eezo application on the http edge with the live layer on it. It
renders the repository's own Markdown: the README, `CONTEXT.md`, everything under `docs/`, the
research notes and the example READMEs, so the site is never a second copy of anything.

## The four pillars

The docs are organised the Diátaxis way, and `docs/` has a directory per pillar:

| directory           | pillar        | answers                                   |
|---------------------|---------------|-------------------------------------------|
| `docs/tutorials/`   | Tutorials     | take me through building something        |
| `docs/how-to/`      | How-to guides | get this one thing done                   |
| `docs/explanation/` | Explanation   | how does this part work, and why          |
| `docs/reference/`   | Reference     | what exactly is the API, command, setting |

Every `.md` under a pillar's directory is a page of that pillar; the order is the list in
`src/main/scala/site/Pages.scala`, and a file not on the list follows alphabetically, so a new
page is served before anyone lists it. The example READMEs are grouped at the end of Tutorials
and `CONTEXT.md` is the vocabulary at the end of Reference. `/docs` is the hub and
`/docs/<pillar>` each pillar's index. The ADRs under `docs/adr` and the research notes are the
repository's own record and are not pages; a link to one goes to GitHub.

The Reference pillar is mostly generated. `sbt unidoc` at the repository root writes one scaladoc
over the framework modules to `target/unidoc`, the site's build copies it into the jar, and it is
served under `/api` with its own search. A page goes out dressed in the site's chrome, see
`src/main/scala/site/ApiPages.scala`: the logo, the top links and the theme toggle replace
scaladoc's header, and `assets/api.css` restyles scaladoc's own variables with the site's fonts
and colours; the markup underneath stays scaladoc's, because its scripts expect it. The
hand-written reference pages are what scaladoc
cannot say: the command line, the sbt plugin's tasks, the environment, the routing conventions,
the type mappings, the file formats, the wire protocol, and the vocabulary. The scaladoc options
(logo, footer, source links, the skipped internal packages) are on the root project in the root
`build.sbt`, and `site/api-root.md` is its front page.

A page whose first line is `<!-- draft -->` is a placeholder: the site shows a draft banner on
it, a chip beside it in the index, and a dot after it in the tree. Write the page and remove the
marker. The test suite fails on a Markdown file that no pillar reaches.

## Local development

You need JDK 25 and sbt, nothing else: no database, no Docker, no Node.

```bash
# 1. At the repository root, once, and again whenever the framework changes. eezo is not
#    released yet, so the site resolves it from the local ivy cache at the version this records.
#    `unidoc` writes the API reference the site serves under /api; without it the site still
#    runs and /api says what to do, and the API tests are skipped rather than failed.
sbt publishLocalForExample unidoc

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
