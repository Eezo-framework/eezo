package app

import io.eezo.http.Body
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response

import site.Assets

/** `GET /install`: the installer, for `curl -fsSL https://eezo.io/install | bash`.
  *
  * Served at its own address rather than under `/assets`, so the command on the front page reads as
  * one and never carries a version query. Cached briefly, because the installer is fetched once per
  * machine and a stale copy helps nobody.
  */
object Install {
  def install(request: Request): Response = {
    val bytes = Assets.read("install.sh").getOrElse(throw NotFound(request.path))
    Response(
      200,
      Seq("Content-Type" -> "text/plain; charset=utf-8", "Cache-Control" -> "public, max-age=300"),
      Body.Bytes(bytes)
    )
  }
}
