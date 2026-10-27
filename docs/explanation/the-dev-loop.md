# The dev loop

What `eezo dev` does on every save, what the browser does in response, and why a failed
compile is not an outage.

## The loop

`eezo dev` is one sbt command of the plugin, `eezoDev`, the dev console. It watches the source
tree itself, and on every change does three things, in this order:

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

The watch is the console's own rather than sbt's `~`, with a hundred milliseconds of quiet
between the last file event and the build, so an editor that writes several files on one save,
a rename across a package for one, compiles once. A save that lands while a compile is running
is remembered and compiles once that one is done.

## The console

`eezo dev` takes the whole terminal. The top row names the project and says what the loop is
doing: a spinner while a compile runs, labelled with the file that triggered it, then `✓ built
in 0.4s`, or `✗ 2 errors`, and beside it the address the application serves on, or that it
exited. Under it, four panes, one key each:

- **1 log**: everything the application prints or logs, a stack trace included, following its
  tail until you scroll up.
- **2 errors**: the compiler's diagnostics as values, one block each: the file, line and
  column, then the compiler's own rendering with the excerpt and the caret. Warnings after
  errors. The pane opens by itself when a build fails; the tab shows the count.
- **3 routes**: the route table the running application mounted, as an aligned table, with the
  warnings boot would print under it: a derived route a handwritten one replaced, a route that
  shadows another, a form page whose submit target is not mounted.
- **4 schema**: the drift the dev server found at boot, each change in the colour of its class,
  and the schema commands as keys: `s` applies the drift to the dev database (it asks first when
  a change loses data or can fail, and adds `--force` only then), `f` writes it as a named
  migration and applies that, `m` lists the pending migrations and applies them on Enter, `S`
  diffs the model against the database again. Each runs the same command `eezo db` runs, as a
  child on the build's classpath, and the console restarts the application when one succeeds.
  An application with no database layer has no schema pane.

The bottom row is the keys that apply right now, and where a question is asked: a migration's
name, or whether to force. `r` rebuilds and restarts without a save, `c` clears the log, `?` lists
every key, `q` or Ctrl-C stops the loop and the application with it.

The layout follows the terminal. Under sixty columns the tabs shorten and the footer keeps only
the keys that matter most; under twelve rows the header and the tabs share one row and the
footer goes; under six rows there is only the status row. Colour is sixteen colours and off under
`NO_COLOR`; the box drawing and the glyphs fall back to ASCII when the terminal is not UTF-8.

What is kept out: sbt's own narration, its loading, its progress bars, its timings, and the
`java.util.logging` records Jetty and HikariCP write at INFO on every boot, which say the same
thing every time. Their warnings still come through.

Without a terminal, in a pipe or in CI, the console has nobody to stop it, so it runs only as
`sbt "eezoDev --once"`: the same events printed as lines, the compile, the diagnostics, the
application's boot, cut short once the application serves or exits, and the command fails when
it did not serve, which is what a build check wants. A watch without a terminal is refused rather
than left running after its reader is gone.

The console learns what the application mounted and what drifted from three lines the
application prints at boot: `serving on <url>`, and, when the plugin asks for them with
`EEZO_DEV_MARKERS`, `eezo dev: routes <json>` and `eezo dev: drift <json>`, the same values
`eezo routes --json` and `eezo db status --json` print, on one line each. `sbt eezoDev` from the
sbt shell is the same console; `sbt "run dev"` by hand is the application alone, without them.

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
page or from another terminal shows up on the next refresh. The console shows the same drift in
its schema pane, switches to it when the drift blocks, and offers the same actions as keys;
resolved from there, the application is restarted at once. A database that can't be reached
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
- **A database-only application.** `dev` is the http layer's command. On `DbApp` alone it's
  unknown, and a rerun-on-save loop for a job is sbt's own `~run`.

> **Daniel:** the save-to-reload timings you measured when choosing sbt's resident watch over
> sbt-revolver, if you want numbers on this page.

## Where to go next

What the forked JVM starts, and how it comes down, is [the server](the-server.md).
