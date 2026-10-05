package io.eezo.http

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler

/** The server, booted for real on an ephemeral port and driven over the loopback.
  *
  * This is the suite that retires the risk `research/http-server.md` section 12 names: four
  * configuration knobs whose Jetty defaults the research calls defects.
  */
class EezoServerSuite extends munit.FunSuite with ServerFixtures {

  private val hello: RouteTable =
    RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/hello"),
          _ => Response.Ok(html(body(h1("hello, eezo"))))
        )
      )
    )

  test("run blocks until stop brings the server down, then returns and frees the port") {
    // `run` hands back no handle, so it goes on its own thread and `stop` is the way back. That
    // thread ends normally, not by interrupt: the same unwinding a SIGTERM causes through the
    // shutdown hook, which is what lets whatever wrapped `run` reach its `finally`.
    val port     = freePort()
    var returned = false
    val runner   = new Thread(
      () => {
        HttpServer.run(port, hello)
        returned = true
      },
      "eezo-run"
    )
    runner.setDaemon(true)
    runner.start()
    try assertEquals(awaiting(port, "/hello").statusCode(), 200)
    finally {
      HttpServer.stop()
      runner.join(5000)
    }
    assert(returned, "run did not return after stop")
    intercept[java.io.IOException](get(port, "/hello"))
  }

  // Here rather than with the other drain tests: `run` and `stop` share one slot for the whole
  // process, and suites run side by side, so every test that uses them lives in this one suite,
  // where they run one after the other.
  test("stop on a server run is holding goes through the same drain and returns without throwing") {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val held    = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/held"),
          _ => {
            entered.countDown()
            release.await()
            Response.Ok(Html.text("finished"))
          }
        )
      )
    )
    val port     = freePort()
    var returned = false
    val runner   = new Thread(
      () => {
        HttpServer.run(port, held, HttpConfig(secret = secret))
        returned = true
      },
      "eezo-drain-run"
    )
    runner.setDaemon(true)
    runner.start()
    try {
      assertEquals(awaiting(port, HttpServer.HealthPath).statusCode(), 200)
      val _ = client.sendAsync(
        HttpRequest.newBuilder(URI.create(s"http://localhost:$port/held")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assert(entered.await(5, TimeUnit.SECONDS), "the held handler never started")
      val began = System.nanoTime()
      HttpServer.stop()
      val took = Duration.ofNanos(System.nanoTime() - began)
      assert(clue(took).compareTo(HttpServer.StopTimeout) >= 0, "stop did not wait on the drain")
      assert(clue(took).compareTo(HttpServer.StopTimeout.plusSeconds(1)) < 0, "stop overran")
    } finally {
      release.countDown()
      HttpServer.stop()
      runner.join(5000)
    }
    assert(returned, "run did not return after stop")
  }

  // Here for the same reason: `serve` ends in `run`, and `run` owns the one slot.
  test("the layout an application names is the one its server frames content in") {
    val chosen = freePort()
    val app    = new HttpApp {
      override def routes: RouteTable =
        RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), _ => Response.Ok(p("hi")))))
      override def port: Int      = chosen
      override def layout: Layout = (_, _, content) => html(body(div(content)))
    }
    val runner = new Thread(() => { val _ = app.run(Nil) }, "eezo-layout-run")
    runner.setDaemon(true)
    runner.start()
    try
      assertEquals(
        awaiting(chosen, "/").body(),
        "<!DOCTYPE html><html><body><div><p>hi</p></div></body></html>"
      )
    finally {
      HttpServer.stop()
      runner.join(5000)
    }
  }

  test("stop with nothing running is a no-op") {
    HttpServer.stop()
  }

  test("a handwritten route renders HTML produced by the core DSL") {
    serving(hello) { (_, port) =>
      val response = get(port, "/hello")
      assertEquals(response.statusCode(), 200)
      assertEquals(
        response.headers().firstValue("Content-Type").orElse(""),
        "text/html; charset=utf-8"
      )
      assertEquals(response.body(), "<html><body><h1>hello, eezo</h1></body></html>")
    }
  }

  test("the handler runs on a virtual thread") {
    val routes = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/thread"),
          _ => Response.Ok(Html.text(s"virtual=${Thread.currentThread().isVirtual}"))
        )
      )
    )
    serving(routes) { (_, port) =>
      assertEquals(get(port, "/thread").body(), plainly("virtual=true"))
    }
  }

  test("the thread pool is a VirtualThreadPool with no semaphore ceiling") {
    serving(hello) { (server, _) =>
      val pool = server.jetty.getThreadPool match {
        case p: VirtualThreadPool => p
        case other => fail(s"expected a VirtualThreadPool, got ${other.getClass.getName}")
      }
      assertEquals(pool.getMaxConcurrentTasks, 0)
    }
  }

  test("the WebSocket container overrides the three defaults the research calls defects") {
    serving(hello) { (server, _) =>
      val upgrade = Option(server.jetty.getDescendant(classOf[WebSocketUpgradeHandler]))
        .getOrElse(fail("the handler tree has no WebSocketUpgradeHandler"))
      val container = upgrade.getServerWebSocketContainer
      assertEquals(container.getIdleTimeout, Duration.ofMinutes(5))
      assertEquals(container.getMaxTextMessageSize, 1L * 1024 * 1024)
      assertEquals(container.getMaxOutgoingFrames, 64)
    }
  }

  test("an unmatched path is eezo's own 404 page, not Jetty's") {
    serving(hello) { (_, port) =>
      val response = get(port, "/nope")
      assertEquals(response.statusCode(), 404)
      assert(clue(response.body()).contains("404 Not Found"))
      assert(clue(response.body()).contains("/nope"))
    }
  }

  test("a matched path with the wrong method is a 405 carrying Allow") {
    serving(hello) { (_, port) =>
      val response = client.send(
        HttpRequest.newBuilder(URI.create(s"http://localhost:$port/hello")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(response.statusCode(), 405)
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET")
    }
  }

  test("a body over the cap is a 413, and the handler never runs") {
    @volatile var ran = false
    val routes        = RouteTable(
      Seq(
        Route.Http(
          Method.POST,
          PathPattern.parse("/upload"),
          _ => { ran = true; Response.Ok(Html.text("ok")) }
        )
      )
    )
    serving(routes, maxBodySize = 16) { (_, port) =>
      val response = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://localhost:$port/upload"))
          .POST(HttpRequest.BodyPublishers.ofString("x" * 64))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(response.statusCode(), 413)
      assert(!ran)
    }
  }

  test("a handler reads the query string, the form body and the path parameters") {
    val routes = RouteTable(
      Seq(
        Route.Http(
          Method.POST,
          PathPattern.parse("/widgets/:id"),
          req =>
            Response.Ok(
              Html.text(
                s"${req.param[Int]("id")}|${req.queryParam("q").getOrElse("")}|${req.form.getOrElse("name", Nil).mkString}"
              )
            )
        )
      )
    )
    serving(routes) { (_, port) =>
      val response = submit(port, "/widgets/7?q=hi", handed(), "name=Tom+%26+Jerry")
      assertEquals(response.body(), plainly("7|hi|Tom &amp; Jerry"))
    }
  }

  test("a byte body is written as it stands, with the headers the handler set") {
    val routes = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/bytes"),
          _ =>
            Response(
              200,
              Seq("Content-Type" -> "text/plain; charset=utf-8"),
              Body.Bytes("hi".getBytes("UTF-8"))
            )
        )
      )
    )
    serving(routes) { (_, port) =>
      val response = get(port, "/bytes")
      assertEquals(response.body(), "hi")
      assertEquals(
        response.headers().firstValue("Content-Type").orElse(""),
        "text/plain; charset=utf-8"
      )
    }
  }

  test("an exception a handler throws is a redacted 500 in production and the message in dev") {
    val routes = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/boom"),
          _ => throw new IllegalStateException("connection refused")
        )
      )
    )
    serving(routes) { (_, port) =>
      val response = get(port, "/boom")
      assertEquals(response.statusCode(), 500)
      assert(!clue(response.body()).contains("connection refused"))
    }
    serving(routes, dev = true) { (_, port) =>
      assert(clue(get(port, "/boom").body()).contains("connection refused"))
    }
  }

  test("a form POST carrying _method=DELETE is dispatched to the DELETE route") {
    val routes = RouteTable(
      Seq(
        Route.Http(
          Method.POST,
          PathPattern.parse("/widgets/:id"),
          _ => Response.Ok(Html.text("posted"))
        ),
        Route.Http(
          Method.DELETE,
          PathPattern.parse("/widgets/:id"),
          req => Response.Ok(Html.text(s"destroyed ${req.param[String]("id")}"))
        )
      )
    )
    serving(routes) { (_, port) =>
      val response = submit(port, "/widgets/7", handed(), "_method=DELETE")
      assertEquals(response.body(), plainly("destroyed 7"))
    }
  }

  test("the dev server appends the reload client inside body, and production serves none") {
    serving(hello, dev = true) { (_, port) =>
      val page = get(port, "/hello").body()
      assert(clue(page).startsWith("<html><body><h1>hello, eezo</h1><script>"))
      assert(clue(page).endsWith("</script></body></html>"))
    }
    serving(hello) { (_, port) =>
      assertEquals(get(port, "/hello").body(), "<html><body><h1>hello, eezo</h1></body></html>")
    }
  }

  test("the dev server's own 404 page carries the reload client too") {
    serving(hello, dev = true) { (_, port) =>
      val response = get(port, "/nope")
      assertEquals(response.statusCode(), 404)
      assert(clue(response.body()).contains("<script>"))
    }
  }

  test("an HTTP GET on the reload path is an ordinary 404 on the dev server") {
    serving(hello, dev = true) { (_, port) =>
      assertEquals(get(port, Reload.path).statusCode(), 404)
    }
  }
}
