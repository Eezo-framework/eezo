package io.eezo

import java.io.{ByteArrayOutputStream, IOException, PrintStream}
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import io.eezo.core.Id
import io.eezo.core.html.Tags.{body, html, p}
import io.eezo.core.support.Captured.captured
import io.eezo.db.{Schema, SchemaError, Table}
import io.eezo.db.engine.Installed
import io.eezo.http.{Handler, Method, PathPattern, Response, Route, RouteTable}

/** The umbrella's entry trait: both edges stacked, `EezoApp extends HttpApp with DbApp`.
  *
  * What this suite pins: `sbt run` on an `EezoApp` answers `Nil` through the database edge, which
  * installs a `Database` around the http edge's `boot`, and `dev` is the one override, the drift
  * check under that same `Database` and then the http edge's dev server. Both are overrides of a
  * hook each edge implements (`program`, `devServer`), and the compiler refuses a trait that stacks
  * the two edges without overriding `program`, so the suite also pins that a hand stacking in
  * either order does not compile. No Postgres is needed: `Database.connect` never touches the
  * network, so a bogus URL installs and closes without a query, and the drift check skips an empty
  * schema and warns about a database it cannot reach.
  */
class EezoAppSuite extends munit.FunSuite {

  private val client = HttpClient.newHttpClient()

  /** An application that records, instead of serving, whether a `Database` was installed. Under
    * `dev` it does serve, on a port picked by [[freePort]], and its one page records the same thing
    * at request time.
    */
  private class Recording extends EezoApp {
    var booted                                   = false
    var bootedUnderDb                            = false
    @volatile var servedUnderDb: Option[Boolean] = None

    private val page: Handler = _ => {
      servedUnderDb = Some(Installed.installed)
      Response.Ok(html(body(p("ok"))))
    }

    override def schema: Schema     = Schema.empty
    override def routes: RouteTable =
      RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), page)))

    override lazy val port: Int = freePort()

    override def databaseUrl: String = "jdbc:postgresql://nowhere:1/none"

    override def boot(): Unit = {
      booted = true
      bootedUnderDb = Installed.installed
    }
  }

  /** A port nobody is listening on right now. `Eezo.run` binds the application's `port` and hands
    * back no handle, so a test cannot ask the server which ephemeral port it took and picks one up
    * front instead.
    */
  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** Runs `dev` on `app` for real and hands `body` the answer to `GET /` once the server is up.
    *
    * `serve` is final and `Eezo.run` blocks until the server stops, keeping the `Server` as a
    * local, so the command runs on a daemon thread and the test holds nothing it could stop.
    * Interrupting that thread is what ends `run`: Jetty's `join` throws, `withDatabase` uninstalls
    * and closes on the way out, and the listener itself is left to die with the JVM.
    */
  private def developing(app: Recording)(body: HttpResponse[String] => Unit): Unit = {
    val thread = new Thread(
      () => {
        try { app.run(List("dev")); () }
        catch { case _: InterruptedException => () }
      },
      "eezo-dev"
    )
    thread.setDaemon(true)
    thread.start()
    try body(awaitPage(app.port))
    finally {
      thread.interrupt()
      thread.join(5000)
    }
  }

  /** `GET /` on `port`, retried while the server is still coming up. */
  private def awaitPage(port: Int): HttpResponse[String] = {
    val request  = HttpRequest.newBuilder(URI.create(s"http://localhost:$port/")).GET().build()
    val deadline = System.nanoTime() + 10_000_000_000L
    def attempt(): HttpResponse[String] =
      try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch {
        case _: IOException if System.nanoTime() < deadline =>
          Thread.sleep(50)
          attempt()
      }
    attempt()
  }

  test("no arguments runs boot with a Database installed, and uninstalls it after") {
    val app = new Recording
    assertEquals(app.run(Nil), 0)
    assert(app.booted)
    assert(app.bootedUnderDb, "boot on an EezoApp must see the installed Database")
    assert(!Installed.installed, "the Database is uninstalled once boot returns")
  }

  test("dev serves the app with the reload client on, under a Database, and never boots") {
    val app = new Recording
    developing(app) { page =>
      assertEquals(page.statusCode(), 200)
      assert(page.body().contains("/eezo/reload"), page.body())
      assertEquals(app.servedUnderDb, Some(true), "dev on an EezoApp serves under the Database")
    }
    assert(!app.booted, "dev is the dev server, not the program")
    assert(!Installed.installed, "the Database is uninstalled once the dev server stops")
  }

  test("dev runs the drift check before serving, and serves anyway when the database is down") {
    val app = new Recording {
      override def schema: Schema      = Widgets
      override def databaseUrl: String = "jdbc:postgresql://localhost:1/none"
    }
    val err   = new ByteArrayOutputStream()
    val saved = System.err
    System.setErr(new PrintStream(err))
    try
      developing(app) { page =>
        assert(err.toString.contains("drift check skipped, database unreachable"), err.toString)
        assertEquals(page.statusCode(), 200)
        assert(page.body().contains("/eezo/reload"), page.body())
      }
    finally System.setErr(saved)
    assert(!app.booted)
  }

  test("both edges' commands are known: routes needs no database, help lists both") {
    val app                     = new Recording
    val (routesCode, routes, _) = captured(app.run(List("routes")))
    assertEquals(routesCode, 0)
    assert(routes.contains("GET /"), routes)

    val (helpCode, help, _) = captured(app.run(List("help")))
    assertEquals(helpCode, 0)
    List("dev", "routes", "status", "sync", "freeze", "migrate").foreach { c =>
      assert(help.linesIterator.exists(_.trim.startsWith(c)), s"$c missing from:\n$help")
    }
    assert(!app.booted)
  }

  test("an unknown first argument is exit 2 on the umbrella too") {
    val (code, _, err) = captured(new Recording().run(List("deploy")))
    assertEquals(code, 2)
    assert(err.contains("unknown command: deploy"), err)
  }

  test(
    "a SchemaError out of the program is exit 1 with the line, and the Database is uninstalled"
  ) {
    // On `main` the whole dispatch sat under one `SchemaError` catch; the database edge's guard now
    // covers the chain after its own arms too, so the program keeps that answer.
    val app = new Recording {
      override def boot(): Unit = throw SchemaError("boom")
    }
    val (code, _, err) = captured(app.run(Nil))
    assertEquals(code, 1)
    assert(err.contains("boom"), err)
    assert(!Installed.installed, "the Database is uninstalled even when boot throws")
  }

  test("stacking the two edges by hand does not compile, in either order") {
    // `program` is concrete in both edges, so a trait that inherits both must override it and say
    // which; `EezoApp` says the database edge's. Without that override the compiler refuses the
    // stacking, and with it a user's own `Main` cannot boot with no `Database` installed by
    // writing the supertypes in the wrong order.
    val dbFirst = compileErrors("""
      object Wrong extends io.eezo.db.DbApp with io.eezo.http.HttpApp {
        override def schema: io.eezo.db.Schema       = io.eezo.db.Schema.empty
        override def routes: io.eezo.http.RouteTable = io.eezo.http.RouteTable.empty
      }
    """)
    val httpFirst = compileErrors("""
      object Wrong extends io.eezo.http.HttpApp with io.eezo.db.DbApp {
        override def schema: io.eezo.db.Schema       = io.eezo.db.Schema.empty
        override def routes: io.eezo.http.RouteTable = io.eezo.http.RouteTable.empty
      }
    """)
    List(dbFirst, httpFirst).foreach { e =>
      assert(!e.contains("Not found"), s"the snippet did not resolve, so it proves nothing: $e")
      assert(e.contains("conflicting members"), e)
      assert(e.contains("program"), e)
    }
  }
}

/** One table, so `dev`'s drift check has something to ask the database about. */
private case class Widget(id: Id[Widget], name: String) derives Table

private object Widgets extends Schema { val widgets = table[Widget] }
