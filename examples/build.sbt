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
  libraryDependencies += "io.eezo" %% "eezo-http" % EezoVersion.value,
  // eezo needs JDK 25 to run: JEP 491, which removed virtual-thread pinning on `synchronized`,
  // landed in JDK 24, and the server design depends on it.
  run / fork := true
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
