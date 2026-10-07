package app.install

import io.eezo.http.Body
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response

import site.Content

/** `GET /install/eezo`: the launcher itself, `bin/eezo` from the repository, which the installer
  * downloads to `~/.local/bin/eezo`. Served from the site so the installer and the launcher it
  * installs are always the same commit.
  */
object Eezo {
  def eezo(request: Request): Response = {
    val text = Content.current.read("bin/eezo").getOrElse(throw NotFound(request.path))
    Response(
      200,
      Seq("Content-Type" -> "text/plain; charset=utf-8", "Cache-Control" -> "public, max-age=300"),
      Body.Bytes(text.getBytes("UTF-8"))
    )
  }
}
