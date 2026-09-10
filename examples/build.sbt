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

// Jetty logs through SLF4J; routing it to java.util.logging puts it on the same backend as eezo's
// own `System.Logger`, and silences SLF4J's no-provider warning at boot. Only an example with the
// http edge carries Jetty, so only those carry this.
lazy val jettyLogging = "org.slf4j" % "slf4j-jdk14" % "2.0.16" % Runtime

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
    libraryDependencies ++= Seq(edge("eezo-http"), jettyLogging)
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
lazy val blog = (project in file("blog"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(
    name := "blog",
    libraryDependencies ++= Seq(edge("eezo"), jettyLogging),
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
    libraryDependencies ++= Seq(edge("eezo"), jettyLogging),
    runInProjectDir
  )
