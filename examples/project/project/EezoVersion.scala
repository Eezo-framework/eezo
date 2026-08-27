import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** The locally published eezo version and the toolchain it was built with, as
  * `sbt publishLocalForExample` recorded them at the root of the repository.
  *
  * None of the three can be a literal here. The version is derived from the git state by
  * sbt-dynver, so it changes with every commit. The Scala version and the JDK floor live in
  * `project/Toolchain.scala` in the main build, which this separate build cannot read directly, so
  * `publishLocalForExample` writes them out where this example can. The file is looked for upwards
  * from wherever sbt was launched, so the example works whether it is opened on its own or from
  * the repository root. The file is a `key=value` properties file, not positional lines, so that a
  * stale copy from before this format existed is rejected by name instead of misread by position.
  */
object EezoVersion {

  private def fail(reason: String): Nothing =
    sys.error(
      s"$reason Run `sbt publishLocalForExample` at the root of the eezo repository first: it " +
        "publishes eezo and sbt-eezo to the local ivy cache and records the version and toolchain " +
        "this example resolves."
    )

  private def search(directory: Path): Option[Path] =
    if (directory == null) None
    else {
      val candidate = directory.resolve(".eezo-version")
      if (Files.isRegularFile(candidate)) Some(candidate) else search(directory.getParent)
    }

  private val path: Path =
    search(Paths.get("").toAbsolutePath).getOrElse(fail("no .eezo-version found."))

  private val properties: Properties = {
    val loaded = new Properties()
    val in     = Files.newInputStream(path)
    try loaded.load(in)
    finally in.close()
    loaded
  }

  private def required(key: String): String =
    properties.getProperty(key) match {
      case null | "" =>
        fail(s"$path has no '$key' entry, so it predates this build or is corrupted.")
      case entry => entry
    }

  /** The published eezo version. */
  val value: String = required("version")

  /** `Toolchain.ScalaVersion` from the main build, carried across for `scalaVersion` here. */
  val scalaVersion: String = required("scalaVersion")

  /** `Toolchain.JdkFloor` from the main build, carried across for `-release` here. */
  val jdkFloor: String = required("jdkFloor")
}
