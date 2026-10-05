package app.api.__file

import java.nio.charset.StandardCharsets

import io.eezo.http.Body
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response
import site.ApiPages
import site.Assets

/** `app/api/__file/Index.scala` mounts the catch-all under `/api`: the API reference, which is the
  * scaladoc `sbt unidoc` generated at the root of the repository and the build copied into the jar.
  * Scaladoc links relatively, so `/api` without its slash is sent to `/api/`, where `index.html`
  * answers; below it every file is served as it was written.
  */
object Index {

  def index(request: Request): Response =
    if (request.path == "/api") Response.Redirect("/api/")
    else {
      val raw   = request.param[String]("file")
      val file  = if (raw.isEmpty || raw.endsWith("/")) raw + "index.html" else raw
      val bytes = Assets
        .readUnder("api", file)
        .getOrElse(
          throw NotFound(
            if (file == "index.html")
              "the API docs are not in this build: run `sbt unidoc` at the repository root"
            else request.path
          )
        )
      val contentType = Assets.contentType(file).getOrElse("application/octet-stream")
      val body        =
        if (file.endsWith(".html"))
          ApiPages
            .dress(new String(bytes, StandardCharsets.UTF_8), request)
            .getBytes(StandardCharsets.UTF_8)
        else bytes
      // A page carries the reader's own theme and token, so it is not cached; the rest is.
      val caching = if (file.endsWith(".html")) "no-cache" else "public, max-age=3600"
      Response(
        200,
        Seq("Content-Type" -> contentType, "Cache-Control" -> caching),
        Body.Bytes(body)
      )
    }
}
