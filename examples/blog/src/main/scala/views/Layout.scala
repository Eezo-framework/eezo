package views

import io.eezo.core.html.*
import io.eezo.http.Assets
import io.eezo.http.Request

/** The blog's frame around every page, the derived admin pages, the login page and the error page
  * included. Named in `Main`.
  *
  * The stylesheet is the one the sbt plugin builds from `tailwind.css` at the project root. It
  * lands in the jar at `assets/tailwind.css`, and `Assets.url` spells its version into the link, so
  * a browser keeps it for a year and the next build that changes a class is a new address. The
  * classes on the frame itself are read by the same Tailwind run as the components' are.
  */
object Layout extends io.eezo.http.Layout {

  def apply(request: Request, title: Option[String], content: Html): Html =
    html(
      Attrs.lang := "en",
      head(
        meta(Attrs.charset := "utf-8"),
        meta(Attrs.name    := "viewport", Attrs.content := "width=device-width, initial-scale=1"),
        Tags.title(title.getOrElse("eezo blog")),
        link(Attrs.rel := "stylesheet", Attrs.href := Assets.url("tailwind.css"))
      ),
      body(
        Attrs.cls := "min-h-screen bg-stone-50 text-stone-900 antialiased",
        nav(
          Attrs.cls := "mx-auto flex max-w-2xl flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-sm",
          a(
            Attrs.cls  := "font-semibold text-stone-900 no-underline",
            Attrs.href := "/",
            "eezo blog"
          ),
          a(Attrs.href := "/admin/posts", "posts"),
          a(Attrs.href := "/counter", "counter"),
          a(Attrs.href := "/form", "form"),
          a(Attrs.href := "/live", "guestbook"),
          a(Attrs.href := "/board", "board"),
          a(Attrs.href := "/callout", "callout")
        ),
        main(Attrs.cls := "mx-auto max-w-2xl px-4 py-6", content)
      )
    )
}
