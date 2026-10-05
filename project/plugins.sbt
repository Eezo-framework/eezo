// Releasing is a git tag and nothing else: sbt-ci-release derives the version from the tag,
// signs the artifacts and pushes them to Sonatype Central. See .github/workflows/release.yml.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

// One formatter, one configuration, checked in CI so the tree cannot drift out of it.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

// One scaladoc site across the framework modules, for the API reference the docs site serves under
// `/api`. The sbt plugin and the demo are left out of it: see `unidocProjectFilter` in build.sbt.
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")
