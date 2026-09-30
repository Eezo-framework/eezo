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
// expected to be green; `backlog` is the list of open work from design/backlog.md, written as tests
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

// The framework's own types: configuration, the HTML node tree and DSL, the model key `Id[T]`, and
// what everything else builds on. `core` holds what has no dependencies of its own and at least two
// dependent modules, which is why `Id` is here: `db` stores one and `http` reads one out of a path
// and a form, and those two are siblings that never see each other. Not errors: the eezo exception
// set lives in `http`, so that `db`, which depends on `core` and never on `http`, cannot reach for
// an HTTP status.
lazy val core = module("core")

// Jetty boot, request parsing, response writing, file based routing.
lazy val http = module("http")
  .dependsOn(core % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(jettyServer, jettyWsServer, slf4jJdk14, jettyWsClient))

// Connection pool, the `sql` interpolator, transactions, migrations, DDL per dialect.
lazy val db = module("db")
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(postgresql, hikari, slf4jJdk14, testcontainersPg),
    // DESIGN §8.8. `Tx^` and `?->` do not parse without this, so it is a build setting rather than
    // a preference. research/capture-checking.md §6.3 measured that a capture checked library
    // requires nothing of downstream and gives downstream nothing: the guarantee is real inside
    // this module and inside any consumer that opts in, and absent, silently, everywhere else.
    scalacOptions += "-language:experimental.captureChecking",
    // The database suite starts one container and shares it across suites (see
    // `support.Pg`). Forking per suite would start one container per JVM.
    Test / fork              := true,
    Test / parallelExecution := false
  )

// The diff and patch protocol, the client runtime, PubSub. The node tree and the HTML DSL are
// `core`'s, and `live` adds structural diffing on top of them.
lazy val live = module("live")
  .dependsOn(core, http)
  // Jetty's WebSocket client, for the integration suite that drives the live socket for real.
  .settings(libraryDependencies += jettyWsClient)

// Sessions, email and password, CSRF, route gating.
//
// `db` is deliberately absent. A guard reads the session and calls back into functions the
// application supplies, so nothing here opens a connection or knows a table exists; the one place
// `Password` meets storage is the `Column[Password]` an application writes beside its own model,
// where both modules are already visible. Depending on `db` would put a driver on the classpath of
// every application that has a login page and no database.
lazy val auth = module("auth")
  .dependsOn(core, http)
  // Jetty's WebSocket client is test scoped, so it does not arrive with `http`: `GuardSuite` boots
  // a server and reads a guarded handshake's refusal off the wire, the same artifact `http` and
  // `live` drive their own sockets with.
  .settings(libraryDependencies ++= Seq(springCrypto, jettyWsClient))

// Booting a real server against a real database, and driving it over HTTP and WebSocket.
lazy val testkit = module("testkit").dependsOn(core, http, db, live)

// The umbrella, and the default artifact an application depends on: `"io.eezo" %% "eezo"`. It
// exists so that `Main.scala` is one dependency, one import, one trait (`io.eezo.EezoApp`), which
// is what design/objective.md's 30 minute benchmark asks of the first file a user writes. The two
// edges are artifacts of their own: `eezo-http` carries `HttpApp` and `eezo-db` carries `DbApp`, so
// an application that has only one edge depends on that edge alone and the other edge's derivations
// are not on its classpath. `EezoApp` stacks the two entry traits, and the drift page the database
// edge contributes to `dev` lives here because it is the one thing that needs both. `test->test` on
// `core`, here and on the two edges, is what lets every entry trait suite share `core`'s
// `Captured`; on `live` and `auth` it is what lets `BoundLivePageSuite`, the one place a real
// guard, a real session and a real live socket meet, share `live`'s socket rig and `auth`'s planted
// sign in rather than spell either a second time.
lazy val eezo = (project in file("modules/eezo"))
  .dependsOn(
    core % "compile->compile;test->test",
    http,
    db,
    live % "compile->compile;test->test",
    auth % "compile->compile;test->test"
  )
  .settings(commonSettings)
  .settings(name := "eezo")
  // Jetty's WebSocket client, stated rather than inherited: `BoundLivePageSuite` drives a real
  // upgrade of its own, so the umbrella asks for the artifact instead of resting on whatever
  // `live`'s test classpath happens to carry.
  .settings(libraryDependencies += jettyWsClient)

// The sbt plugin that generates the route table. It is published as `sbt-eezo` because sbt
// plugins are named that way, and it is cross built for sbt 1 and sbt 2 from one source. See
// `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md` for why.
lazy val sbtEezo = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-eezo",
    // The build level Scala 3.8.4 cannot build an sbt 1 plugin, so a plain `sbt compile`
    // would fail.
    scalaVersion       := Toolchain.PluginScalaVersion,
    crossScalaVersions := Seq(Toolchain.PluginScalaVersion, Toolchain.ScalaVersion),
    // Both compilers get a stated bytecode target on both axes, so that no half of a published jar
    // inherits one from whatever JDK happened to launch the build. The floors are sbt's own, Java 8
    // for sbt 1 and JDK 17 for sbt 2, not eezo's.
    scalacOptions := {
      val common = Seq("-deprecation", "-feature", "-unchecked")
      scalaBinaryVersion.value match {
        // No `-Ywarn-value-discard` here: it rejects the `expr: Unit` ascription that
        // DevProcess.scala uses to satisfy the Scala 3 value discard check, so value discard is
        // checked on the Scala 3 axis only.
        case "2.12" =>
          common ++ Seq("-Xsource:3", "-release:8", "-Xfatal-warnings", "-Ywarn-unused")
        // 3.8.4 already defaults to 17; stated so a compiler upgrade cannot move it in silence.
        case _ => common ++ Seq("-release:17", "-Werror", "-Wunused:all", "-Wvalue-discard")
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
    }
  )

// The root has no sources today, but it still carries commonSettings. Without it, any file
// dropped at the repo root would compile against whatever JVM launched sbt instead of the
// floor, which is the exact failure issue 38 calls Not negotiable downwards.
lazy val root = (project in file("."))
  // `sbtEezo` is aggregated so that `ci-release`'s `+publishSigned` reaches it. See
  // `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md`.
  .aggregate(core, http, db, live, auth, testkit, eezo, sbtEezo)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(commonSettings)
  .settings(
    // The name `eezo` belongs to the published umbrella module above; the root is the unpublished
    // aggregate.
    name           := "eezo-root",
    publish / skip := true,
    // The API reference: one scaladoc over the published Scala 3 modules, written to
    // `target/unidoc`, which `site/` copies into its jar and serves under `/api`. The sbt plugin
    // is a 2.12 cross build and the demo is not an API, so neither is documented here.
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(core, http, db, live, auth, testkit, eezo),
    ScalaUnidoc / unidoc / target              := baseDirectory.value / "target" / "unidoc",
    ScalaUnidoc / unidoc / scalacOptions := Seq(
      "-project",
      "eezo",
      "-project-version",
      version.value,
      "-project-footer",
      "eezo is released under the MIT License.",
      "-social-links:github::https://github.com/Eezo-framework/eezo",
      s"-source-links:github://Eezo-framework/eezo/main",
      "-doc-root-content",
      (baseDirectory.value / "site" / "api-root.md").getPath,
      "-skip-by-regex:io\\.eezo\\..*\\.internal.*",
      "-external-mappings:.*java.*::javadoc::https://docs.oracle.com/en/java/javase/25/docs/api/"
    )
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
  .dependsOn(eezo)
  .settings(
    name := "eezo-example",
    libraryDependencies += testcontainersPg,
    Compile / run / mainClass := Some("example.Cli"),
    Compile / run / fork      := true,
    // The Tour lives in `src/test` because it starts its own Postgres through testcontainers, and
    // that is a test scoped dependency. It is still a program, not a suite:
    //   sbt "example/Test/runMain example.Tour"            (TOUR_NOPAUSE=1 to run straight through)
    // An environment variable, because `Test / fork` starts a child JVM that inherits the environment
    // but not a `-D` property given to the sbt launcher.
    // `connectInput` is what lets its pauses and `freeze`'s prompts read stdin.
    Test / fork                  := true,
    Compile / run / connectInput := true,
    Test / run / connectInput    := true
  )
