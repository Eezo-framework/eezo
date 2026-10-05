# The dev loop

What `eezo dev` does on every save, what the browser does in response, and why a failed
compile is not an outage.

## The loop

`eezo dev` forwards to `sbt eezoDev`, and `eezoDev` is an alias for `~eezoRestart`: sbt's own
watch, over the source tree, around one task. On every change the task does three things, in
this order:

1. **Compile.** The task asks for the project's full classpath, and asking for it compiles the
   project first. If the compile fails, the task fails here, and nothing after it runs.
2. **Kill the previous application**, politely, then forcibly after ten seconds if it ignores
   the signal. The kill completes before the next step starts, so the new process never races
   the old one for the port.
3. **Fork a fresh JVM** on the new classpath, running `Main dev`, with its output in the same
   console as the compile.

Step one is why a typo isn't an outage. The previous process is still serving when the compile
fails, and it keeps serving until a compile succeeds. The dev loop never kills a working server
for an error in the editor.

The route generator runs inside step one, as a source generator, so a new file under `app/` is
a new row in the table on the same save. It caches on the hash of the sources it reads, and
writes the generated file only when its content changed, so a save that doesn't touch a route
doesn't invalidate the compile that follows.

## What the browser does

Every page the dev server serves gets a small script appended to its `<body>`. The script keeps
a WebSocket open to `/eezo/reload`. The restart that follows a save closes that socket; the
script notices, polls until the new server answers, and reloads the page. Whole page, no state
kept.

The script and the endpoint exist only when `dev` is on. In production the endpoint is an
ordinary 404 and no page carries the script. The injection is structural, into the first
`<body>` element of a `Body.Html`, so a response that isn't a page, or one you built from raw
bytes, is left alone.

A live page is reloaded the same way. Its state was in the process that died, so the client
reloads into a fresh page with a fresh `init`.

## The drift check

Before the dev server serves, it diffs the database against the model. Additive drift is a
banner and the application serves. Destructive or risky drift is a page in place of the
application, with the two actions on it, re-checked on every `GET` so resolving it from the
page or from another terminal shows up on the next refresh. A database that can't be reached
is a warning, and everything that doesn't need it serves. [Schema and
migrations](schema-and-migrations.md) has the detail.

## One secret per sbt session

The session cookie is signed with the application secret, and every restart is a new JVM. A
child left to generate its own secret would sign you out on every edit. So the plugin
generates one secret when the loop first starts, keeps it for the life of the sbt session, and
hands it to every child as `EEZO_SECRET`, unless sbt's environment already has one, which the
child inherits. A `reload` of the build makes a fresh secret and signs you out once.

## What `eezo dev` doesn't do

- **Hot swap.** No class reloading, no agent. Every change is a full restart of the
  application JVM, which is what makes the result trustworthy: the running code is always the
  code on disk.
- **State preservation.** In-memory stores, live page state and anything else the process held
  are gone on restart. A model with a `Table` keeps its rows, because they're in Postgres.
- **A database-only application.** `dev` is the http edge's command. On `DbApp` alone it's
  unknown, and a rerun-on-save loop for a job is sbt's own `~run`.

> **Daniel:** the save-to-reload timings you measured when choosing sbt's resident watch over
> sbt-revolver, if you want numbers on this page.

## Where to go next

What the forked JVM starts, and how it comes down, is [the server](the-server.md).
