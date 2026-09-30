package site

import io.eezo.core.html.*
import io.eezo.http.Csrf
import io.eezo.http.Request

/** Light or dark, remembered in the session.
  *
  * A preference that has to survive from one page to the next is the session's to keep, not a live
  * page's: a component's state lives as long as its page, and a socket cannot write a cookie. So
  * the toggle is an ordinary form that posts to `/theme`, the handler amends the session and
  * redirects back, and every page renders the choice on `<html>` before the browser paints. With no
  * choice made, the stylesheet follows the operating system.
  */
object Theme {

  /** The session entry. */
  val Key: String = "theme"

  val Choices: Set[String] = Set("light", "dark")

  /** The choice this browser made, if any. */
  def current(request: Request): Option[String] = request.session.get(Key).filter(Choices)

  /** The toggle: two buttons, one to each theme, and the stylesheet shows the one that leads away
    * from the theme in effect, whether that theme was chosen here or inherited from the system.
    * Rendering both is what lets the server stay ignorant of the system preference.
    */
  def toggle(request: Request): Html =
    form(
      Attrs.cls    := "theme-form",
      Attrs.method := "post",
      Attrs.action := "/theme",
      Csrf.hidden(request.csrf),
      input(Attrs.tpe := "hidden", Attrs.name := "back", Attrs.value := request.path),
      button(
        Attrs.cls                := "theme-toggle to-dark",
        Attrs.name               := "to",
        Attrs.value              := "dark",
        Attrs.attr("aria-label") := "Switch to dark mode",
        Icons.moon
      ),
      button(
        Attrs.cls                := "theme-toggle to-light",
        Attrs.name               := "to",
        Attrs.value              := "light",
        Attrs.attr("aria-label") := "Switch to light mode",
        Icons.sun
      )
    )
}
