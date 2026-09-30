package site

/** One page of the docs: where it is served, which file it renders, and how the navigation names
  * it.
  *
  * @param slug
  *   the address below `/docs`, empty for the overview at `/docs` itself
  * @param source
  *   the repository relative path of the Markdown file
  * @param title
  *   the name the navigation shows, which is the file's first heading unless a section says
  *   otherwise
  * @param section
  *   the section the page is listed under
  */
final case class Page(slug: String, source: String, title: String, section: String) {

  /** The absolute path the page answers on. A finished address, never mounted. */
  def path: String = if (slug.isEmpty) "/docs" else s"/docs/$slug"
}

final case class Section(name: String, pages: Vector[Page])

/** The whole tree, in navigation order, with the two lookups the site makes: by address when a
  * request arrives, and by source file when a Markdown link names another file.
  */
final class Pages(val sections: Vector[Section]) {

  val all: Vector[Page] = sections.flatMap(_.pages)

  val bySlug: Map[String, Page] = all.map(page => page.slug -> page).toMap

  val bySource: Map[String, Page] = all.map(page => page.source -> page).toMap

  /** The page before and the page after, in navigation order across sections. */
  def neighbours(page: Page): (Option[Page], Option[Page]) = {
    val index = all.indexOf(page)
    (all.lift(index - 1), all.lift(index + 1))
  }

  /** The `href` a link written in `from`'s Markdown resolves to.
    *
    * A link to another Markdown file in the repository becomes a link to that file's page, so the
    * cross references the authors wrote keep working here. A link to a repository file that is not
    * a page, `LICENSE` or a source file, points at that file on GitHub, which is what the reader
    * was promised. An absolute address and a fragment on the same page are left as written.
    */
  def resolve(from: Page, destination: String): String =
    if (Pages.isAbsolute(destination) || destination.startsWith("#")) destination
    else {
      val (path, fragment) = destination.indexOf('#') match {
        case -1 => (destination, "")
        case at => (destination.take(at), destination.drop(at))
      }
      val target = Pages.normalise(Pages.directoryOf(from.source), path)
      bySource.get(target) match {
        case Some(page) => page.path + fragment
        case None       => s"${Pages.Repository}/blob/main/$target$fragment"
      }
    }
}

object Pages {

  val Repository: String = "https://github.com/Eezo-framework/eezo"

  /** The tree, in the order the sidebar shows it. The fixed pages are named here with the title the
    * navigation shows; the two enumerated directories, the ADRs and the research notes, take
    * whatever files are there and their own first heading, so a new ADR is a new page with no
    * change here.
    */
  def load(content: Content): Pages = {
    def fixed(section: String)(entries: (String, String)*): Section =
      Section(
        section,
        entries.toVector.map { case (source, title) =>
          Page(slugOf(source), source, title, section)
        }
      )

    def enumerated(section: String, directory: String)(title: String => String): Section =
      Section(
        section,
        content.list(directory).map { source =>
          val heading = content.read(source).flatMap(firstHeading).getOrElse(source)
          Page(slugOf(source), source, title(heading), section)
        }
      )

    new Pages(
      Vector(
        fixed("Start")(
          "README.md"  -> "Overview",
          "CONTEXT.md" -> "Vocabulary"
        ),
        fixed("Guides")(
          "docs/deploying.md" -> "Deploying",
          "docs/live.md"      -> "Live pages",
          "docs/failures.md"  -> "Failures"
        ),
        fixed("Examples")(
          "examples/hello/README.md"     -> "hello: the http edge",
          "examples/reminders/README.md" -> "reminders: the database edge",
          "examples/blog/README.md"      -> "blog: both edges",
          "examples/todo/README.md"      -> "todo: the CLI tour"
        ),
        enumerated("Decisions", "docs/adr")(identity),
        Section(
          "Research",
          enumerated("Research", "research")(untagged).pages ++
            enumerated("Research", "docs/research")(untagged).pages
        )
      )
    )
  }

  /** A research note's heading without the `Research:` or `Design:` tag it opens with. */
  private def untagged(title: String): String =
    title.stripPrefix("Research:").stripPrefix("Design:").trim

  /** The address a source file is served under: the repository path without its extension, the two
    * root files given a name of their own, and an example's README named after the example.
    */
  def slugOf(source: String): String = source match {
    case "README.md"                    => ""
    case "CONTEXT.md"                   => "vocabulary"
    case s if s.startsWith("docs/")     => s.stripPrefix("docs/").stripSuffix(".md")
    case s if s.startsWith("examples/") => s.stripSuffix("/README.md")
    case s                              => s.stripSuffix(".md")
  }

  /** The text of the first `#` heading, with Markdown's code span ticks removed, for the tree. */
  def firstHeading(markdown: String): Option[String] =
    markdown.linesIterator
      .find(_.startsWith("# "))
      .map(_.drop(2).replace("`", "").trim)

  private def isAbsolute(destination: String): Boolean =
    destination.startsWith("http://") || destination.startsWith("https://") ||
      destination.startsWith("mailto:") || destination.startsWith("//")

  private def directoryOf(source: String): String =
    source.lastIndexOf('/') match {
      case -1 => ""
      case at => source.take(at)
    }

  /** `path` as written relative to `directory`, with `.` and `..` folded away, as a repository
    * relative path.
    */
  private def normalise(directory: String, path: String): String = {
    val segments = (directory.split('/').toVector ++ path.split('/').toVector).filter(_.nonEmpty)
    segments
      .foldLeft(Vector.empty[String]) {
        case (acc, ".")  => acc
        case (acc, "..") => acc.dropRight(1)
        case (acc, seg)  => acc :+ seg
      }
      .mkString("/")
  }
}
