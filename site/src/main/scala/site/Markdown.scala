package site

import io.eezo.core.html.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Markdown, parsed once and walked into eezo's own `Html` nodes.
  *
  * The walk rather than the library's HTML renderer and a `Html.raw` around its output, because the
  * page then stays one tree from the header to the footer: headings get their anchors, code blocks
  * their language class and links their resolution in the same place everything else on the page is
  * built, and the outline on the right is read off the same headings the article renders.
  */
object Markdown {

  /** A heading the outline lists, with the id the article gave it. */
  final case class Heading(level: Int, id: String, text: String)

  /** A parsed page: the tree, its headings in order, and the id each heading node was assigned, so
    * that the outline and the article cannot disagree about an anchor.
    */
  final class Doc private[Markdown] (
      private[Markdown] val root: Node,
      val headings: Vector[Heading],
      private[Markdown] val ids: Map[Node, String],
      draft: Boolean
  ) {

    /** The page's own title: its first level one heading. */
    def title: Option[String] = headings.find(_.level == 1).map(_.text)

    /** Whether the source opened with the draft marker: a placeholder whose outline is all there
      * is.
      */
    val isDraft: Boolean = draft

    /** The first paragraph, plain, for the page's description. */
    def description: Option[String] =
      children(root).collectFirst { case p: Paragraph => plain(p) }.map(clip(_, 160))
  }

  private val parser: Parser =
    Parser.builder().extensions(java.util.List.of(TablesExtension.create())).build()

  def parse(markdown: String): Doc = {
    val root         = parser.parse(markdown)
    val headingNodes = descendants(root).collect { case h: org.commonmark.node.Heading => h }
    // Ids in document order, a repeated title numbered from its second occurrence, the way every
    // Markdown host does it, so a link written against GitHub's anchors lands here too.
    val (ids, _) = headingNodes.foldLeft((Vector.empty[(Node, String)], Map.empty[String, Int])) {
      case ((assigned, seen), heading) =>
        val base  = slug(plain(heading))
        val count = seen.getOrElse(base, 0)
        val id    = if (count == 0) base else s"$base-$count"
        (assigned :+ (heading -> id), seen.updated(base, count + 1))
    }
    val byNode   = ids.toMap
    val headings = headingNodes.map(h => Heading(h.getLevel, byNode(h), plain(h)))
    new Doc(root, headings, byNode, markdown.startsWith(Pages.DraftMarker))
  }

  /** The article. `link` says where a link's destination goes, which is the page's question rather
    * than the parser's.
    */
  def render(doc: Doc, link: String => String): Html = node(doc.root, doc, link, tight = false)

  private def node(n: Node, doc: Doc, link: String => String, tight: Boolean): Html = {
    def inner(parent: Node, tightList: Boolean = tight): Html =
      children(parent).foldLeft(Html.empty)((acc, child) =>
        acc ++ node(child, doc, link, tightList)
      )

    n match {
      case d: Document => inner(d)

      case h: org.commonmark.node.Heading =>
        val id  = doc.ids(h)
        val tag = Vector(h1, h2, h3, h4, h5, h6)(h.getLevel - 1)
        tag(
          Attrs.id := id,
          inner(h),
          a(
            Attrs.cls                := "anchor",
            Attrs.href               := s"#$id",
            Attrs.attr("aria-label") := "Link to this section",
            "#"
          )
        )

      case p: Paragraph => if (tight) inner(p) else Tags.p(inner(p))

      case t: Text           => Html.text(t.getLiteral)
      case e: Emphasis       => em(inner(e))
      case s: StrongEmphasis => strong(inner(s))
      case c: Code           => code(c.getLiteral)

      case f: FencedCodeBlock   => CodeBlock.render(Option(f.getInfo).getOrElse(""), f.getLiteral)
      case i: IndentedCodeBlock => CodeBlock.render("", i.getLiteral)

      case l: Link =>
        val href     = link(l.getDestination)
        val external = href.startsWith("http://") || href.startsWith("https://")
        a(
          Attrs.href := href,
          Option(l.getTitle).map(Attrs.title := _),
          if (external) Seq(Attrs.rel := "noopener") else Nil,
          inner(l)
        )

      case i: Image =>
        img(
          Attrs.src := link(i.getDestination),
          Attrs.alt := plain(i),
          Option(i.getTitle).map(Attrs.title := _)
        )

      case b: BulletList  => ul(inner(b, b.isTight))
      case o: OrderedList =>
        val start = Option(o.getMarkerStartNumber).map(_.intValue).filter(_ != 1)
        ol(start.map(Attrs.attr("start") := _), inner(o, o.isTight))
      case item: ListItem => li(inner(item))

      case b: BlockQuote              => blockquote(inner(b, tightList = false))
      case _: ThematicBreak           => hr()
      case _: HardLineBreak           => br()
      case _: SoftLineBreak           => Html.text("\n")
      case h: HtmlBlock               => Html.raw(h.getLiteral)
      case h: HtmlInline              => Html.raw(h.getLiteral)
      case _: LinkReferenceDefinition => Html.empty

      case t: TableBlock => div(Attrs.cls := "table-scroll", table(inner(t)))
      // A table opened with an empty header row, `| | |`, is a table with no header at all.
      case h: TableHead => if (plain(h).trim.isEmpty) Html.empty else thead(inner(h))
      case b: TableBody => tbody(inner(b))
      case r: TableRow  => tr(inner(r))
      case c: TableCell =>
        val align =
          Option(c.getAlignment).map(a => Attrs.style := s"text-align:${a.name.toLowerCase}")
        if (c.isHeader) th(align, inner(c)) else td(align, inner(c))

      case other => inner(other)
    }
  }

  /** The page cut at its headings: the text above the first, then the text under each. The level
    * one heading is the page's title and opens no passage of its own. Code blocks count as text,
    * because a name in one is what a search is most often for.
    */
  def passages(doc: Doc): Vector[Search.Passage] = {
    def text(n: Node): String =
      (n +: descendants(n)).collect {
        case t: Text              => t.getLiteral
        case c: Code              => c.getLiteral
        case f: FencedCodeBlock   => f.getLiteral
        case i: IndentedCodeBlock => i.getLiteral
        case _: Paragraph         => " "
        case _: ListItem          => " "
        case _: TableCell         => " "
        case _: SoftLineBreak     => " "
        case _: HardLineBreak     => " "
      }.mkString
    val cut = children(doc.root).foldLeft(Vector.empty[(Option[Heading], Vector[String])]) {
      case (acc, h: org.commonmark.node.Heading) =>
        val heading = Option.when(h.getLevel > 1)(Heading(h.getLevel, doc.ids(h), plain(h)))
        acc :+ (heading, Vector.empty)
      case (init :+ ((heading, texts)), block) => init :+ (heading, texts :+ text(block))
      case (_, block)                          => Vector((None, Vector(text(block))))
    }
    cut
      .map { case (heading, texts) =>
        Search.Passage(heading, texts.mkString(" ").replaceAll("\\s+", " ").trim)
      }
      .filter(passage => passage.text.nonEmpty || passage.heading.isDefined)
  }

  private def children(n: Node): Vector[Node] =
    Iterator.iterate(n.getFirstChild)(_.getNext).takeWhile(_ != null).toVector

  private def descendants(n: Node): Vector[Node] =
    children(n).flatMap(child => child +: descendants(child))

  /** A node's text with every mark stripped: what a heading is called and what an id is made of. */
  private def plain(n: Node): String =
    (n +: descendants(n)).collect {
      case t: Text          => t.getLiteral
      case c: Code          => c.getLiteral
      case _: SoftLineBreak => " "
      case _: HardLineBreak => " "
    }.mkString

  /** GitHub's anchor rule, near enough: lower case, punctuation dropped, spaces to hyphens. */
  def slug(text: String): String =
    text.toLowerCase
      .map(c => if (c.isLetterOrDigit || c == ' ' || c == '-' || c == '_') c else ' ')
      .trim
      .replaceAll("\\s+", "-")

  private def clip(text: String, max: Int): String = {
    val flat = text.replaceAll("\\s+", " ").trim
    if (flat.length <= max) flat
    else flat.take(max).reverse.dropWhile(c => !c.isWhitespace).reverse.trim + "…"
  }

}
