package io.eezo.http.cli

import io.eezo.core.html.Tags.p
import io.eezo.http.{
  ApiRequest,
  Handler,
  Method,
  PathPattern,
  Provenance,
  Response,
  Route,
  RouteTable
}

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

  private val table = RouteTable(
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

  test("routes: reports the table, the overridden derived route, shadowing, and orphans") {
    val listing = Commands.routes(table)

    assertEquals(listing.routes.map(_.describe), table.routes.map(_.describe))
    assertEquals(listing.overridden.map(_.describe), Seq("GET /posts"))
    assertEquals(
      listing.shadowed.map { case (earlier, later) => (earlier.describe, later.describe) },
      Seq(("GET /posts/:id", "GET /posts/latest"))
    )
    assertEquals(listing.orphans.map(_.targetRoute), Seq("POST /widgets"))
  }

  test("routes: the text prints boot's own sentences, each marked, above the listing") {
    assertEquals(
      Render.routes(Commands.routes(table)),
      Seq(
        "⚠ GET /posts is written by hand and also derived; the handwritten route is served and " +
          "the derived one is not mounted.",
        "⚠ GET /posts/:id shadows GET /posts/latest, which can never match. Routes are tried in " +
          "table order; move the narrower route first.",
        "⚠ GET /widgets/new is mounted without POST /widgets: the page renders a form whose " +
          "submit target is not mounted, so submitting it answers 405. Mount Create, or " +
          "subtract New as well.",
        "4 routes:",
        "  GET /posts",
        "  GET /posts/:id",
        "  GET /posts/latest",
        "  GET /widgets/new"
      ).mkString("\n")
    )
  }

  test("routes: a clean table has nothing to warn about") {
    val listing = Commands.routes(RouteTable(Seq(http(Method.GET, "/"))))
    assertEquals(listing.overridden, Seq.empty[Route])
    assertEquals(listing.shadowed, Seq.empty[(Route, Route)])
    assertEquals(listing.orphans, Seq.empty[OrphanedPage])
  }

  test("routes: the text marks an API route, and leaves a browser route's line alone") {
    val mixed = RouteTable(
      Seq(
        http(Method.GET, "/"),
        Route.handwritten(Method.POST, "/webhooks/stripe", (_: ApiRequest) => Response.status(200))
      )
    )
    assertEquals(
      Render.routes(Commands.routes(mixed)),
      "2 routes:\n  GET /\n  POST /webhooks/stripe api"
    )
  }
}
