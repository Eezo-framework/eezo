// The example applications, in one build so that they share one meta build: the plugin is
// resolved once, and `EezoVersion` — which reads the locally published version and toolchain out
// of `.eezo-version` — has one definition rather than one per example. Each example is still an
// ordinary single-source-tree project that enables `EezoPlugin` for itself, which is what a real
// application is.
//
// `EezoPlugin` is what puts `io.eezo.generated.Routes` on the source path. It is `noTrigger`, so
// the `enablePlugins` below is not optional.

// Set on `ThisBuild` as well as on each example, because the aggregate sbt generates for
// `examples/` itself has no sources and otherwise falls back to sbt's own Scala 2.12, saying so on
// every load.
ThisBuild / scalaVersion := EezoVersion.scalaVersion

lazy val commonSettings = Seq(
  scalaVersion := EezoVersion.scalaVersion,
  scalacOptions ++= Seq(
    "-release",
    EezoVersion.jdkFloor,
    "-deprecation",
    "-feature",
    "-unchecked",
    "-no-indent"
  ),
  // eezo needs JDK 25 to run: JEP 491, which removed virtual-thread pinning on `synchronized`,
  // landed in JDK 24, and the server design depends on it.
  run / fork := true,
  // JUL's ConsoleHandler writes to stderr, which sbt's default strategy labels [error]; passing
  // the forked output straight through keeps the boot print unprefixed.
  run / outputStrategy := Some(OutputStrategy.StdoutOutput)
)

// Each example depends on the artifact of the edges it has, and that dependency is the one line
// that decides what compiles in it. `eezo-http` alone carries `HttpApp`, `Form` and `Resource`;
// `eezo-db` alone carries `DbApp` and `Table`; the umbrella `eezo` carries both and `EezoApp`. A
// derivation for an edge the example does not have is a compile error, because the type is not on
// the classpath: see each example's README for the line that proves it.
def edge(artifact: String) = "io.eezo" %% artifact % EezoVersion.value

// `freeze` writes `db/migrations` and `db/schema.json` against the working directory; pin the
// forked run to the project dir so the terminal commands and the dev loop write one place.
lazy val runInProjectDir =
  Compile / run / forkOptions := (Compile / run / forkOptions).value
    .withWorkingDirectory(baseDirectory.value)

// The http edge alone: one handwritten route, no database, no derivation. `derives Table` does not
// compile here.
lazy val hello = (project in file("hello"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(
    name := "hello",
    libraryDependencies += edge("eezo-http"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.4" % Test // SPIKE
  )

// The database edge alone: one model deriving `Table`, an `AppSchema`, and a `boot` that is a job
// over rows. No routes, so no `EezoPlugin`; `derives Form` does not compile here.
lazy val reminders = (project in file("reminders"))
  .settings(commonSettings)
  .settings(
    name := "reminders",
    libraryDependencies += edge("eezo-db"),
    runInProjectDir
  )

// Both edges: one model deriving `Table, Form, Resource`, seven CRUD routes in a browser over rows
// in Postgres, and the derived half of the table mounted under `/admin`.
// `eezo-auth` is named here even though the umbrella already carries it, and naming it is what
// turns the completeness check on: the generated route table only *demands* a `Guarded` of every
// route in an application that asked for a way of signing in. `examples/todo` depends on the same
// umbrella and does not name this, so its routes are not asked to declare anything and every one
// of them is public. What naming it does not decide is whether a declaration is read: the table
// looks a `Guarded` up for every route it mounts either way, so a guard `todo` chose to write
// would guard, and only the silence of a route nobody thought about goes unreported there.
//
// `CreateUser` is a second `DbApp` in this project, for making the first user, so the main class
// `run` means has to be stated: without this, `sbt "blog/run sync --apply"` would ask which one.
lazy val blog = (project in file("blog"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(
    name := "blog",
    libraryDependencies ++= Seq(
      edge("eezo"),
      edge("eezo-auth"),
      "org.scalameta" %% "munit" % "1.3.4" % Test,
      // `PostOwnershipSuite` drives the real route table over real rows, so it needs a real
      // Postgres: what it is asserting about is the SQL `JdbcStore.owned` narrows with, and an
      // in-memory substitute is the half `modules/http` already tests. Test scope, so nothing the
      // blog runs with carries it.
      "org.testcontainers" % "postgresql" % "1.21.3" % Test
    ),
    Compile / run / mainClass := Some("Main"),
    runInProjectDir
  )

// The tour app, on both edges: a model deriving `Table, Form, Resource`, an `AppSchema`, two
// handwritten routes, and its own Postgres schema. `todo/README.md` is a guided walk through the
// whole CLI on it.
lazy val todo = (project in file("todo"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(
    name := "todo",
    libraryDependencies += edge("eezo"),
    runInProjectDir
  )
