package site

import io.eezo.core.html.*
import io.eezo.live.Component
import io.eezo.live.Event
import io.eezo.live.Init
import io.eezo.live.Live

/** The documentation tree, and the drawer it slides in as on a narrow screen.
  *
  * A live component with one bit of state: whether the drawer is open. The button that opens it and
  * the tree it opens are one subtree, because a component patches its own root and nothing outside
  * it; the stylesheet puts the button in the top bar. On a wide screen the same tree is the left
  * column and the button is not shown.
  */
final class Drawer(tree: Pages, current: Page) extends Component[Boolean] {

  def init(ctx: Init[Boolean]): Boolean = false

  def handle(event: Event, open: Boolean): Boolean = event.name match {
    case "toggle" => !open
    case "close"  => false
    case _        => open
  }

  def render(open: Boolean): Html =
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
        nav(
          Attrs.attr("aria-label") := "Documentation",
          tree.sections.map { section =>
            div(
              Attrs.cls := "nav-section",
              h2(section.name),
              ul(
                section.pages.map { page =>
                  li(
                    a(
                      Attrs.href := page.path,
                      if (page == current) Seq(Attrs.attr("aria-current") := "page") else Nil,
                      page.title
                    )
                  )
                }
              )
            )
          }
        )
      )
    )
}
