// The half of the plugin that no unit test can reach: whether what the generator writes actually
// compiles against a published eezo.
//
// `RouteGeneratorSuite` pins the emitted text and `eezo/generator-cache` pins its stability, and
// both of them are happy with a table that names a type eezo does not have. Nothing in this
// repository catches that, because a plugin defined in a build cannot be enabled by that same
// build, and until this test existed the only thing that did was `examples/`, by hand, outside CI.
//
// eezo is resolved from the local ivy cache the way `examples/` resolves it. The version and the
// toolchain travel as system properties from `scriptedLaunchOpts`, since this build cannot read
// `Toolchain` and sbt-dynver moves the version with every commit.

lazy val root = (project in file("."))
  .enablePlugins(EezoPlugin)
  .settings(
    scalaVersion := sys.props("eezo.scalaVersion"),
    scalacOptions ++= Seq("-release", sys.props("eezo.jdkFloor"), "-no-indent"),
    libraryDependencies += "io.eezo" %% "eezo-http" % sys.props("plugin.version")
  )

val assertTableMounts = taskKey[Unit]("Fails unless the compiled table serves what was declared.")

// Run rather than read: the point of this test is the compiler's opinion of the generated file,
// and after that the table's own, so the assertion calls `Routes.table()` in the compiled
// application rather than grepping the source it came from.
assertTableMounts := (Compile / runMain).toTask(" Check").value
