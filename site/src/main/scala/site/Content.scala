package site

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

/** Where the site's Markdown comes from: the repository on disk, or the copy of it inside the jar.
  *
  * Every path is repository relative, `docs/live.md`, so a page names its source the same way in
  * both places, and a link from one file to another resolves against the same tree the author
  * linked in. [[list]] exists because the page tree enumerates two directories rather than naming
  * every file: the ADRs and the research notes are whatever is there.
  */
trait Content {

  /** The file's text, or nothing when there is no such file. */
  def read(path: String): Option[String]

  /** The Markdown files directly under a directory, as repository relative paths, sorted. */
  def list(directory: String): Vector[String]
}

object Content {

  /** The repository at `root`, read on every call, which is what lets a page edit show on the next
    * refresh under the dev loop.
    */
  def disk(root: Path): Content = new Content {

    def read(path: String): Option[String] = {
      val file = root.resolve(path)
      if (Files.isRegularFile(file)) Some(Files.readString(file, StandardCharsets.UTF_8))
      else None
    }

    def list(directory: String): Vector[String] = {
      val dir = root.resolve(directory)
      if (!Files.isDirectory(dir)) Vector.empty
      else {
        val stream = Files.list(dir)
        try
          stream.iterator.asScala
            .filter(f => Files.isRegularFile(f) && f.getFileName.toString.endsWith(".md"))
            .map(f => s"$directory/${f.getFileName}")
            .toVector
            .sorted
        finally stream.close()
      }
    }
  }

  /** The copy the build put under `content/` in the jar, listed through the index it wrote there.
    */
  def classpath: Content = new Content {

    private def resource(path: String): Option[String] =
      Option(getClass.getClassLoader.getResourceAsStream(s"content/$path")).map { in =>
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      }

    private val index: Vector[String] =
      resource("index.txt").map(_.linesIterator.filter(_.nonEmpty).toVector).getOrElse(Vector.empty)

    def read(path: String): Option[String] = resource(path)

    def list(directory: String): Vector[String] =
      index.filter { path =>
        path.startsWith(s"$directory/") && !path.drop(directory.length + 1).contains('/') &&
        path.endsWith(".md")
      }.sorted
  }

  /** The content the running site serves: the repository when `site.root` names it, which the build
    * sets for `run`, `eezoDev` and `test`, and the jar's own copy otherwise, which is what a
    * deployed image has.
    */
  lazy val current: Content =
    sys.props.get("site.root").map(root => disk(Path.of(root))).getOrElse(classpath)
}
