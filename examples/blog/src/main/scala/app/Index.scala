package app

import io.eezo.core.html.*
import io.eezo.http.Guarded
import io.eezo.http.Request
import io.eezo.http.Response

/** `app/Index.scala` mounts `GET /`, calling `def index`.
  *
  * A handwritten route beside seven derived ones: the generated table lists this one first, so a
  * handwritten route always wins a path a derived one would also match.
  *
  * `Main.scala` mounts only the derived half of the table, so this page stays at `/` while the
  * posts move to `/admin/posts`. That is what decides the link below. This response never passes
  * through the handler wrapper `Route.under` installs, because this route is outside the mount, so
  * a `Url.Mounted("/posts")` here would find nothing to rewrite it and would render the bare
  * `/posts`, which 404s. The string `"/admin/posts"` is a finished address that eezo never touches,
  * and that is exactly the tool for pointing from outside a mount into it: the writer of the link
  * is the one who knows where the mount is.
  *
  * Inside the mount the choice reverses. A derived page's own links are `Url.Mounted`, so they
  * travel with the routes and no page has to name `/admin` at all. An address that is genuinely not
  * eezo's to move, another site or a `mailto:`, stays a string too, or a `Url.Absolute` when it
  * wants to say so in the type.
  */
object Index {

  /** Anyone may read the blog's front page.
    *
    * Saying so is not optional. This application has a guard, so every route it mounts has to
    * declare who may reach it, and a route that says nothing is a compile error rather than a page
    * that quietly turns out to be public. The declaration is about this object because a
    * handwritten route has no model behind it: the page itself is the thing being declared about.
    */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response = {
    val _ = request

    Response.Ok(
      Html.doctype ++ html(
        head(
          meta(Attrs.charset := "utf-8"),
          title("eezo blog")
        ),
        body(
          h1("eezo blog"),
          p("Seven routes, derived from one case class."),
          p(a(Attrs.href := "/admin/posts", "All posts"))
        )
      )
    )
  }
}
