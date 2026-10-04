package io.eezo.http

import java.util.logging.Handler as LogHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

import io.eezo.core.Id
import io.eezo.core.html.*

/** The one frame every HTML reply comes back in, driven through a booted server, because the frame
  * is applied where every response passes and nowhere else.
  */
class LayoutSuite extends munit.FunSuite with ServerFixtures with ResourceFixtures {

  private val anybody: Request = anonymous(Method.GET, "/")

  /** A layout that remembers every call it was handed and marks the frame it builds, so a test can
    * tell a framed page from one that went out as written.
    */
  private final class Marked extends Layout {
    private var seen = Vector.empty[(Option[String], String)]

    def calls: Vector[(Option[String], String)] = synchronized(seen)

    def apply(request: Request, title: Option[String], content: Html): Html = {
      synchronized { seen :+= ((title, content.render)) }
      html(
        head(title.fold(Html.empty)(Tags.title(_))),
        body(Attrs.cls := "framed", content)
      )
    }
  }

  private def page(path: String)(handler: Handler): Route =
    Route.Http(Method.GET, PathPattern.parse(path), handler)

  private def table(routes: Route*): RouteTable = RouteTable(routes)

  /** A layout that fails, the way one that reads something the request does not carry does. */
  private val failing: Layout = (_, _, _) => throw new IllegalStateException("the layout broke")

  /** What eezo logged at `level` about `path` while `body` ran. The logger is process wide and
    * other suites running beside this one share it, so the lines are filtered by the path they
    * name.
    */
  private def logging(level: Level, path: String)(body: => Unit): Vector[String] = {
    val logger  = Logger.getLogger("io.eezo.http")
    var lines   = Vector.empty[String]
    val handler = new LogHandler {
      override def publish(record: LogRecord): Unit =
        if (record.getLevel == level && record.getMessage.endsWith(s" $path"))
          synchronized { lines :+= record.getMessage }
      override def flush(): Unit = ()
      override def close(): Unit = ()
    }
    logger.addHandler(handler)
    try body
    finally logger.removeHandler(handler)
    handler.synchronized(lines)
  }

  test("the plain layout puts the charset and the title in the head and the content in the body") {
    assertEquals(
      Layout.plain(anybody, Some("Widgets"), h1("All Widgets")).render,
      """<html><head><meta charset="utf-8"><title>Widgets</title></head>""" +
        "<body><h1>All Widgets</h1></body></html>"
    )
  }

  test("the plain layout writes no title element when there is no title") {
    assertEquals(
      Layout.plain(anybody, None, p("x")).render,
      """<html><head><meta charset="utf-8"></head><body><p>x</p></body></html>"""
    )
  }

  test("content comes back inside the layout, with the doctype in front") {
    val marked = new Marked
    serving(table(page("/")(_ => Response.Ok(p("hi")))), layout = marked) { (_, port) =>
      assertEquals(
        get(port, "/").body(),
        """<!DOCTYPE html><html><head></head><body class="framed"><p>hi</p></body></html>"""
      )
    }
  }

  test(
    "a document goes out byte for byte as written, doctype or none, and the layout is not called"
  ) {
    val marked   = new Marked
    val document = Html.doctype ++ html(head(title("mine")), body(p("whole")))
    val bare     = html(body(p("bare")))
    val routes   = table(
      page("/document")(_ => Response.Ok(document)),
      page("/bare")(_ => Response.Ok(bare))
    )
    serving(routes, layout = marked) { (_, port) =>
      assertEquals(get(port, "/document").body(), document.render)
      assertEquals(get(port, "/bare").body(), bare.render)
    }
    assertEquals(marked.calls, Vector.empty)
  }

  test("the first title leaves the content and its text reaches the layout; a second one stays") {
    val marked = new Marked
    val routes = table(
      page("/titled")(_ => Response.Ok(title("Widgets") ++ h1("All") ++ title("second"))),
      page("/untitled")(_ => Response.Ok(h1("none")))
    )
    serving(routes, layout = marked) { (_, port) =>
      assertEquals(
        get(port, "/titled").body(),
        "<!DOCTYPE html><html><head><title>Widgets</title></head>" +
          """<body class="framed"><h1>All</h1><title>second</title></body></html>"""
      )
      val _ = get(port, "/untitled")
    }
    assertEquals(
      marked.calls,
      Vector(
        (Some("Widgets"), "<h1>All</h1><title>second</title>"),
        (None, "<h1>none</h1>")
      )
    )
  }

  test("a title is handed over as plain text and escaped once, on the way out") {
    val marked = new Marked
    val routes = table(page("/")(_ => Response.Ok(title("""Tom & "Jerry" <3""") ++ p("x"))))
    serving(routes, layout = marked) { (_, port) =>
      assert(
        clue(get(port, "/").body()).contains("<title>Tom &amp; &quot;Jerry&quot; &lt;3</title>")
      )
    }
    assertEquals(marked.calls.map(_._1), Vector(Some("""Tom & "Jerry" <3""")))
  }

  test("the layout reads the session the browser carried, on a page and on a 404 alike") {
    val greeting: Layout = (request, _, content) =>
      html(body(p(request.session.get("name").getOrElse("nobody")), content))
    val cookie = SessionCookie.encode(Session.empty.set("name", "ann"), secret)
    serving(table(page("/")(_ => Response.Ok(p("x")))), layout = greeting) { (_, port) =>
      assert(clue(send(port, "GET", "/", Some(cookie)).body()).contains("<p>ann</p><p>x</p>"))
      assert(clue(send(port, "GET", "/nope", Some(cookie)).body()).contains("<p>ann</p>"))
      assert(clue(get(port, "/").body()).contains("<p>nobody</p>"))
    }
  }

  test("a 404 off the boundary comes back inside the layout, its status kept") {
    val marked = new Marked
    serving(table(page("/")(_ => Response.Ok(p("x")))), layout = marked) { (_, port) =>
      val response = get(port, "/nope")
      assertEquals(response.statusCode(), 404)
      assert(clue(response.body()).startsWith("<!DOCTYPE html><html><head><title>404 Not Found"))
      assert(clue(response.body()).contains("""<body class="framed"><h1>404 Not Found</h1>"""))
    }
    assertEquals(marked.calls.map(_._1), Vector(Some("404 Not Found")))
  }

  test("a layout that throws is answered 500 in the plain frame, and logged like a handler") {
    val routes = table(page("/broken-frame")(_ => Response.Ok(p("x"))))
    val logged = logging(Level.SEVERE, "/broken-frame") {
      serving(routes, layout = failing) { (_, port) =>
        val response = get(port, "/broken-frame")
        assertEquals(response.statusCode(), 500)
        assertEquals(
          response.body(),
          """<!DOCTYPE html><html><head><meta charset="utf-8">""" +
            "<title>500 Internal Server Error</title></head><body>" +
            "<h1>500 Internal Server Error</h1>" +
            "<p>The server encountered an unexpected error.</p>" +
            "<p><small>/broken-frame</small></p></body></html>"
        )
      }
    }
    assertEquals(logged, Vector("500 on /broken-frame"))
  }

  test("a request that cannot be read is answered in the plain frame, whatever the layout") {
    val marked = new Marked
    serving(table(page("/")(_ => Response.Ok(p("x")))), maxBodySize = 4, layout = marked) {
      (_, port) =>
        val response = send(port, "POST", "/", None, form = Some("far more than four bytes"))
        assertEquals(response.statusCode(), 413)
        assert(
          clue(response.body()).startsWith(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><title>413 Content Too Large"""
          )
        )
    }
    assertEquals(marked.calls, Vector.empty)
  }

  test("a derived page comes back inside the layout, titled with its heading, and so does a 422") {
    val marked = new Marked
    val store  = InMemoryStore[Widget]()
    val row    = Widget(Id.gen(), "bolt", 3)
    store.insert(row.id, row)
    serving(RouteTable(Resource.routesOf[Widget](store)), layout = marked) { (_, port) =>
      val shown = get(port, s"/widgets/${row.id.show}")
      assertEquals(shown.statusCode(), 200)
      assert(clue(shown.body()).startsWith("<!DOCTYPE html><html><head><title>Widget</title>"))
      assert(clue(shown.body()).contains("""<body class="framed"><h1>Widget</h1><dl>"""))

      val refused = submit(port, "/widgets", visit(port, "/widgets"), "name=nut&price=lots")
      assertEquals(refused.statusCode(), 422)
      assert(clue(refused.body()).contains("""<body class="framed"><h1>New Widget</h1><form"""))
    }
    assertEquals(
      marked.calls.map(_._1),
      Vector(Some("Widget"), Some("widgets"), Some("New Widget"))
    )
  }

  test("under a mount the content's links are already moved and the layout's own are not") {
    val nav: Layout =
      (_, _, content) => html(body(a(Attrs.href := Url.Mounted("/home"), "home"), content))
    val routes = RouteTable(
      Route.under("/admin")(
        Seq(page("/")(_ => Response.Ok(a(Attrs.href := Url.Mounted("/posts")))))
      )
    )
    serving(routes, layout = nav) { (_, port) =>
      assertEquals(
        get(port, "/admin").body(),
        """<!DOCTYPE html><html><body><a href="/home">home</a><a href="/admin/posts"></a>""" +
          "</body></html>"
      )
    }
  }

  test("on the dev server the reload client is the last child of the layout's body, once") {
    serving(table(page("/")(_ => Response.Ok(p("x")))), dev = true, layout = new Marked) {
      (_, port) =>
        val page = get(port, "/").body()
        assert(clue(page).endsWith(s"<p>x</p>${Reload.tag.render}</body></html>"))
        assertEquals(page.split("<script>", -1).length - 1, 1)
    }
  }

  test("a byte body and an empty one go out exactly as they were, an HTML content type or not") {
    val bytes  = "<p>raw</p>".getBytes("UTF-8")
    val marked = new Marked
    val routes = table(
      page("/bytes")(_ => Response(200, Seq(Response.HtmlContentType), Body.Bytes(bytes))),
      page("/empty")(_ => Response.status(204).withHeader("X-Kept", "yes"))
    )
    serving(routes, layout = marked) { (_, port) =>
      val sent = get(port, "/bytes")
      assertEquals(sent.statusCode(), 200)
      assertEquals(sent.headers().firstValue("Content-Type").orElse(""), "text/html; charset=utf-8")
      assertEquals(sent.body(), "<p>raw</p>")

      val empty = get(port, "/empty")
      assertEquals(empty.statusCode(), 204)
      assertEquals(empty.headers().firstValue("X-Kept").orElse(""), "yes")
      assertEquals(empty.body(), "")
    }
    assertEquals(marked.calls, Vector.empty)
  }

  test("a framed reply keeps its status, its headers and the session cookie it writes") {
    val routes = table(
      page("/")(request =>
        Response(201, Seq(Response.HtmlContentType), Body.Html(p("made")))
          .withHeader("X-Kept", "yes")
          .withSession(request.session.set("name", "ann"))
      )
    )
    serving(routes, layout = new Marked) { (_, port) =>
      val response = get(port, "/")
      assertEquals(response.statusCode(), 201)
      assertEquals(response.headers().firstValue("X-Kept").orElse(""), "yes")
      assertEquals(
        sessionCookie(response).map(SessionCookie.decode(_, secret).get("name")),
        Some(Some("ann"))
      )
      assert(clue(response.body()).contains("""<body class="framed"><p>made</p></body>"""))
    }
  }
}
