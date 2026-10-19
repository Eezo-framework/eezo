# eezo, for agents

Where to look before touching code:

- `CONTEXT.md` is the vocabulary. Use its words for every name you introduce, and the ones it marks _Avoid_ never.
- `docs/adr` holds the decisions that bind the code; `docs/explanation` says how the pieces fit.
- `README.md`, under Contributing, has the build and the branch rules: `dev` is the trunk every pull request targets, `main` is the latest release and nobody commits on it.

The build is three sbt builds: the modules at the root, the examples under `examples/`, the site under `site/`. The gate is `bin/gate`. It runs what CI runs, in CI's order, serialises concurrent runs, and its header lists the gotchas the build does not confess (capture checking in db, Docker through colima, sbt in a linked worktree). Run it before claiming green; for the red green loop run one suite, `sbt -batch http/testOnly <Suite>`.

Prose, in docs, scaladoc and commit messages, contains no dash characters.
