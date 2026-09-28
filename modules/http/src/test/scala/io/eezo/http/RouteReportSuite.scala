package io.eezo.http

import io.eezo.core.html.Tags.p

import munit.FunSuite

/** The route warnings and the listing are pinned here, as whole strings, because boot logs them and
  * the `routes` command prints them, and the two must never drift apart again. A pinned sentence is
  * also what keeps the boot log byte for byte where it was without a server or a log capture.
  */
class RouteReportSuite extends FunSuite {

  private val ok: Handler = _ => Response.Ok(p("ok"))

  private def http(
      method: Method,
      pattern: String,
      provenance: Provenance = Provenance.Handwritten
  ): Route =
    Route.Http(method, PathPattern.parse(pattern), ok, provenance)

  test("an overridden route is named, with what is served and what is not") {
    assertEquals(
      RouteReport.overridden(http(Method.GET, "/posts")),
      "GET /posts is written by hand and also derived; the handwritten route is served and the " +
        "derived one is not mounted."
    )
  }

  test("a shadowed route is named after the route that swallows it, with the fix") {
    assertEquals(
      RouteReport.shadowed(http(Method.GET, "/posts/:id"), http(Method.GET, "/posts/latest")),
      "GET /posts/:id shadows GET /posts/latest, which can never match. Routes are tried in table " +
        "order; move the narrower route first."
    )
  }

  test("an orphaned form page is named both ways, as routes and as the actions a user edits") {
    assertEquals(
      RouteReport.orphaned(Orphan(Action.New, "GET /widgets/new", Action.Create, "POST /widgets")),
      "GET /widgets/new is mounted without POST /widgets: the page renders a form whose submit " +
        "target is not mounted, so submitting it answers 405. Mount Create, or subtract New as well."
    )
  }

  test("a table's warnings come overridden, then shadowed, then orphaned, each in table order") {
    val table = RouteTable(
      Seq(
        // Declared ahead of everything it is grouped after, so table order alone cannot pass.
        http(Method.GET, "/widgets/new", Provenance.Derived),
        http(Method.GET, "/gadgets/new", Provenance.Derived),
        http(Method.GET, "/posts/:id"),
        http(Method.GET, "/posts/latest"),
        http(Method.GET, "/tags/:id"),
        http(Method.GET, "/tags/latest"),
        http(Method.GET, "/posts"),
        http(Method.GET, "/posts", Provenance.Derived),
        http(Method.GET, "/tags"),
        http(Method.GET, "/tags", Provenance.Derived)
      )
    )

    assertEquals(
      RouteReport.warnings(table),
      table.overridden.map(RouteReport.overridden) ++
        table.shadowed.map(RouteReport.shadowed) ++
        Resource.orphaned(table).map(RouteReport.orphaned)
    )
    assertEquals(
      RouteReport.warnings(table).map(_.split(' ').take(2).mkString(" ")),
      Seq(
        "GET /posts",
        "GET /tags",
        "GET /posts/:id",
        "GET /tags/:id",
        "GET /widgets/new",
        "GET /gadgets/new"
      )
    )
  }

  test("an empty table lists as a sentence, because a typo'd derives mounts nothing in silence") {
    assertEquals(RouteReport.listing(Seq.empty), "no routes mounted")
  }

  test("a single route lists under a singular heading") {
    assertEquals(RouteReport.listing(Seq(http(Method.GET, "/"))), "1 route:\n  GET /")
  }

  test("several routes list under a counted heading, one indented line each, in table order") {
    assertEquals(
      RouteReport.listing(Seq(http(Method.GET, "/posts"), http(Method.POST, "/posts"))),
      "2 routes:\n  GET /posts\n  POST /posts"
    )
  }
}
