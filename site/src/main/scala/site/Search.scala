package site

import java.util.regex.Pattern

import io.eezo.core.html.*

/** Search over the documentation, from the same Markdown the pages render.
  *
  * The index is every page cut into passages, one per heading, each holding the plain text under
  * it. A query is a set of terms, and a passage matches when every term appears in the page's
  * title, the passage's heading or its text. Hits are ranked by where the terms were found, a title
  * counting for more than a heading and a heading for more than the body, and each hit carries an
  * excerpt around the first term with every term marked. No stemming, no fuzziness: the
  * documentation is small enough that a substring match reads as the right answer.
  */
object Search {

  /** The text under one heading, or above the first. */
  final case class Passage(heading: Option[Markdown.Heading], text: String)

  final case class Entry(page: Article, passages: Vector[Passage])

  /** One result: a page, the section of it that matched, and the excerpt to show. */
  final case class Hit(
      page: Article,
      heading: Option[Markdown.Heading],
      excerpt: Html,
      score: Int
  ) {

    /** The page, at the section when there is one. */
    def href: String = heading.fold(page.path)(h => s"${page.path}#${h.id}")
  }

  /** How many results a query answers with, and how many of them one page may take. */
  val Limit: Int   = 10
  val PerPage: Int = 2

  final class Index(val entries: Vector[Entry]) {

    def query(raw: String): Vector[Hit] = {
      val terms = raw.toLowerCase.split("\\s+").filter(_.nonEmpty).distinct.toVector
      if (terms.isEmpty) Vector.empty
      else {
        val whole = raw.trim.toLowerCase
        val hits  = entries.flatMap { entry =>
          val title = entry.page.title.toLowerCase
          entry.passages.flatMap { passage =>
            val heading = passage.heading.fold("")(_.text.toLowerCase)
            val body    = passage.text.toLowerCase
            val matches =
              terms.forall(t => title.contains(t) || heading.contains(t) || body.contains(t))
            Option.when(matches) {
              val score = terms.map { t =>
                (if (title.contains(t)) 20 else 0) +
                  (if (heading.contains(t)) 10 else 0) +
                  math.min(occurrences(body, t), 5)
              }.sum + (if (title.contains(whole)) 30 else 0) +
                (if (heading.contains(whole)) 15 else 0)
              Hit(entry.page, passage.heading, excerpt(passage.text, terms), score)
            }
          }
        }
        hits
          .sortBy(hit => (-hit.score, hit.page.path))
          .foldLeft(Vector.empty[Hit]) { (kept, hit) =>
            if (kept.count(_.page == hit.page) >= PerPage) kept else kept :+ hit
          }
          .take(Limit)
      }
    }
  }

  /** How many times `term` appears in `text`, both already lower case. */
  private def occurrences(text: String, term: String): Int =
    Iterator
      .iterate(text.indexOf(term))(at => text.indexOf(term, at + term.length))
      .takeWhile(_ >= 0)
      .size

  /** The index over every written page. */
  def build(pages: Pages, content: Content): Index =
    new Index(pages.all.filterNot(_.draft).flatMap { page =>
      content.read(page.source).map(text => Entry(page, Markdown.passages(Markdown.parse(text))))
    })

  /** The index the site searches with: rebuilt per query under the dev loop, so an edit to a page
    * is searchable on the next keystroke, and built once in production.
    */
  def current: Index =
    if (sys.props.contains("site.root")) build(Pages.load(Content.current), Content.current)
    else cached

  private lazy val cached: Index = build(Pages.load(Content.current), Content.current)

  private val Mark: Tag = Tag("mark")

  /** How far the excerpt reaches before and after the first term, in characters. */
  private val Before: Int = 60
  private val Width: Int  = 190

  /** A window of the text around the first term, cut at word boundaries, with every term marked. */
  private def excerpt(text: String, terms: Vector[String]): Html = {
    val lower = text.toLowerCase
    val first = terms.map(lower.indexOf).filter(_ >= 0).minOption.getOrElse(0)
    val from  = {
      val rough = math.max(0, first - Before)
      if (rough == 0) 0
      else {
        val space = text.indexOf(' ', rough)
        if (space < 0 || space > first) rough else space + 1
      }
    }
    val to = {
      val rough = math.min(text.length, from + Width)
      if (rough == text.length) rough
      else {
        val space = text.lastIndexOf(' ', rough)
        if (space <= first) rough else space
      }
    }
    val window  = text.substring(from, to)
    val pattern = Pattern.compile(terms.map(Pattern.quote).mkString("|"), Pattern.CASE_INSENSITIVE)
    val matcher = pattern.matcher(window)
    val pieces  = Iterator
      .continually(matcher.find())
      .takeWhile(identity)
      .map(_ => (matcher.start, matcher.end))
      .toVector
    val (parts, last) = pieces.foldLeft((Vector.empty[Html], 0)) {
      case ((acc, cursor), (start, end)) =>
        val before =
          if (start > cursor) Vector(Html.text(window.substring(cursor, start))) else Vector.empty
        (acc ++ before :+ Mark(window.substring(start, end)), end)
    }
    val tail = if (last < window.length) Vector(Html.text(window.substring(last))) else Vector.empty
    val lead = if (from > 0) Vector(Html.text("…")) else Vector.empty
    val trail = if (to < text.length) Vector(Html.text("…")) else Vector.empty
    p(Attrs.cls := "search-excerpt", lead ++ parts ++ tail ++ trail)
  }
}
