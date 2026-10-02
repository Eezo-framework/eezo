package site

import io.eezo.core.html.*
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response

/** The documentation: the hub at `/docs`, an index per pillar, and the pages themselves, each with
  * the tree on the left and its own outline on the right.
  */
object Docs {

  /** The page tree, read from the repository on every request under the dev loop so a new file
    * appears without a restart, and once for the life of the process when the content is the jar's,
    * where it cannot change.
    */
  def pages: Pages =
    if (sys.props.contains("site.root")) Pages.load(Content.current) else cachedPages

  private lazy val cachedPages: Pages = Pages.load(Content.current)

  /** What `/docs/<slug>` answers: the hub for an empty slug, a pillar's index for a pillar's slug,
    * the page otherwise, and a 404 when there is none. The catch-all route matches every path under
    * `/docs`, so the refusal is this page's to raise.
    */
  def render(request: Request, slug: String): Response = {
    val tree  = pages
    val clean = slug.stripSuffix("/")
    if (clean.isEmpty) Response.Ok(hub(request, tree))
    else
      tree.pillar(clean) match {
        case Some(pillar) => Response.Ok(index(request, tree, pillar))
        case None         =>
          val page = tree.bySlug.getOrElse(clean, throw NotFound(s"/docs/$slug"))
          val text = Content.current.read(page.source).getOrElse(throw NotFound(page.path))
          Response.Ok(document(request, tree, page, Markdown.parse(text)))
      }
  }

  /** The chrome every docs address shares: the drawer on the left, the article in the middle, and
    * whatever the right column holds.
    */
  private def shell(
      request: Request,
      tree: Pages,
      current: Option[Page],
      meta: Layout.Meta,
      initial: Chrome.State = Chrome.State.initial
  )(
      article: Html,
      right: Html
  ): Html =
    Layout.page(request, meta, "docs", initial)(state =>
      div(
        Attrs.cls := "docs-shell",
        // The tree and its drawer are a slice of the page's one live component, placed where the
        // left column goes; the article is built once, above, and only compared from then on.
        Drawer.render(state.drawer, tree, current),
        main(Attrs.id := "content", Attrs.cls := "docs-main", article),
        right
      )
    )

  /** `/search`: the hub, with the search dialog open. */
  def searching(request: Request): Html = hub(request, pages, Chrome.State.searching)

  /** `/docs`: the four pillars, and the way into each. */
  private def hub(
      request: Request,
      tree: Pages,
      initial: Chrome.State = Chrome.State.initial
  ): Html = {
    val meta = Layout.Meta(
      "Documentation",
      "The eezo documentation: tutorials, how-to guides, explanation and reference.",
      "/docs"
    )
    shell(request, tree, None, meta, initial)(
      Tags.article(
        Attrs.cls := "prose hub",
        h1("Documentation"),
        p(
          Attrs.cls := "lede",
          "Four kinds of page, for four kinds of question. ",
          "New here? Read the ",
          a(Attrs.href := "/docs/overview", "overview"),
          " first, then start with a tutorial."
        ),
        ul(
          Attrs.cls := "pillar-cards",
          tree.pillars.map { section =>
            li(
              Attrs.cls := s"pillar pillar-${section.slug.getOrElse("")}",
              a(
                Attrs.href := section.path.getOrElse("/docs"),
                h2(section.name),
                p(section.description),
                small(counts(section))
              )
            )
          }
        )
      ),
      aside(Attrs.cls := "outline")
    )
  }

  /** `/docs/<pillar>`: every page of one pillar, with what each is about. */
  private def index(request: Request, tree: Pages, pillar: Section): Html = {
    val meta = Layout.Meta(pillar.name, pillar.description, pillar.path.getOrElse("/docs"))
    shell(request, tree, None, meta)(
      Tags.article(
        Attrs.cls := "prose pillar-index",
        p(Attrs.cls := "crumbs", span(Attrs.cls := "chip", "Documentation")),
        h1(pillar.name),
        p(Attrs.cls := "lede", pillar.description),
        Html.when(pillar.slug.contains("reference"))(api),
        pillar.grouped.map { case (group, pages) =>
          Html.Fragment(
            group.map(name => h2(name)).toVector :+
              ul(
                Attrs.cls := "page-list",
                pages.map { page =>
                  li(
                    if (page.draft) Seq(Attrs.cls := "draft") else Nil,
                    a(Attrs.href := page.path, page.title),
                    Html.when(page.draft)(span(Attrs.cls := "chip chip-draft", "draft")),
                    summary(page).map(text => p(text))
                  )
                }
              )
          )
        }
      ),
      aside(Attrs.cls := "outline")
    )
  }

  /** The modules an application depends on, each a door into the generated API. */
  private val Modules: Vector[(String, String, String)] = Vector(
    ("io.eezo", "eezo", "The umbrella: EezoApp, both edges."),
    ("io.eezo.core", "eezo-core", "The HTML tree and DSL, Id, Store."),
    ("io.eezo.http", "eezo-http", "Request, Response, routes, Form, Resource, sessions, CSRF."),
    ("io.eezo.db", "eezo-db", "The connection, sql, transactions, Table, Schema, migrations."),
    ("io.eezo.live", "eezo-live", "Component, Live, Topic, the patch protocol."),
    ("io.eezo.auth", "eezo-auth", "Guard, Password, ownership.")
  )

  private def api: Html =
    Html.Fragment(
      Vector(
        h2("The API"),
        p(
          "Every public type and member, generated from the sources' scaladoc on each build, ",
          "with a search box and a link from every signature to its source. ",
          a(Attrs.cls := "btn btn-small", Attrs.href := "/api/", "Browse the API")
        ),
        ul(
          Attrs.cls := "module-cards",
          Modules.map { case (pkg, artifact, blurb) =>
            li(
              a(
                Attrs.href := s"/api/${pkg.replace('.', '/')}.html",
                code(pkg),
                span(Attrs.cls := "chip chip-quiet", artifact),
                p(blurb)
              )
            )
          }
        ),
        h2("Everything else")
      )
    )

  private def counts(section: Section): String = {
    val pages  = s"${section.pages.size} pages"
    val drafts = section.drafts
    if (drafts == 0) pages else s"$pages, $drafts still to write"
  }

  /** A page's first paragraph, for the index. Parsed here rather than at load, so that listing a
    * tree costs nothing and only the pillar being shown is read whole.
    */
  private def summary(page: Page): Option[String] =
    Content.current.read(page.source).flatMap(text => Markdown.parse(text).description)

  private[site] def document(request: Request, tree: Pages, page: Page, doc: Markdown.Doc): Html = {
    val title = doc.title.getOrElse(page.title)
    val meta  = Layout.Meta(
      title = title,
      description = doc.description.getOrElse(s"$title, from the eezo documentation."),
      path = page.path
    )
    val (previous, next) = tree.neighbours(page)
    val section          = tree.sections.find(_.name == page.section)

    shell(request, tree, Some(page), meta)(
      Tags.article(
        Attrs.cls := "prose",
        p(
          Attrs.cls := "crumbs",
          span(
            section.flatMap(_.path) match {
              case Some(path) => a(Attrs.cls := "chip chip-link", Attrs.href := path, page.section)
              case None       => span(Attrs.cls := "chip", page.section)
            },
            page.group.map(group => span(Attrs.cls := "chip chip-quiet", group))
          ),
          sourceLink(page)
        ),
        Html.when(doc.isDraft)(
          div(
            Attrs.cls := "draft-banner",
            strong("Draft. "),
            "This page is an outline of what it will cover. The content is not written yet; ",
            a(Attrs.href := editUrl(page), Attrs.rel := "noopener", "write it on GitHub"),
            "."
          )
        ),
        Markdown.render(doc, tree.resolve(page, _)),
        pager(previous, next)
      ),
      outline(doc.headings)
    )
  }

  private def editUrl(page: Page): String = s"${Pages.Repository}/blob/main/${page.source}"

  private def sourceLink(page: Page): Html =
    a(
      Attrs.cls  := "source-link",
      Attrs.href := editUrl(page),
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
