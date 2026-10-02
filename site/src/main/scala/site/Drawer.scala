package site

import io.eezo.core.html.*
import io.eezo.live.Event
import io.eezo.live.Live

/** The documentation tree, and the drawer it slides in as on a narrow screen.
  *
  * One bit of state, owned by [[Page]]: whether the drawer is open. The button that opens it and
  * the tree it opens are one subtree, and the stylesheet puts the button in the top bar. On a wide
  * screen the same tree is the left column and the button is not shown.
  *
  * `current` is the page being read, or nothing on the hub and a pillar's index.
  */
object Drawer {

  def handle(event: Event, open: Boolean): Boolean = event.name match {
    case "toggle" => !open
    case "close"  => false
    case _        => open
  }

  def render(open: Boolean, tree: Pages, current: Option[Article]): Html =
    div(
      Attrs.cls := (if (open) "drawer open" else "drawer"),
      button(
        Attrs.cls                   := "menu-toggle",
        Attrs.tpe                   := "button",
        Attrs.attr("aria-label")    := (if (open) "Close navigation" else "Open navigation"),
        Attrs.attr("aria-expanded") := open.toString,
        Attrs.attr("aria-controls") := "sidebar",
        Live.onClick("toggle"),
        if (open) Icons.close else Icons.menu
      ),
      div(Attrs.cls := "backdrop", Live.onClick("close")),
      aside(
        Attrs.id  := "sidebar",
        Attrs.cls := "sidebar",
        nav(Attrs.attr("aria-label") := "Documentation", tree.sections.map(section(_, current)))
      )
    )

  /** A section: its heading, linked to the pillar's index when it has one, then its pages with a
    * sub-heading wherever a group starts.
    */
  private def section(section: Section, current: Option[Article]): Html =
    div(
      Attrs.cls := "nav-section",
      section.path match {
        case Some(path) => h2(a(Attrs.href := path, section.name))
        case None       => h2(section.name)
      },
      // The generated API is served beside the tree rather than out of it, so its door is here.
      Html.when(section.slug.contains("reference"))(
        ul(li(Attrs.cls := "nav-api", a(Attrs.href := "/api/", "The API, from the sources")))
      ),
      section.grouped.map { case (group, pages) =>
        Html.Fragment(
          group.map(name => h3(Attrs.cls := "nav-group", name)).toVector :+
            ul(pages.map(link(_, current)))
        )
      }
    )

  private def link(page: Article, current: Option[Article]): Html =
    li(
      if (page.draft) Seq(Attrs.cls := "draft") else Nil,
      a(
        Attrs.href := page.path,
        if (current.contains(page)) Seq(Attrs.attr("aria-current") := "page") else Nil,
        page.title
      )
    )
}
