package app.api

import io.eezo.http.{Body, Guarded, Request, Response}

/** `app/api/Index.scala` mounts `GET /api`: the upstream the callout demo fetches from — this
  * very application, one route over. The right bearer token gets the goods; anything else gets
  * a 401, which the client classifies as `Reply.Denied`.
  */
object Index {

  /** A demo page anyone may open; the blog has a guard, so saying so is not optional. */
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response =
    if (request.header("Authorization").contains("Bearer let-me-in"))
      Response(
        200,
        Seq("Content-Type" -> "text/plain; charset=utf-8"),
        Body.Bytes(s"the report, fetched at ${java.time.LocalTime.now().withNano(0)}".getBytes)
      )
    else
      // A body, so a browser poking at /api sees the refusal instead of a blank page.
      Response(
        401,
        Seq("Content-Type" -> "text/plain; charset=utf-8"),
        Body.Bytes("401: this endpoint wants `Authorization: Bearer let-me-in`".getBytes)
      )
}
