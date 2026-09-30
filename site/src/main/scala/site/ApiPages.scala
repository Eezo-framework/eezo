package site

import io.eezo.core.html.*
import io.eezo.http.Request

/** The generated API pages, dressed in the site's chrome as they are served.
  *
  * Scaladoc writes a whole site of its own, with its own header, fonts and theme switch. The pages
  * are served as scaladoc wrote them, since its scripts expect its markup, and three things are
  * added on the way out: the site's stylesheet for these pages, which restyles scaladoc's own
  * variables; the site's logo and navigation in scaladoc's header, in place of the project name;
  * and the site's theme, so that a reader who chose dark on the docs reads the API dark too.
  * Everything here is a string edit anchored on markup scaladoc has kept stable across releases,
  * and each edit is a no-op on a page that lacks its anchor.
  */
object ApiPages {

  private val Logo =
    """<a href="[^"]*" class="logo-container">\s*<span class="project-name h300">[^<]*</span>\s*</a>""".r

  private val ThemeScript = """<script type="text/javascript" src="([^"]*scripts/theme\.js)">""".r

  /** scaladoc's script takes over every same-origin link on the page and loads its target as if it
    * were another scaladoc page, which a docs page is not. It offers no opt-out, but listeners fire
    * in the order they were added, and an attribute handler is added when the element is parsed,
    * before the deferred script runs: stopping there leaves the browser to follow the link.
    */
  private val Plain: Attr = Attrs.attr("onclick") := "event.stopImmediatePropagation()"

  def dress(page: String, request: Request): String = {
    val stylesheet =
      s"""<link rel="stylesheet" href="${Assets.url("api.css")}">"""
    val links = nav(request).render

    // scaladoc decides light or dark from `localStorage` before it paints. The site's choice is in
    // the session, so it is written there first; with no choice the key is cleared and scaladoc
    // follows the system, as the docs do.
    val seed = Theme.current(request) match {
      case Some(theme) =>
        s"""<script>try{localStorage.setItem("use-dark-theme","${theme == "dark"}")}catch(e){}</script>"""
      case None =>
        """<script>try{localStorage.removeItem("use-dark-theme")}catch(e){}</script>"""
    }

    val withHead  = page.replace("</head>", stylesheet + "</head>")
    val withTheme = ThemeScript.replaceAllIn(withHead, m => seed + m.matched)
    val withLogo  =
      Logo.replaceFirstIn(
        withTheme,
        java.util.regex.Matcher.quoteReplacement(Layout.logo(Plain).render)
      )
    withLogo.replace(
      """<div class="header-container-right">""",
      links + """<div class="header-container-right">"""
    )
  }

  /** The same links the site's top bar carries, and the same theme toggle. */
  private def nav(request: Request): Html =
    Tags.nav(
      Attrs.cls                := "site-links",
      Attrs.attr("aria-label") := "Site",
      a(Plain, Attrs.href := "/docs", "Docs"),
      a(Plain, Attrs.href := "/docs/examples/hello", "Examples"),
      a(Plain, Attrs.href := "/api/", Attrs.attr("aria-current") := "page", "API"),
      a(Plain, Attrs.href := Pages.Repository, Attrs.rel         := "noopener", "GitHub"),
      Theme.toggle(request)
    )
}
