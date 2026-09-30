package site

/** One page of the docs: where it is served, which file it renders, and how the navigation names
  * it.
  *
  * @param slug
  *   the address below `/docs`
  * @param source
  *   the repository relative path of the Markdown file
  * @param title
  *   the name the navigation shows, which is the file's first heading unless a section says
  *   otherwise
  * @param section
  *   the section the page is listed under
  * @param group
  *   a sub-heading within the section, for the pages that arrive as a set: the examples, the
  *   decisions, the research notes
  * @param draft
  *   whether the file is a placeholder still to be written, which it says with a `draft` marker
  */
final case class Page(
    slug: String,
    source: String,
    title: String,
    section: String,
    group: Option[String] = None,
    draft: Boolean = false
) {

  /** The absolute path the page answers on. A finished address, never mounted. */
  def path: String = s"/docs/$slug"
}

/** A section of the tree. The four pillars have a slug and an index page of their own at
  * `/docs/<slug>`; the opening section has neither.
  */
final case class Section(
    name: String,
    slug: Option[String],
    description: String,
    pages: Vector[Page]
) {

  def path: Option[String] = slug.map(s => s"/docs/$s")

  /** The pages in order, cut where the group changes, so a sidebar or an index can head each run
    * once.
    */
  def grouped: Vector[(Option[String], Vector[Page])] =
    pages.foldLeft(Vector.empty[(Option[String], Vector[Page])]) {
      case (acc :+ ((group, run)), page) if group == page.group => acc :+ (group, run :+ page)
      case (acc, page)                                          => acc :+ (page.group, Vector(page))
    }

  def drafts: Int = pages.count(_.draft)
}

/** The whole tree, in navigation order, with the lookups the site makes: by address when a request
  * arrives, and by source file when a Markdown link names another file.
  */
final class Pages(val sections: Vector[Section]) {

  val all: Vector[Page] = sections.flatMap(_.pages)

  /** The four pillars: the sections with an index page. */
  val pillars: Vector[Section] = sections.filter(_.slug.isDefined)

  val bySlug: Map[String, Page] = all.map(page => page.slug -> page).toMap

  val bySource: Map[String, Page] = all.map(page => page.source -> page).toMap

  def pillar(slug: String): Option[Section] = pillars.find(_.slug.contains(slug))

  /** Whether an absolute path is one this tree answers: the hub, a pillar's index, or a page. The
    * generated API under `/api` is not the tree's; the asset route answers it.
    */
  def knows(path: String): Boolean =
    path == "/docs" || pillars.exists(_.path.contains(path)) ||
      bySlug.contains(path.stripPrefix("/docs/"))

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

  /** The marker a placeholder carries on its first line. Invisible on the page, since Markdown
    * passes an HTML comment through, and what turns the draft banner on.
    */
  val DraftMarker: String = "<!-- draft -->"

  /** The tree, in the order the sidebar shows it: the overview, then the four pillars.
    *
    * Each pillar is a directory under `docs/`. The pages named in its list come first, in that
    * order, because a tutorial has an order and a reference has a conventional one; anything else
    * in the directory follows alphabetically, so a new file is a page before anyone lists it. The
    * example READMEs are grouped at the end of the tutorials, and the vocabulary closes the
    * reference. The ADRs and the research notes under `docs/adr`, `docs/research` and `research`
    * are not pages: they are the repository's own record, and a link to one goes to GitHub.
    */
  def load(content: Content): Pages = {
    def read(source: String): String = content.read(source).getOrElse("")

    def page(source: String, section: String, title: Option[String], group: Option[String]) = {
      val text = read(source)
      Page(
        slugOf(source),
        source,
        title.orElse(firstHeading(text)).getOrElse(source),
        section,
        group,
        text.startsWith(DraftMarker)
      )
    }

    /** The files under `directory`, the named ones first in that order, the rest alphabetically. */
    def ordered(
        directory: String,
        section: String,
        first: Vector[String],
        group: Option[String] = None
    )(title: String => String = identity): Vector[Page] = {
      val present = content.list(directory)
      val named   = first.map(name => s"$directory/$name.md").filter(present.contains)
      (named ++ present.filterNot(named.contains)).map { source =>
        val p = page(source, section, None, group)
        p.copy(title = title(p.title))
      }
    }

    def fixed(section: String, group: Option[String] = None)(
        entries: (String, String)*
    ): Vector[Page] =
      entries.toVector.map { case (source, title) => page(source, section, Some(title), group) }

    new Pages(
      Vector(
        Section("Start", None, "", fixed("Start")("README.md" -> "Overview")),
        Section(
          "Tutorials",
          Some("tutorials"),
          "Lessons that take you by the hand through building something, start to finish. " +
            "Read them in order the first time.",
          ordered(
            "docs/tutorials",
            "Tutorials",
            Vector("getting-started", "first-model", "sign-in", "deploy-to-fly", "a-live-page")
          )() ++
            fixed("Tutorials", Some("The example applications"))(
              "examples/hello/README.md"     -> "hello: the http edge",
              "examples/reminders/README.md" -> "reminders: the database edge",
              "examples/blog/README.md"      -> "blog: both edges",
              "examples/todo/README.md"      -> "todo: the CLI tour"
            )
        ),
        Section(
          "How-to guides",
          Some("how-to"),
          "Recipes for a task you already know you want done: the steps, the code, and nothing " +
            "else.",
          ordered(
            "docs/how-to",
            "How-to guides",
            Vector(
              "create-a-crud-app",
              "write-a-handwritten-route",
              "take-over-a-derived-route",
              "mount-under-a-prefix",
              "evolve-the-schema",
              "configure-the-database",
              "add-authentication",
              "add-a-live-component",
              "share-state-between-pages",
              "call-an-external-api",
              "handle-failures",
              "test-an-application",
              "run-a-job",
              "use-logback",
              "deploy"
            )
          )()
        ),
        Section(
          "Explanation",
          Some("explanation"),
          "How the parts work and why they are shaped the way they are, at reading pace.",
          ordered(
            "docs/explanation",
            "Explanation",
            Vector(
              "the-case-class",
              "edges",
              "routing",
              "urls-and-mounts",
              "requests-and-responses",
              "sessions-and-csrf",
              "guards-and-ownership",
              "the-database-edge",
              "schema-and-migrations",
              "the-live-layer",
              "failures",
              "the-dev-loop",
              "the-server",
              "deployment"
            )
          )()
        ),
        Section(
          "Reference",
          Some("reference"),
          "The API, generated from the sources, and the things scaladoc cannot say: commands, " +
            "settings, conventions and file formats, stated once.",
          ordered(
            "docs/reference",
            "Reference",
            Vector(
              "cli",
              "sbt-plugin",
              "configuration",
              "routing",
              "derivations",
              "migrations",
              "live-protocol",
              "deploy-files"
            )
          )() ++
            fixed("Reference")("CONTEXT.md" -> "Vocabulary")
        )
      )
    )
  }

  /** The address a source file is served under: the repository path without its extension, the two
    * root files given a name of their own, and an example's README named after the example.
    */
  def slugOf(source: String): String = source match {
    case "README.md"                    => "overview"
    case "CONTEXT.md"                   => "reference/vocabulary"
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
