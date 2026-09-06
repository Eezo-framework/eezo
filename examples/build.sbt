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
  // The umbrella: `io.eezo.EezoApp` plus everything it dispatches to. One dependency and one
  // import is the point — see the comment on the `eezo` module in the main build.
  libraryDependencies += "io.eezo" %% "eezo" % EezoVersion.value,
  // Jetty logs through SLF4J; routing it to java.util.logging puts it on the same backend as
  // eezo's own `System.Logger`, and silences SLF4J's no-provider warning at boot.
  libraryDependencies += "org.slf4j" % "slf4j-jdk14" % "2.0.16" % Runtime,
  // eezo needs JDK 25 to run: JEP 491, which removed virtual-thread pinning on `synchronized`,
  // landed in JDK 24, and the server design depends on it.
  run / fork := true,
  // JUL's ConsoleHandler writes to stderr, which sbt's default strategy labels [error]; passing
  // the forked output straight through keeps the boot print unprefixed.
  run / outputStrategy := Some(OutputStrategy.StdoutOutput)
)

// Skeleton one: one handwritten route, no database, no derivation.
lazy val hello = (project in file("hello"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(name := "hello")

// Skeleton two: one model, `derives Form, Resource`, seven CRUD routes in a browser over the
// in-memory store the generated table mints.
lazy val blog = (project in file("blog"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(name := "blog")

// The tour app: a model deriving `Table, Form, Resource`, an `AppSchema`, two handwritten routes,
// and its own Postgres schema. `todo/README.md` is a guided walk through the whole CLI on it.
lazy val todo = (project in file("todo"))
  .enablePlugins(EezoPlugin)
  .settings(commonSettings)
  .settings(
    name := "todo",
    // `freeze` writes `db/migrations` and `db/schema.json` against the working directory; pin the
    // forked run to the project dir so the terminal commands and the dev loop write one place.
    Compile / run / forkOptions := (Compile / run / forkOptions).value
      .withWorkingDirectory(baseDirectory.value)
  )
