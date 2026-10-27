# The sbt plugin

`sbt-eezo`: the tasks and settings it adds, the source it generates, and the sbt versions it
runs on.

## Enabling it

```scala
// project/plugins.sbt
addSbtPlugin("io.eezo" % "sbt-eezo" % eezoVersion)

// build.sbt
enablePlugins(EezoPlugin)
```

The plugin does nothing until a project enables it. An application with no routes, a job on
`eezo-db` alone, doesn't need it.

## Tasks

| task | what it does |
|---|---|
| `eezoGenerateRoutes` | writes `Routes.scala`; runs on every compile as a source generator, cached on the sources' hashes |
| `eezoDev [--once]` | a command, not a task: the dev console. Watches the sources, compiles on every save, forks `Main dev` with its output piped into a pane, and draws the log, the errors, the routes and the drift on the terminal; the schema commands on keys. Without a terminal only `--once` runs: it builds, starts, prints the events as lines, waits for the application to serve, stops, and fails when it did not |
| `eezoRestart` | prints `eezo dev: building`, compiles, kills the running application, forks a fresh JVM running `Main dev` with its output inherited, prints `eezo dev: started <Main>`; `~eezoRestart` is the loop without the console |
| `eezoStop` | stops the forked application, if any |
| `eezoRoutes` | `run routes` |
| `eezoStatus` | `run db status` |
| `eezoSync <args>` | `run db sync <args>`; `--apply` and `--force` pass through |
| `eezoMigrate <args>` | `run db migrate <args>` |
| `eezoFreeze <args>` | `run db freeze <args>` |
| `eezoStage` | stages `target/eezo/stage/`, returns the directory |

The five forwards go through `Compile / run`, so they inherit everything the build decided
about running: `fork`, the working directory, `javaOptions`, `javaHome`. `eezoRestart` reads the
same keys for the JVM it forks. The forked application's output is inherited into sbt's
console.

## Settings

| key | default | notes |
|---|---|---|
| `eezoJavaVersion` | `"25"` | the JRE tag `eezoStage` writes into the Dockerfile |
| `Compile / run / connectInput` | `true` | set by the plugin so `freeze` can prompt |
| `Compile / eezoGenerateRoutes / watchTriggers` | `src/main/scala/**/*.scala` | what the watch reacts to |

`run / fork := true` is the scaffold's, not the plugin's, and it's needed: the application
runs on the JDK floor, and sbt's own JVM may not be on it.

## The dev loop

`eezoDev` asks for the full classpath, which compiles the project first. A failed compile
leaves the running application untouched, so it keeps serving, and its diagnostics fill the
errors pane. On success the previous process is destroyed, forcibly after ten seconds, then the
next is forked, with `EEZO_DEV_MARKERS` set so the application prints its route table and its
drift as one JSON line each (`eezo dev: routes`, `eezo dev: drift`), which the console reads.
One secret per sbt session is passed as `EEZO_SECRET` unless the environment already sets one.
A `reload` of the build strands the running child until sbt exits, and makes a fresh secret.

The console runs sbt's tasks under a session with console logging and the progress bars off,
since the screen is its own; a failed compile's log is still in `last compile`. It reads the
terminal through sbt's own terminal layer, the one the sbt shell uses, so `eezoDev` typed at the
shell works the same as from the launcher, and `q` hands the terminal back. It refuses to run
behind sbt's thin client, which an sbt 2 project gets from the `sbt` script by default, because
the client returns at the first failed task and would leave the loop running in a server nobody
sees: `sbt --server eezoDev` there, or `eezo dev`, which passes the flag.

`dev` is the http layer's command, so `eezoDev` on a `DbApp` prints the unknown command line on
every save. A job's rerun-on-save loop is sbt's own `~run`.

## The generated file

`Compile / sourceManaged`, at `io/eezo/generated/Routes.scala`, which is on the managed source
path so an editor can navigate into it. [Routing conventions](routing.md) has its contents.
It's rewritten only when its content would change, so a save that doesn't touch a route
doesn't invalidate the compile that follows. Two facts about the build are folded into the
cache key, because no source file carries them:

- whether `eezo-db` is on the compile classpath, read off the resolution report so a
  transitive `eezo-db` counts; it decides whether `storeFor` may name `JdbcStore`;
- whether `eezo-auth` is declared in `libraryDependencies` in a configuration the compiler
  sees (`compile`, `provided`, `optional`, or unscoped), which decides whether a route with no
  `Guarded` is a compile error.

Adding either dependency therefore regenerates the table on the next compile with no source
change.

## sbt versions

| axis | Scala | floor |
|---|---|---|
| sbt 1 | 2.12.21 | 1.5.8 |
| sbt 2 | 3.8.4 | 2.0.6 |

One source file set, compiled on both axes. The source stays inside the subset both compilers
accept, and building both is the only check that it does. The plugin's bytecode targets are
Java 8 on sbt 1 and 17 on sbt 2, which are sbt's floors and have nothing to do with the JDK the
application needs.

## Related

[The dev loop](../explanation/the-dev-loop.md) is what the restart does to the browser.
[The CLI](cli.md) is the launcher that wraps these tasks.
