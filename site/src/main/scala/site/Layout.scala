package site

import io.eezo.core.html.*
import io.eezo.http.Request

/** The chrome every page shares: the document, the header, the footer. */
object Layout {

  /** What a page says about itself in its `<head>`. */
  final case class Meta(title: String, description: String, path: String)

  val SiteName: String = "eezo"

  /** A whole document around `content`. The request is what the header needs: the theme this
    * browser chose, and the token its toggle posts with.
    */
  def page(request: Request, meta: Meta, bodyClass: String)(content: Html): Html =
    Html.doctype ++ html(
      Attrs.lang := "en",
      Theme.current(request).map(Attrs.data("theme") := _),
      head(
        Tags.meta(Attrs.charset := "utf-8"),
        Tags.meta(Attrs.name := "viewport", Attrs.content := "width=device-width, initial-scale=1"),
        Tags.title(
          if (meta.path == "/") s"$SiteName · a Scala 3 web framework"
          else s"${meta.title} · $SiteName"
        ),
        Tags.meta(Attrs.name             := "description", Attrs.content    := meta.description),
        Tags.meta(Attrs.name             := "theme-color", Attrs.content    := "#ffd84d"),
        Tags.meta(Attrs.attr("property") := "og:title", Attrs.content       := meta.title),
        Tags.meta(Attrs.attr("property") := "og:description", Attrs.content := meta.description),
        Tags.meta(Attrs.attr("property") := "og:type", Attrs.content        := "website"),
        Tags.meta(Attrs.attr("property") := "og:site_name", Attrs.content   := SiteName),
        link(
          Attrs.rel  := "icon",
          Attrs.tpe  := "image/svg+xml",
          Attrs.href := Assets.url("favicon.svg")
        ),
        link(Attrs.rel := "stylesheet", Attrs.href := Assets.url("site.css"))
      ),
      body(
        Attrs.cls := bodyClass,
        a(Attrs.cls := "skip", Attrs.href := "#content", "Skip to content"),
        header(request, meta.path),
        content,
        footer
      )
    )

  /** The mark: a rounded tile with a wink of an `e`, and the wordmark beside it. Inline, so it
    * takes the page's colours and needs no request of its own. `mods` is for a host that needs
    * another attribute on the anchor, as the API pages do.
    */
  def logo(mods: Attr*): Html =
    a(
      Attrs.cls                := "logo",
      Attrs.href               := "/",
      Attrs.attr("aria-label") := "eezo home",
      Html.raw(LogoMark),
      span(Attrs.cls := "wordmark", "eezo"),
      mods
    )

  private val LogoMark: String =
    """<svg class="mark" viewBox="0 0 40 40" width="36" height="36" aria-hidden="true">
      |<rect x="2" y="2" width="36" height="36" rx="11" fill="var(--sun)"/>
      |<circle cx="20" cy="20" r="9.5" fill="none" stroke="var(--ink-fixed)" stroke-width="4.5"/>
      |<rect x="19" y="18" width="14" height="4.5" rx="2" fill="var(--sun)"/>
      |<rect x="20" y="18" width="12" height="4.5" rx="2" fill="var(--ink-fixed)"/>
      |</svg>""".stripMargin.replace("\n", "")

  private def header(request: Request, path: String): Html = {
    def item(href: String, label: String, active: Boolean): Html =
      li(
        a(Attrs.href := href, if (active) Seq(Attrs.attr("aria-current") := "page") else Nil, label)
      )

    Tags.header(
      Attrs.cls := "topbar",
      div(
        Attrs.cls := "topbar-inner",
        logo(),
        nav(
          Attrs.attr("aria-label") := "Site",
          ul(
            item("/docs", "Docs", path.startsWith("/docs") && !path.startsWith("/docs/examples")),
            item("/docs/examples/hello", "Examples", path.startsWith("/docs/examples")),
            li(a(Attrs.href := Pages.Repository, Attrs.rel := "noopener", "GitHub"))
          )
        ),
        Theme.toggle(request)
      )
    )
  }

  private def footer: Html =
    Tags.footer(
      Attrs.cls := "site-footer",
      div(
        Attrs.cls := "footer-inner",
        p(
          strong("eezo"),
          " is released under the ",
          a(
            Attrs.href := s"${Pages.Repository}/blob/main/LICENSE",
            Attrs.rel  := "noopener",
            "MIT License"
          ),
          ". Built with eezo, of course."
        ),
        p(
          Attrs.cls := "footer-meta",
          span(Attrs.cls := "chip chip-sun", "pre-release"),
          span(Attrs.cls := "version", Attrs.title := SiteInfo.eezoVersion, "eezo ", shortVersion),
          " · ",
          a(Attrs.href := Pages.Repository, Attrs.rel := "noopener", "Source on GitHub")
        )
      )
    )

  /** `0.0.0+68-06488d5a+20260917-0725-SNAPSHOT` reads as `0.0.0+68-06488d5a` in a footer. */
  private def shortVersion: String =
    SiteInfo.eezoVersion.split('+').take(2).mkString("+")
}
