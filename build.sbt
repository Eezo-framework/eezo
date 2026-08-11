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

// The framework's own types: configuration, errors, and what everything else builds on.
lazy val core = module("core")

// Jetty boot, request parsing, response writing, file-based routing.
lazy val http = module("http").dependsOn(core)

// Connection pool, the `sql` interpolator, transactions, migrations, DDL per dialect.
lazy val db = module("db")
  .dependsOn(core)
  .settings(libraryDependencies += "org.postgresql" % "postgresql" % "42.7.1")

// The node tree, the HTML DSL, the diff and patch protocol, the client runtime, PubSub.
lazy val live = module("live").dependsOn(core, http)

// The `derives` chain: DbCodec, Table, Form, Resource. It sits on everything it derives into.
lazy val derives = module("derives").dependsOn(core, db, http, live)

// Sessions, email and password, CSRF, route gating.
lazy val auth = module("auth").dependsOn(core, http, db)

// Booting a real server against a real database, and driving it over HTTP and WebSocket.
lazy val testkit = module("testkit").dependsOn(core, http, db, live)

// `eezo new`, `dev`, `routes`, `g`, `db`, `deploy`.
lazy val cli = module("cli").dependsOn(core, http, db, derives, live, auth)

// The root has no sources today, but it still carries commonSettings. Without it, any file
// dropped at the repo root would compile against whatever JVM launched sbt instead of the
// floor, which is the exact failure issue 38 calls Not negotiable downwards.
lazy val eezo = (project in file("."))
  .aggregate(core, http, db, live, derives, auth, testkit, cli)
  .settings(commonSettings)
  .settings(
    name           := "eezo",
    publish / skip := true
  )

lazy val example = project
  .in(file("modules/example"))
  .dependsOn(eezo)
  .settings(
    name := "eezo-example",
    scalacOptions += "-Xcheck-macros"
  )
