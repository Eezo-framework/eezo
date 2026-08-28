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
// of with a `-release` error forty lines into a compile. `sbt.version` is checked for the same
// reason: it is read by the sbt launcher before any Scala code runs, so the examples build cannot get
// it from `Toolchain` the way it gets `ScalaVersion` and `JdkFloor`, and a drift there would
// otherwise go unnoticed.
Global / onLoad := {
  val root = (ThisBuild / baseDirectory).value
  (Global / onLoad).value.andThen { state =>
    Toolchain.assertJdk()
    Toolchain.assertExampleSbtVersionMatches(root)
    state
  }
}

// The db suite is deliberately two things at once. `check` is the regression signal and is
// expected to be green; `backlog` is the to-do list from design/backlog.md, written as tests
// that assert what eezo should do, and is expected to be red. A backlog test turns green by
// the bug being fixed, never by the assertion being weakened.
addCommandAlias("check", "db/testOnly -- --exclude-tags=backlog")
addCommandAlias("backlog", "db/testOnly io.eezo.db.BacklogSuite")

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
    "-Wvalue-discard"
    // The two warnings above are only worth setting if they can fail the build.
    // "-Werror" // TODO fix back
  ),
  javacOptions ++= Seq("--release", Toolchain.JdkFloor.toString),
  libraryDependencies += munit
)

/** A module of the framework, at `modules/<id>`, published as `eezo-<id>`. */
def module(id: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(commonSettings)
    .settings(name := s"eezo-$id")

// The framework's own types: configuration, the HTML node tree and DSL, the model key `Id[T]`, and
// what everything else builds on. `core` holds what has no dependencies of its own and at least two
// dependent modules, which is why `Id` is here: `db` stores one and `http` reads one out of a path
// and a form, and those two are siblings that never see each other. Not errors: the eezo exception
// set lives in `http`, so that `db`, which depends on `core` and never on `http`, cannot reach for
// an HTTP status.
lazy val core = module("core")

// Jetty boot, request parsing, response writing, file-based routing.
lazy val http = module("http")
  .dependsOn(core)
  .settings(libraryDependencies ++= Seq(jettyServer, jettyWsServer, jettyWsClient))

// Connection pool, the `sql` interpolator, transactions, migrations, DDL per dialect.
lazy val db = module("db")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(postgresql, testcontainersPg),
    // The database suite starts one container and shares it across suites (see
    // `support.Pg`). Forking per suite would start one container per JVM.
    Test / fork              := true,
    Test / parallelExecution := false
  )

// The diff and patch protocol, the client runtime, PubSub. The node tree and the HTML DSL are
// `core`'s, and `live` adds structural diffing on top of them.
lazy val live = module("live").dependsOn(core, http)

// Sessions, email and password, CSRF, route gating.
lazy val auth = module("auth").dependsOn(core, http, db)

// Booting a real server against a real database, and driving it over HTTP and WebSocket.
lazy val testkit = module("testkit").dependsOn(core, http, db, live)

// `eezo new`, `dev`, `routes`, `g`, `db`, `deploy`.
lazy val cli = module("cli").dependsOn(core, http, db, live, auth)

// The sbt plugin that generates the route table. It is published as `sbt-eezo` because sbt
// plugins are named that way, and it is cross-built for sbt 1 and sbt 2 from one source. See
// `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md` for why.
lazy val sbtEezo = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-eezo",
    // The build-level Scala 3.8.4 cannot build an sbt 1 plugin, so a plain `sbt compile`
    // would fail.
    scalaVersion       := Toolchain.PluginScalaVersion,
    crossScalaVersions := Seq(Toolchain.PluginScalaVersion, Toolchain.ScalaVersion),
    // Both compilers get a stated bytecode target on both axes, so that no half of a published jar
    // inherits one from whatever JDK happened to launch the build. The floors are sbt's own, Java 8
    // for sbt 1 and JDK 17 for sbt 2, not eezo's.
    scalacOptions := {
      val common = Seq("-deprecation", "-feature", "-unchecked")
      scalaBinaryVersion.value match {
        case "2.12" => common ++ Seq("-Xsource:3", "-release:8")
        // 3.8.4 already defaults to 17; stated so a compiler upgrade cannot move it in silence.
        case _ => common ++ Seq("-release:17")
      }
    },
    // `-Xlint:-options` is sbt 1 only: JDK 26 javac calls source and target 8 obsolete,
    // and 8 must stay.
    javacOptions := {
      scalaBinaryVersion.value match {
        case "2.12" => Seq("--release", "8", "-Xlint:-options")
        case _      => Seq("--release", "17")
      }
    },
    // munit, because `commonSettings` is not inherited here.
    libraryDependencies += munit,
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => Toolchain.Sbt1Floor
        case _      => Toolchain.Sbt2Version
      }
    },
    // The scripted tests are the only place the plugin is exercised as a plugin. `SbtPlugin`
    // brings the framework in for free; everything below is what it needs to reach eezo.
    //
    // `scriptedSbt` defaults to `pluginCrossBuild / sbtVersion`, which is the 1.5.8 floor on the
    // sbt 1 axis. An sbt that old cannot compile a Scala 3.8.4 application, which is what a
    // generated table is, so that axis runs on the sbt this repository itself runs on and the
    // floor stays a compile-against promise rather than a run-under one. The sbt 2 axis keeps the
    // default: overriding it there asks Maven Central for `scripted-sbt_3` at an sbt 1 version,
    // which does not exist, and no scripted test is written in sbt 2's dialect yet anyway.
    scriptedSbt := {
      scalaBinaryVersion.value match {
        case "2.12" => Toolchain.rootSbtVersion((ThisBuild / baseDirectory).value)
        case _      => (pluginCrossBuild / sbtVersion).value
      }
    },
    // A scripted test's build resolves eezo from the local ivy cache, exactly as `examples/` does.
    // It cannot read `Toolchain`, and the version is derived from the git state by sbt-dynver, so
    // all three travel as system properties instead of the `.eezo-version` file: scripted has a
    // channel for this and the examples build does not.
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      s"-Dplugin.version=${version.value}",
      s"-Deezo.scalaVersion=${Toolchain.ScalaVersion}",
      s"-Deezo.jdkFloor=${Toolchain.JdkFloor}"
    ),
    // `scriptedDependencies` defaults to publishing the plugin alone. A test that compiles what the
    // generator writes also needs `eezo-http` and, through it, `eezo-core`: the emitted file names
    // `io.eezo.http.Route`, `RouteTable` and `Resource`. Both are published even for the tests that
    // only read the generated text, because scripted has one dependency task for all of them.
    scriptedDependencies := {
      val publishedCore = (core / publishLocal).value
      val publishedHttp = (http / publishLocal).value
      val _             = (publishedCore, publishedHttp)
      scriptedDependencies.value
    }
  )

// The root has no sources today, but it still carries commonSettings. Without it, any file
// dropped at the repo root would compile against whatever JVM launched sbt instead of the
// floor, which is the exact failure issue 38 calls Not negotiable downwards.
lazy val eezo = (project in file("."))
  // `sbtEezo` is aggregated so that `ci-release`'s `+publishSigned` reaches it. See
  // `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md`.
  .aggregate(core, http, db, live, auth, testkit, cli, sbtEezo)
  .settings(commonSettings)
  .settings(
    name           := "eezo",
    publish / skip := true
  )

// What the examples build needs in order to resolve eezo from the local ivy cache, and to stay on
// the same Scala version and JDK floor as the rest of the build. The eezo version is derived from
// the git state by sbt-dynver, so it changes with every commit, and the Scala version and JDK
// floor live in `Toolchain`, which the example's separate build cannot read directly; none of the
// three can be written into the example's build by hand without drifting. `publishLocalForExample`
// publishes the modules and the plugin and then records all three where the example's build reads
// them, through `EezoVersion` in `examples/project/project`.
lazy val writeLocalVersion =
  taskKey[File]("Records the locally published version and toolchain for the examples build.")

writeLocalVersion := {
  val destination = (ThisBuild / baseDirectory).value / ".eezo-version"
  // key=value, not positional lines, so a reader that only recognises the current keys rejects a
  // file from an earlier format by name instead of misreading it by position.
  val contents = List(
    s"version=${version.value}",
    s"scalaVersion=${Toolchain.ScalaVersion}",
    s"jdkFloor=${Toolchain.JdkFloor}"
  )
  IO.writeLines(destination, contents)
  streams.value.log.info(s"eezo ${version.value} recorded in $destination")
  destination
}

addCommandAlias("publishLocalForExample", ";publishLocal;writeLocalVersion")

// The demo app: a real model, a real schema, and the `eezo db` CLI driving them. The
// framework's own correctness lives in `db`'s test suite, not here.
lazy val example = project
  .in(file("modules/example"))
  .dependsOn(db)
  .settings(
    name                         := "eezo-example",
    Compile / run / mainClass    := Some("example.Tour"),
    Compile / run / fork         := true,
    Compile / run / connectInput := true // required for freeze's prompts
  )
