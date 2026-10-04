package views

import io.eezo.core.html.*
import io.eezo.http.Request

import models.User

/** The shop's frame: the nav and the stylesheet around every page, the derived admin pages, the
  * login page and the error page included. Named in `Main`. Every link in the nav has a route behind it.
  *
  * The stylesheet is `assets/shop.css` in the jar, linked under the versioned address `Assets`
  * gives it and served by the route under `/assets`. Element selectors only, so the pages typed on
  * stage carry no class names.
  */
object Layout extends io.eezo.http.Layout {

  def apply(request: Request, title: Option[String], content: Html): Html =
    html(
      head(
        meta(Attrs.charset := "utf-8"),
        Tags.title(title.getOrElse("the shop")),
        link(Attrs.rel := "stylesheet", Attrs.href := Assets.url("shop.css"))
      ),
      body(
        nav(
          a(Attrs.href := "/", "shop"),
          a(Attrs.href := "/board", "board"),
          a(Attrs.href := "/products", "admin"),
          User.guard.logoutForm(request, Url.Absolute("/logout"))
        ),
        main(content)
      )
    )
}
