package io.eezo.cli

import io.eezo.core.html.Tags.p
import io.eezo.http.{Handler, Method, PathPattern, Provenance, Response, Route, RouteTable}

import munit.FunSuite

/** `routes` is pure over the assembled table; the listing must surface everything boot warns about,
  * because the CLI and the boot print are two renderings of one answer.
  */
class RoutesCommandSuite extends FunSuite {

  private val ok: Handler = _ => Response.Ok(p("ok"))

  private def http(
      method: Method,
      pattern: String,
      provenance: Provenance = Provenance.Handwritten
  ): Route =
    Route.Http(method, PathPattern.parse(pattern), ok, provenance)

  test("routes: reports the table, the overridden derived route, shadowing, and orphans") {
    val table = RouteTable(
      Seq(
        // A handwritten route and its derived twin: the derived one is dropped, and reported.
        http(Method.GET, "/posts"),
        http(Method.GET, "/posts", Provenance.Derived),
        // Declaration order makes the parameterised route swallow the literal one after it.
        http(Method.GET, "/posts/:id"),
        http(Method.GET, "/posts/latest"),
        // A derived form page whose submit target is not mounted anywhere.
        http(Method.GET, "/widgets/new", Provenance.Derived)
      )
    )

    val listing = Commands.routes(table)

    assertEquals(listing.routes.map(_.describe), table.routes.map(_.describe))
    assertEquals(listing.overridden.map(_.describe), Seq("GET /posts"))
    assertEquals(
      listing.shadowed.map { case (earlier, later) => (earlier.describe, later.describe) },
      Seq(("GET /posts/:id", "GET /posts/latest"))
    )
    assertEquals(listing.orphans.map(_.targetRoute), Seq("POST /widgets"))
  }

  test("routes: a clean table has nothing to warn about") {
    val listing = Commands.routes(RouteTable(Seq(http(Method.GET, "/"))))
    assertEquals(listing.overridden, Seq.empty[Route])
    assertEquals(listing.shadowed, Seq.empty[(Route, Route)])
    assertEquals(listing.orphans, Seq.empty[OrphanedPage])
  }
}
