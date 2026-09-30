package site

import io.eezo.core.html.*
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response
import io.eezo.live.Live

/** A docs page: the article rendered from its Markdown, the section tree on the left and the page's
  * own outline on the right.
  */
object Docs {

  /** The page tree, read from the repository on every request under the dev loop so a new file
    * appears without a restart, and once for the life of the process when the content is the jar's,
    * where it cannot change.
    */
  def pages: Pages =
    if (sys.props.contains("site.root")) Pages.load(Content.current) else cachedPages

  private lazy val cachedPages: Pages = Pages.load(Content.current)

  /** The page at `slug`, or a 404 when there is none: the catch-all route matches every path under
    * `/docs`, so the refusal is this page's to raise.
    */
  def render(request: Request, slug: String): Response = {
    val tree = pages
    val page = tree.bySlug.getOrElse(slug.stripSuffix("/"), throw NotFound(s"/docs/$slug"))
    val text = Content.current.read(page.source).getOrElse(throw NotFound(page.path))
    val doc  = Markdown.parse(text)
    Response.Ok(document(request, tree, page, doc))
  }

  private def document(request: Request, tree: Pages, page: Page, doc: Markdown.Doc): Html = {
    val title = doc.title.getOrElse(page.title)
    val meta  = Layout.Meta(
      title = title,
      description = doc.description.getOrElse(s"$title, from the eezo documentation."),
      path = page.path
    )
    val (previous, next) = tree.neighbours(page)

    Layout.page(request, meta, "docs")(
      div(
        Attrs.cls := "docs-shell",
        // The tree and its drawer are one live component, mounted where the left column goes.
        Live.mount(request, new Drawer(tree, page)),
        main(
          Attrs.id  := "content",
          Attrs.cls := "docs-main",
          article(
            Attrs.cls := "prose",
            p(Attrs.cls := "crumbs", span(Attrs.cls := "chip", page.section), sourceLink(page)),
            Markdown.render(doc, tree.resolve(page, _)),
            pager(previous, next)
          )
        ),
        outline(doc.headings)
      )
    )
  }

  private def sourceLink(page: Page): Html =
    a(
      Attrs.cls  := "source-link",
      Attrs.href := s"${Pages.Repository}/blob/main/${page.source}",
      Attrs.rel  := "noopener",
      "Edit on GitHub"
    )

  private def outline(headings: Vector[Markdown.Heading]): Html = {
    val listed = headings.filter(h => h.level == 2 || h.level == 3)
    aside(
      Attrs.cls := "outline",
      Html.when(listed.nonEmpty)(
        nav(
          Attrs.attr("aria-label") := "On this page",
          h2("On this page"),
          ul(
            listed.map(h =>
              li(Attrs.cls := s"level-${h.level}", a(Attrs.href := s"#${h.id}", h.text))
            )
          )
        )
      )
    )
  }

  private def pager(previous: Option[Page], next: Option[Page]): Html =
    nav(
      Attrs.cls                := "pager",
      Attrs.attr("aria-label") := "Previous and next page",
      previous.map(p =>
        a(Attrs.cls := "pager-prev", Attrs.href := p.path, small("Previous"), span(p.title))
      ),
      next.map(p =>
        a(Attrs.cls := "pager-next", Attrs.href := p.path, small("Next"), span(p.title))
      )
    )
}
