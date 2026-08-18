import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The locally published eezo version, as `sbt publishLocalForExample` recorded it at the root of
  * the repository.
  *
  * The version cannot be a literal here: sbt-dynver derives it from the git state, so it changes
  * with every commit. The file is looked for upwards from wherever sbt was launched, so the
  * example works whether it is opened on its own or from the repository root.
  */
object EezoVersion {

  val value: String = {
    def search(directory: Path): Option[Path] =
      if (directory == null) None
      else {
        val candidate = directory.resolve(".eezo-version")
        if (Files.isRegularFile(candidate)) Some(candidate) else search(directory.getParent)
      }

    search(Paths.get("").toAbsolutePath) match {
      case Some(path) => new String(Files.readAllBytes(path), "UTF-8").trim
      case None       =>
        sys.error(
          "no .eezo-version found. Run `sbt publishLocalForExample` at the root of the eezo " +
            "repository first: it publishes eezo and sbt-eezo to the local ivy cache and records " +
            "the version this example resolves."
        )
    }
  }
}
