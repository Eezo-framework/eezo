// The documentation site, https://eezo.io: an eezo application that serves the repository's own
// Markdown. It is a build of its own for the reason the examples are: a plugin defined in a build
// cannot be enabled by that same build, and the site is built, run and deployed the way any
// application on eezo is, through `sbt-eezo` and `bin/eezo`. It resolves eezo from the local ivy
// cache at the version `sbt publishLocalForExample` recorded, until eezo is released.
//
// The site has the http edge only, with the live layer on it. Its content is files, not rows, so
// there is no database, no schema and no migration: `eezo deploy` ships the jars and nothing else.

name         := "eezo-site"
scalaVersion := EezoVersion.scalaVersion

enablePlugins(EezoPlugin)

libraryDependencies ++= Seq(
  "io.eezo" %% "eezo-http" % EezoVersion.value,
  // The live layer: the navigation drawer and the front page's demo are components, so the site
  // ships no script of its own, only the framework's client.
  "io.eezo" %% "eezo-live" % EezoVersion.value,
  // CommonMark, parsed to an AST the site walks into eezo's own `Html` nodes. The one third party
  // dependency of the site, and one the framework does not take: two small jars, no transitive
  // dependency, BSD licensed.
  "org.commonmark"  % "commonmark"                % "0.30.0",
  "org.commonmark"  % "commonmark-ext-gfm-tables" % "0.30.0",
  "org.scalameta"  %% "munit"                     % "1.3.4" % Test
)

scalacOptions ++= Seq(
  "-release",
  EezoVersion.jdkFloor,
  "-deprecation",
  "-feature",
  "-unchecked",
  "-no-indent",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)

// eezo needs JDK 25 to run (virtual threads without pinning), so the application forks, and the
// forked output passes straight through so the boot print is not prefixed by sbt's logger.
run / fork           := true
run / outputStrategy := Some(OutputStrategy.StdoutOutput)

/** The repository root, one level up. The site's pages are the Markdown files that live there. */
lazy val repoRoot = settingKey[File]("The eezo repository root, whose Markdown the site serves.")
repoRoot := baseDirectory.value.getParentFile

// In development the site reads its pages from the repository on every request, so an edit to a
// page shows on the next refresh with no restart: `site.root` names where. Neither the deployed
// image nor a plain `sbt run` elsewhere carries the property, and the site then reads the same
// files from its own jar, where the generator below put them. `eezoDev` forks with these options
// too, because the plugin inherits `Compile / run / javaOptions`.
Compile / run / javaOptions += s"-Dsite.root=${repoRoot.value}"
Compile / run / forkOptions := (Compile / run / forkOptions).value
  .withWorkingDirectory(baseDirectory.value)

Test / fork := true
Test / javaOptions += s"-Dsite.root=${repoRoot.value}"

/** The Markdown the site serves, copied into the jar under `content/<repository path>`, and an
  * index of those paths beside it, because a jar cannot list a directory. The set is spelled here
  * once: the site's `Pages` names sections out of these files, and its test suite fails on a file
  * here that no section reaches. The ADRs and the research notes are deliberately absent: they
  * are the repository's own record and stay there.
  */
Compile / resourceGenerators += Def.task {
  val root = repoRoot.value
  val out  = (Compile / resourceManaged).value / "content"

  def under(directory: String, file: File): String =
    directory + "/" + IO.relativize(root / directory, file).getOrElse(sys.error(s"$file is not under $directory"))

  val files: Seq[(File, String)] =
    Seq(root / "README.md" -> "README.md", root / "CONTEXT.md" -> "CONTEXT.md") ++
      (root / "docs" ** "*.md").get().map(f => f -> under("docs", f)).filterNot { case (_, rel) =>
        rel.startsWith("docs/adr/") || rel.startsWith("docs/research/")
      } ++
      (root / "examples" * DirectoryFilter / "README.md").get().map(f => f -> under("examples", f))

  IO.delete(out)
  val copied = files.map { case (source, relative) =>
    val destination = out / relative
    IO.copyFile(source, destination)
    destination
  }
  val index = out / "index.txt"
  IO.writeLines(index, files.map(_._2).sorted)
  copied :+ index
}.taskValue

/** The API reference: the scaladoc `sbt unidoc` wrote at the root of the repository, copied into
  * the jar under `api/` and served under `/api`. Generated at the root rather than here because
  * the sources are there and this build sees only the published jars. Absent when `unidoc` has
  * not run, which the site tolerates in development and the test suite refuses in CI.
  */
Compile / resourceGenerators += Def.task {
  val source = repoRoot.value / "target" / "unidoc"
  val out    = (Compile / resourceManaged).value / "api"
  IO.delete(out)
  if (!source.isDirectory) {
    streams.value.log.warn(s"no API docs at $source: run `sbt unidoc` at the repository root")
    Seq.empty[File]
  } else {
    IO.copyDirectory(source, out)
    (out ** "*").get().filter(_.isFile)
  }
}.taskValue

/** What the site says about the eezo it was built against, generated so that it cannot drift from
  * the version the build resolved.
  */
Compile / sourceGenerators += Def.task {
  val file = (Compile / sourceManaged).value / "site" / "SiteInfo.scala"
  IO.write(
    file,
    s"""package site
       |
       |/** Generated by the site's build. Do not edit. */
       |object SiteInfo {
       |  val eezoVersion: String = "${EezoVersion.value}"
       |}
       |""".stripMargin
  )
  Seq(file)
}.taskValue
