# Writing the docs: how to continue

This is the handoff for whoever writes the next pages, in a new session or by hand. Read
`VOICE.md` first; it is the voice and the review checklist, and it includes Daniel's own markup
on the first page. Then read the three written tutorials as the examples of the standard:
`docs/tutorials/getting-started.md`, `first-model.md`, `sign-in.md`.

## State

- Written and reviewed: the three tutorials above. The two walkthroughs, `deploy-to-fly.md` and
  `a-live-page.md`, predate the guide and have not been rewritten to it.
- Written, awaiting review (2026-10-02): all fifteen pages under `docs/how-to/`. The ones that
  build on the bookshelf were run and captured; the ones on the live layer, failures, logging
  and testing were written from the walkthroughs, the examples and the code.
- Written, awaiting review (2026-10-02): all fourteen pages under `docs/explanation/`, from the
  code alone; the research notes and ADRs were deleted before this pillar and are not a source
  any more. `failures.md` was rewritten to the voice at the same time. Four `> **Daniel:**`
  markers stand where the deleted notes held measurements or a survey: the dev loop's timings,
  the server comparison, the deploy target survey and the redeploy time.
- Written, awaiting review (2026-10-02): all eight pages under `docs/reference/`, from the
  code, with the JSON bodies, the snapshot, the first migration and the staged Dockerfile
  captured from the bookshelf. No placeholder remains anywhere; the draft tests in
  `PagesSuite` now build a draft in memory from `reference/cli`.
- The two walkthroughs, `deploy-to-fly.md` and `a-live-page.md`, are the last pages that
  predate the voice guide.
- The site shows drafts as such: a banner on the page, a chip in the pillar index, a dot in the
  tree. `cd site && sbt test` fails on a broken link, so run it after every page.

## The method, per page

1. Read the placeholder's outline and the files it points at. The outline is the table of
   contents; it is not prose to expand.
2. Build the thing the page describes and run every command on it. The tutorials were written on
   a scaffolded `bookshelf` application (`eezo new bookshelf`, then the steps in each tutorial);
   twenty minutes recreates it, with a Postgres from the `docker run` line in `first-model.md`.
   The how-to pages that touch the blog or todo example run those instead.
3. Capture every output block from the run. Never type one. Trim noise (the JUL and Hikari lines)
   but not content.
4. Write in the voice. The checks that caught the most on the first page: no "we", no narration
   after an output, no sentence announcing that something matters, no sentence that restates the
   previous one, no motivation for prerequisites, no explanation of the tooling underneath.
5. Where the page wants an opinion or a story only Daniel has, leave `> **Daniel:** <what would go
   here>` rather than inventing it.
6. Remove `<!-- draft -->`, run `cd site && sbt test`, and deploy with `../bin/eezo deploy` from
   `site/` if the session is deploying.
7. Stop at the end of a pillar for review. One pillar per session is the right size.

## Order

Every pillar is written. What is left is Daniel's review of the whole site, and then the two
walkthroughs rewritten to the guide. Within a pillar, the order in
`site/src/main/scala/site/Pages.scala` is the reading order.

## Things that are easy to get wrong

- Links between pages are relative Markdown links, `../how-to/deploy.md`, and the site resolves
  them. A link to a file that is not a page goes to GitHub, which is fine for source files.
- The scaffold's version string in captured output changes with every commit. Leave it as
  captured and say so once per page, as `getting-started.md` does.
- `eezo status` exits 1 on drift; that is not a failure.
- The default database is `localhost:5442/eezo`; the examples each keep their own Postgres
  schema, the bookshelf uses the public one. For a clean capture, create a database and set
  `EEZO_DB_URL`.
- `Compile / run / mainClass := Some("Main")` is needed as soon as a project has `CreateUser`.
