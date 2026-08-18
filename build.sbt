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
  // `sbtEezo` is aggregated so that `ci-release`'s `+publishSigned` reaches it. See
  // `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md`.
  .aggregate(core, http, db, live, derives, auth, testkit, cli, sbtEezo)
  .settings(commonSettings)
  .settings(
    name           := "eezo",
    publish / skip := true
  )
