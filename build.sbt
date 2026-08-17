import Dependencies._

// eezo ships as one version. No module carries its own, and no module is released alone. The
// version itself is not set here: sbt-ci-release derives it from the git tag through sbt-dynver,
// so a release is a tag and nothing else. The fields below are what Maven Central requires in the
// POM of every artifact.
inThisBuild(
  List(
    organization  := "io.eezo",
    scalaVersion  := Toolchain.ScalaVersion,
    versionScheme := Some("early-semver"),
    homepage      := Some(url("https://eezo.io")),
    licenses      := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    developers    := List(
      Developer(
        "rcardin",
        "Riccardo Cardin",
        "riccardo DOT cardin AT gmail.com",
        url("https://github.com/rcardin")
      ),
      Developer(
        "daniel-ciocirlan",
        "Daniel Ciocîrlan",
        "",
        url("https://github.com/daniel-ciocirlan")
      )
    )
  )
)

// The JDK floor is checked when the build loads, so that a wrong JVM fails with a reason instead
// of with a `-release` error forty lines into a compile.
Global / onLoad := (Global / onLoad).value.andThen { state =>
  Toolchain.assertJdk()
  state
}

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-release",
    Toolchain.JdkFloor.toString,
    "-deprecation",
    "-feature",
    "-unchecked",
    // Braces, not significant indentation. The framework is read as much as it is written, and
    // this is the syntax the project commits to; `-no-indent` makes it a compile error to drift.
    "-no-indent",
    "-Wunused:all",
    "-Wvalue-discard",
    // The two warnings above are only worth setting if they can fail the build.
    "-Werror"
  ),
  javacOptions ++= Seq("--release", Toolchain.JdkFloor.toString),
  libraryDependencies += munit
)

/** A module of the framework, at `modules/<id>`, published as `eezo-<id>`. */
def module(id: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(commonSettings)
    .settings(name := s"eezo-$id")

// The framework's own types: configuration, the HTML node tree and DSL, and what everything else
// builds on. Not errors: the eezo exception set lives in `http`, so that `db`, which depends on
// `core` and never on `http`, cannot reach for an HTTP status.
lazy val core = module("core")

// Jetty boot, request parsing, response writing, file-based routing.
lazy val http = module("http").dependsOn(core)

// Connection pool, the `sql` interpolator, transactions, migrations, DDL per dialect.
lazy val db = module("db").dependsOn(core)

// The diff and patch protocol, the client runtime, PubSub. The node tree and the HTML DSL are
// `core`'s, and `live` adds structural diffing on top of them.
lazy val live = module("live").dependsOn(core, http)

// The `derives` chain: DbCodec, Table, Form, Resource. It sits on everything it derives into.
lazy val derives = module("derives").dependsOn(core, db, http, live)

// Sessions, email and password, CSRF, route gating.
lazy val auth = module("auth").dependsOn(core, http, db)

// Booting a real server against a real database, and driving it over HTTP and WebSocket.
lazy val testkit = module("testkit").dependsOn(core, http, db, live)

// `eezo new`, `dev`, `routes`, `g`, `db`, `deploy`.
lazy val cli = module("cli").dependsOn(core, http, db, derives, live, auth)

// The sbt plugin that generates the route table, published as `sbt-eezo` rather than `eezo-*`
// because sbt plugins are named that way and because it is not a module of the framework: it
// depends on sbt and the standard library alone, never on eezo's own modules. The sbt 1 axis
// physically cannot see eezo's Scala 3 artifacts, and the sbt 2 axis must not, or eezo's Scala
// version would be welded to sbt's and Jetty would land on the build classpath.
//
// It is cross-built for sbt 1 and sbt 2 from one source, which is why it does not inherit
// `commonSettings`: `-no-indent`, `-Wvalue-discard` and `-release 25` are not a valid Scala 2.12
// flag set. `-Xsource:3` on the 2.12 axis is what makes the single source possible at all: it
// teaches 2.12 to accept Scala 3 syntax. It does not do the reverse. Scala 2 constructs that
// 3.8.4 rejects, procedure syntax and `forSome` among them, still compile without a warning on
// the 2.12 axis, and constructs both axes accept with different meanings, such as leading infix,
// diverge in silence. The shared subset is held by compiling both axes, in CI, not by the flag.
// The two axes publish as `sbt-eezo_2.12_1.0` and `sbt-eezo_sbt2_3`, two different artifactIds,
// so dropping either later breaks nothing that already resolves.
//
// The research behind all of the above is not committed to this repository. It is
// `research/sbt-plugin.md` on the unmerged `research/sbt-plugin` branch, which
// `git show origin/research/sbt-plugin:research/sbt-plugin.md` prints. Issue 108 owns that
// research and the decisions it settled.
lazy val sbtEezo = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-eezo",
    // The build-level `scalaVersion` is eezo's own 3.8.4, which an sbt 1 plugin cannot be built
    // with. Stated here so that a plain `sbt compile` builds the sbt 1 axis rather than failing.
    scalaVersion       := Toolchain.PluginScalaVersion,
    crossScalaVersions := Seq(Toolchain.PluginScalaVersion, Toolchain.ScalaVersion),
    // Both compilers get a stated bytecode target on both axes, so that no half of a published jar
    // inherits one from whatever JDK happened to launch the build.
    //
    // The targets are deliberately not eezo's own `Toolchain.JdkFloor` of 25. That floor is about
    // the JVM an eezo server runs on, and virtual threads are what justify it; a plugin runs inside
    // sbt at build time and touches none of that. What binds this module is the JVM sbt loads the
    // plugin into, and somebody may well build an eezo application on JDK 25 with an sbt that
    // starts on something older. The floors are sbt's own published ones: the 1.x manual requires
    // Java 8, the 2.x setup page requires JDK 17. Aiming above either publishes a plugin the sbt
    // it targets cannot load.
    scalacOptions := {
      val common = Seq("-deprecation", "-feature", "-unchecked")
      scalaBinaryVersion.value match {
        case "2.12" => common ++ Seq("-Xsource:3", "-release:8")
        // The sbt 2 axis carried no `-release` at all. Scala 3.8.4 defaults to 17, so the output
        // was already right by accident, and a compiler upgrade that moved the default would have
        // changed it in silence. 17 is also the lowest `-release` 3.8.4 accepts, so sbt 2's
        // requirement and the compiler's floor coincide with no slack in either direction.
        case _ => common ++ Seq("-release:17")
      }
    },
    // Not inherited from `commonSettings` either, so without this a Java source here would compile
    // against the launching JDK, 25 on CI and 26 on a maintainer's machine, and be unloadable for
    // the sbt users it is published for. There are no `.java` files yet; this is the floor going in
    // ahead of the first one rather than a repair.
    //
    // `-Xlint:-options` is on the sbt 1 axis alone because JDK 26's javac calls source and target 8
    // obsolete three times per compile, and 8 is not negotiable for as long as sbt 1 is supported.
    // The sbt 2 axis keeps reporting option problems.
    javacOptions := {
      scalaBinaryVersion.value match {
        case "2.12" => Seq("--release", "8", "-Xlint:-options")
        case _      => Seq("--release", "17")
      }
    },
    // `+publishSigned`, which `ci-release` already runs, resolves each project's own
    // `crossScalaVersions`, so this publishes twice while the eight framework modules publish
    // once. The sbt version follows the Scala version, because that pairing is what sbt 1 and
    // sbt 2 respectively are.
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => Toolchain.Sbt1Floor
        case _      => Toolchain.Sbt2Version
      }
    }
  )

// The root has no sources today, but it still carries commonSettings. Without it, any file
// dropped at the repo root would compile against whatever JVM launched sbt instead of the
// floor, which is the exact failure issue 38 calls Not negotiable downwards.
lazy val eezo = (project in file("."))
  // `sbtEezo` is aggregated by choice. What forces the question is that `ci-release` runs
  // `+publishSigned` on the root, and an unaggregated project is never published, so leaving the
  // plugin out of this list would release nothing for it. There is an escape hatch:
  // `sbt-ci-release` reads the `CI_RELEASE` environment variable and uses its value verbatim as
  // the publish command, so the release workflow could name `sbtEezo/publishSigned` itself and
  // keep the plugin unaggregated. Aggregating wins anyway, because it leaves one publish path
  // that the build describes, instead of a command living in a workflow file's environment where
  // it can silently drift from the module list here. The cost is that a plain `sbt compile` also
  // builds the plugin on Scala 2.12, and the plugin is small enough for that to be worth paying.
  .aggregate(core, http, db, live, derives, auth, testkit, cli, sbtEezo)
  .settings(commonSettings)
  .settings(
    name           := "eezo",
    publish / skip := true
  )
