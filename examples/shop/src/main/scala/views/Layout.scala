package views

import io.eezo.core.html.*
import io.eezo.http.Request

/** The shop's frame: the nav and the stylesheet around every page, the derived admin pages, the
  * login page and the error page included. Named in `Main`. A link appears in the nav in the step
  * that mounts its route: admin and sign out at step 1, board at step 5.
  */
object Layout extends io.eezo.http.Layout {

  private val stylesheet =
    """body { font-family: system-ui; max-width: 40rem; margin: 3rem auto; color: #222 }
      |nav a { margin-right: 1rem }
      |li { display: flex; gap: 1rem; align-items: baseline; padding: .4rem 0 }
      |button { padding: .3rem .9rem }""".stripMargin

  def apply(request: Request, title: Option[String], content: Html): Html =
    html(
      head(
        meta(Attrs.charset := "utf-8"),
        Tags.title(title.getOrElse("the shop")),
        style(stylesheet)
      ),
      body(
        nav(a(Attrs.href := "/", "shop")),
        main(content)
      )
    )
}
