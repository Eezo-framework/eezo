package io.eezo.http

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import io.eezo.core.Id
import io.eezo.core.html.{Attrs, Html, Url}
import io.eezo.core.html.Tags.*

/** What a mount does to the URLs a handler emits, which is the half `Route.under` used to leave
  * behind: the pattern moved and the page kept linking to where the route no longer was.
  *
  * Every assertion here reads the rendered markup or the `Location` header, never the route
  * pattern, because a pattern level assertion passes over a completely unmounted page.
  */
class MountSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], name: String, price: Int) derives Form, Resource

  private def mounted(prefixes: String*): (RouteTable, Widget) = {
    val store = Store.inMemory()
    val row   = Widget(Id.gen[Widget](), "Bolt", 3)
    store.insert("widgets", row.id, row)
    val routes = prefixes.foldRight(summon[Resource[Widget]].routes(store)) { (prefix, inner) =>
      Route.under(prefix)(inner)
    }
    (RouteTable(routes), row)
  }

  private def request(method: Method, path: String, form: (String, String)*): Request = {
    val body = form
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers =
        if (form.isEmpty) Map.empty
        else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )
  }

  private def markup(response: Response): String = response.body match {
    case Body.Html(node) => node.render
    case other           => fail(s"expected an HTML body, got $other")
  }

  private def location(response: Response): String = response.header("Location").getOrElse("")

  // ---------------------------------------------------------------- read pages

  test("every href on a mounted index begins with the prefix") {
    val (routes, row) = mounted("/admin")
    val page          = markup(routes.dispatch(request(Method.GET, "/admin/widgets")))
    assert(page.contains(s"""href="/admin/widgets/${row.id.show}""""), page)
    assert(page.contains("""href="/admin/widgets/new""""), page)
    assert(!page.contains("""href="/widgets"""), page)
  }

  test("a mounted show page links to edit, deletes and goes back, all under the prefix") {
    val (routes, row) = mounted("/admin")
    val page = markup(routes.dispatch(request(Method.GET, s"/admin/widgets/${row.id.show}")))
    assert(page.contains(s"""href="/admin/widgets/${row.id.show}/edit""""), page)
    assert(page.contains(s"""<form action="/admin/widgets/${row.id.show}""""), page)
    assert(page.contains("""href="/admin/widgets">All widgets"""), page)
  }

  // ---------------------------------------------------------------- write pages

  test("a mounted new page posts to a target dispatch actually serves") {
    val (routes, _) = mounted("/admin")
    val page        = markup(routes.dispatch(request(Method.GET, "/admin/widgets/new")))
    assert(page.contains("""<form action="/admin/widgets" method="post">"""), page)
    assert(page.contains("""href="/admin/widgets">All widgets"""), page)

    val created =
      routes.dispatch(request(Method.POST, "/admin/widgets", "name" -> "Nut", "price" -> "1"))
    assertEquals(created.status, 303)
    assert(location(created).startsWith("/admin/widgets/"), location(created))
  }

  test("a mounted edit page submits to a target dispatch actually serves") {
    val (routes, row) = mounted("/admin")
    val path          = s"/admin/widgets/${row.id.show}"
    val page          = markup(routes.dispatch(request(Method.GET, s"$path/edit")))
    assert(page.contains(s"""<form action="$path" method="post">"""), page)
    assert(page.contains("""href="/admin/widgets">All widgets"""), page)

    val updated =
      routes.dispatch(request(Method.PUT, path, "name" -> "Nut", "price" -> "2"))
    assertEquals(updated.status, 303)
    assertEquals(location(updated), path)
  }

  test("the form rendered again after a failed update still targets the mounted path") {
    val (routes, row) = mounted("/admin")
    val path          = s"/admin/widgets/${row.id.show}"
    val rejected      = routes.dispatch(request(Method.PUT, path, "name" -> "Nut", "price" -> "no"))
    assertEquals(rejected.status, 422)
    assert(markup(rejected).contains(s"""<form action="$path" method="post">"""), markup(rejected))
  }

  test("destroying a mounted record redirects inside the mount") {
    val (routes, row) = mounted("/admin")
    val destroyed     = routes.dispatch(request(Method.DELETE, s"/admin/widgets/${row.id.show}"))
    assertEquals(destroyed.status, 303)
    assertEquals(location(destroyed), "/admin/widgets")
  }

  // ---------------------------------------------------------------- what a mount leaves alone

  test("an absolute url and a handwritten String are served exactly as written") {
    val handwritten: Handler = _ =>
      Response.Ok(
        p(
          a(Attrs.href := Url.Absolute("https://eezo.io"), "home"),
          a(Attrs.href := "/posts", "posts")
        )
      )
    val routes = RouteTable(
      Route.under("/admin")(Seq(Route.Http(Method.GET, PathPattern.parse("/page"), handwritten)))
    )
    assertEquals(
      markup(routes.dispatch(request(Method.GET, "/admin/page"))),
      """<p><a href="https://eezo.io">home</a><a href="/posts">posts</a></p>"""
    )
  }

  test("nested mounts compose, in the markup and in the Location alike") {
    val (routes, row) = mounted("/admin", "/v1")
    val page          = markup(routes.dispatch(request(Method.GET, "/admin/v1/widgets")))
    assert(page.contains("""href="/admin/v1/widgets/new""""), page)

    val destroyed = routes.dispatch(request(Method.DELETE, s"/admin/v1/widgets/${row.id.show}"))
    assertEquals(location(destroyed), "/admin/v1/widgets")
  }

  test("a WebSocket route moves its pattern and keeps the endpoint it was given") {
    val endpoint: Request => WsListener = _ => new WsListener {}
    val moved = Route.under("/admin")(Seq(Route.Ws(PathPattern.parse("/live"), endpoint)))
    moved match {
      case Seq(Route.Ws(pattern, mounted, _)) =>
        assertEquals(pattern.render, "/admin/live")
        assert(mounted eq endpoint)
      case other => fail(s"expected one WebSocket route, got $other")
    }
  }

  test("a resource mounted under no prefix renders what it renders today") {
    val store = Store.inMemory()
    val row   = Widget(Id.gen[Widget](), "Bolt", 3)
    store.insert("widgets", row.id, row)
    val plain = RouteTable(summon[Resource[Widget]].routes(store))
    val page  = markup(plain.dispatch(request(Method.GET, "/widgets")))
    assert(page.contains(s"""href="/widgets/${row.id.show}""""), page)
    assertEquals(
      location(plain.dispatch(request(Method.DELETE, s"/widgets/${row.id.show}"))),
      "/widgets"
    )
  }

  test("a Location a handler wrote as a String is left alone, since a String is absolute") {
    val handwritten: Handler = _ => Response.Redirect("/posts")
    val routes               = RouteTable(
      Route.under("/admin")(Seq(Route.Http(Method.GET, PathPattern.parse("/go"), handwritten)))
    )
    assertEquals(location(routes.dispatch(request(Method.GET, "/admin/go"))), "/posts")
  }

  test("Html.text and raw content are untouched by a mount") {
    val handwritten: Handler = _ => Response.Ok(div(Html.raw("""<a href="/posts">raw</a>""")))
    val routes               = RouteTable(
      Route.under("/admin")(Seq(Route.Http(Method.GET, PathPattern.parse("/page"), handwritten)))
    )
    assertEquals(
      markup(routes.dispatch(request(Method.GET, "/admin/page"))),
      """<div><a href="/posts">raw</a></div>"""
    )
  }
}
