// The route table generator. `sbt-eezo` is resolved from the local ivy cache, at the version
// `sbt publishLocalForExample` recorded, because eezo is not released yet.
addSbtPlugin("io.eezo" % "sbt-eezo" % EezoVersion.value)

// The same formatter and configuration as the framework, checked in CI: `scalafmtConfig` in
// build.sbt points at the root `.scalafmt.conf`.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
