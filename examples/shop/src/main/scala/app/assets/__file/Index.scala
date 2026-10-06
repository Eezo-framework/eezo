package app.assets.__file

import io.eezo.http.{Body, Guarded, NotFound, Request, Response}

import views.Assets

/** `app/assets/__file/Index.scala` mounts the catch-all under `/assets`, `*file` in the generated
  * table: the stylesheet, read out of the jar. Public, like the shop page itself: a stylesheet has
  * no one to hide from. A URL that carries the version the pages link with is cached for a year,
  * since the next deploy links a new one.
  */
object Index {
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response = {
    val file        = request.param[String]("file")
    val bytes       = Assets.read(file).getOrElse(throw NotFound(request.path))
    val contentType = Assets.contentType(file).getOrElse(throw NotFound(request.path))
    val caching     =
      if (request.queryParam("v").contains(Assets.version)) "public, max-age=31536000, immutable"
      else "public, max-age=300"
    Response(
      200,
      Seq("Content-Type" -> contentType, "Cache-Control" -> caching),
      Body.Bytes(bytes)
    )
  }
}
