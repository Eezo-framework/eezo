package site

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.live.Live

/** The chrome every page shares: the document, the header, the footer. */
object Layout {

  /** What a page says about itself in its `<head>`. */
  final case class Meta(title: String, description: String, path: String)

  val SiteName: String = "eezo"

  /** Where the site lives, for the addresses a social card needs to be absolute. */
  val Origin: String = "https://eezo.io"

  /** A whole document around `content`, which is a function of the live state the page shares with
    * its chrome: see [[Page]]. The request is what the header needs: the theme this browser chose,
    * and the token its toggle posts with.
    */
  def page(
      request: Request,
      meta: Meta,
      bodyClass: String,
      initial: Page.State = Page.State.initial
  )(content: Page.State => Html): Html =
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
        Tags.meta(Attrs.attr("property") := "og:url", Attrs.content         := Origin + meta.path),
        Tags.meta(
          Attrs.attr("property") := "og:image",
          Attrs.content          := Origin + Assets.url("og-image.png")
        ),
        Tags.meta(Attrs.attr("property") := "og:image:width", Attrs.content  := "1200"),
        Tags.meta(Attrs.attr("property") := "og:image:height", Attrs.content := "630"),
        Tags.meta(
          Attrs.attr("property") := "og:image:alt",
          Attrs.content          := "The eezo mascot beside the words: a Scala 3 web framework, the data model is the source of truth."
        ),
        Tags.meta(Attrs.name := "twitter:card", Attrs.content := "summary_large_image"),
        link(
          Attrs.rel           := "icon",
          Attrs.tpe           := "image/png",
          Attrs.attr("sizes") := "32x32",
          Attrs.href          := Assets.url("favicon-32.png")
        ),
        link(Attrs.rel := "apple-touch-icon", Attrs.href := Assets.url("apple-touch-icon.png")),
        link(Attrs.rel := "stylesheet", Attrs.href       := Assets.url("site.css"))
      ),
      body(
        Attrs.cls := bodyClass,
        Live.mount(
          request,
          new Page(meta.path, Theme.toggle(request), content, () => Search.current, initial)
        ),
        // highlight.js colours the code blocks after the page is parsed: the library, the two
        // grammars its bundle leaves out, and the one call.
        script(Attrs.src := Assets.url("hljs/highlight.min.js")),
        script(Attrs.src := Assets.url("hljs/scala.min.js")),
        script(Attrs.src := Assets.url("hljs/nginx.min.js")),
        script(Html.raw("hljs.highlightAll()")),
        // The keyboard: the one thing the live layer cannot hear. Ctrl-K or Cmd-K presses the
        // search button, Escape presses its close button, and the input is focused once the
        // dialog has been patched in; everything else about the search is the component's.
        script(Attrs.src := Assets.url("keys.js"))
      )
    )

  /** The mascot and the wordmark beside it. The image is served three times the size it is shown
    * at, so it stays crisp on a dense screen; its dimensions are on the tag so the header does not
    * shift while it loads. `mods` is for a host that needs another attribute on the anchor, as the
    * API pages do.
    */
  def logo(mods: Attr*): Html =
    a(
      Attrs.cls                := "logo",
      Attrs.href               := "/",
      Attrs.attr("aria-label") := "eezo home",
      img(
        Attrs.cls    := "mark",
        Attrs.src    := Assets.url("mascot.png"),
        Attrs.alt    := "",
        Attrs.width  := 38,
        Attrs.height := 36
      ),
      span(Attrs.cls := "wordmark", "eezo"),
      mods
    )

  /** The top bar, rendered inside [[Page]] so the search button can open the dialog. */
  private[site] def header(path: String, theme: Html): Html = {
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
        searchButton,
        theme
      )
    )
  }

  /** The way into the search dialog, with the shortcut beside it. Both spellings of the shortcut
    * are rendered and the stylesheet shows the one for the keyboard at hand.
    */
  private val searchButton: Html =
    button(
      Attrs.cls                 := "search-button",
      Attrs.tpe                 := "button",
      Attrs.attr("aria-label")  := "Search the documentation",
      Attrs.data("search-open") := "",
      Live.onClick("search-open"),
      Icons.search,
      span(Attrs.cls     := "search-label", "Search"),
      Page.Kbd(Attrs.cls := "shortcut mac", "⌘K"),
      Page.Kbd(Attrs.cls := "shortcut other", "Ctrl K")
    )

  private[site] val footer: Html =
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
