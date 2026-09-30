package app.theme

import io.eezo.http.BadRequest
import io.eezo.http.Request
import io.eezo.http.Response
import site.Theme

/** `app/theme/Create.scala` mounts `POST /theme`: the theme toggle's form. The choice goes into the
  * session and the browser goes back where it was. The CSRF token the form carries is checked
  * before this runs, like any unsafe request.
  */
object Create {

  def create(request: Request): Response = {
    val to = request.form
      .get("to")
      .flatMap(_.headOption)
      .filter(Theme.Choices)
      .getOrElse(throw BadRequest("theme must be light or dark"))
    // Only a path of this site: a `back` that named another host would make this a redirector.
    val back = request.form
      .get("back")
      .flatMap(_.headOption)
      .filter(path => path.startsWith("/") && !path.startsWith("//"))
      .getOrElse("/")
    Response.Redirect(back).withSession(request.session.set(Theme.Key, to))
  }
}
