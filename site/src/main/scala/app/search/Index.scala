package app.search

import io.eezo.http.Request
import io.eezo.http.Response

import site.Docs

/** `GET /search`: the documentation hub with the search dialog already open, for a link from a page
  * that has no live chrome of its own, which is what the API reference is.
  */
object Index {
  def index(request: Request): Response = Response.Ok(Docs.searching(request))
}
