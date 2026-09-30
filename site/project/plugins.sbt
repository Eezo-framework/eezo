// The route table generator and the `eezo*` tasks the launcher forwards to (`eezoDev`,
// `eezoStage`). Resolved from the local ivy cache at the version `sbt publishLocalForExample`
// recorded, because eezo is not released yet; the site is an eezo application like any other.
addSbtPlugin("io.eezo" % "sbt-eezo" % EezoVersion.value)

// The same formatter and configuration as the framework, checked in CI.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
