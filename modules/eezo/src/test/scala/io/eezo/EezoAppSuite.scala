package io.eezo

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import io.eezo.core.html.Tags.p
import io.eezo.db.Schema
import io.eezo.db.engine.Installed
import io.eezo.http.{Handler, Method, PathPattern, Response, Route, RouteTable}

/** The umbrella's entry trait: both edges stacked, `EezoApp extends HttpApp with DbApp`.
  *
  * The order is load-bearing and the trait is written once, so what this suite pins is what the
  * order buys: `sbt run` on an `EezoApp` is the database edge's `Nil` arm, which installs a
  * `Database` around the http edge's `boot`. No Postgres is needed: `Database.connect` never
  * touches the network, so a bogus URL installs and closes without a query.
  */
class EezoAppSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(p("ok"))

  /** An application that records, instead of serving, whether a `Database` was installed. */
  private class Recording extends EezoApp {
    var booted        = false
    var bootedUnderDb = false

    override def schema: Schema     = Schema.empty
    override def routes: RouteTable =
      RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), ok)))

    override def databaseUrl: String = "jdbc:postgresql://nowhere:1/none"

    override def boot(): Unit = {
      booted = true
      bootedUnderDb =
        try { Installed.get; true }
        catch { case _: IllegalStateException => false }
    }
  }

  private def captured(body: => Int): (Int, String, String) = {
    val out  = new ByteArrayOutputStream()
    val err  = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      Console.withErr(new PrintStream(err)) { body }
    }
    (code, out.toString, err.toString)
  }

  test("no arguments runs boot with a Database installed, and uninstalls it after") {
    val app = new Recording
    assertEquals(app.run(Nil), 0)
    assert(app.booted)
    assert(app.bootedUnderDb, "boot on an EezoApp must see the installed Database")
    assert(
      try { Installed.get; false }
      catch { case _: IllegalStateException => true },
      "the Database is uninstalled once boot returns"
    )
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
}
