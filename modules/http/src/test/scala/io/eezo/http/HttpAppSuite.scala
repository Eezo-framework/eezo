package io.eezo.http

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import io.eezo.core.html.Tags.p

/** The http edge's entry trait: what `sbt "run <command>"` answers on an application that has only
  * this edge.
  *
  * The server itself is `EezoServerSuite`'s to test. What this suite pins is the dispatch: the
  * empty argument list is the application, `dev` goes through the `devServer` hook the umbrella
  * overrides, `routes` needs no server, and the database edge's commands do not exist here.
  */
class HttpAppSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(p("ok"))

  private val table: RouteTable =
    RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), ok)))

  /** An application that records which hook ran instead of binding a port. */
  private class Recording extends HttpApp {
    var booted    = false
    var developed = false

    override def routes: RouteTable = table

    override def boot(): Unit = booted = true

    override protected def devServer(): Unit = developed = true
  }

  private def captured(body: => Int): (Int, String, String) = {
    val out  = new ByteArrayOutputStream()
    val err  = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      Console.withErr(new PrintStream(err)) { body }
    }
    (code, out.toString, err.toString)
  }

  test("no arguments is the application: boot runs") {
    val app = new Recording
    assertEquals(app.run(Nil), 0)
    assert(app.booted)
    assert(!app.developed)
  }

  test("dev runs the dev server hook, not boot") {
    val app = new Recording
    assertEquals(app.run(List("dev")), 0)
    assert(app.developed)
    assert(!app.booted)
  }

  test("routes prints the listing without a server, as text and as JSON") {
    val app             = new Recording
    val (code, text, _) = captured(app.run(List("routes")))
    assertEquals(code, 0)
    assert(text.contains("1 route:"), text)
    assert(text.contains("GET /"), text)

    val (jsonCode, json, _) = captured(app.run(List("routes", "--json")))
    assertEquals(jsonCode, 0)
    assert(json.contains("\"command\": \"routes\""), json)
    assert(!app.booted)
  }

  test("the database edge's commands are unknown on an http-only application") {
    val app = new Recording
    List("status", "sync", "freeze", "migrate", "reset", "drop", "dump", "ddl").foreach { command =>
      val (code, out, err) = captured(app.run(List(command)))
      assertEquals(code, 2, command)
      assertEquals(out, "", command)
      assert(err.contains(s"unknown command: $command"), err)
    }
    assert(!app.booted)
  }

  test("help names this edge's two commands") {
    val (code, out, _) = captured(new Recording().run(List("help")))
    assertEquals(code, 0)
    assert(out.contains("dev"), out)
    assert(out.contains("routes"), out)
    assert(!out.contains("status"), out)
  }

  test("every server setting is an override with the server's own default") {
    val app = new Recording
    assertEquals(app.port, 8080)
    assertEquals(app.maxBodySize, Config.DefaultMaxBodySize)
    assert(!app.problems.isDefinedAt(new RuntimeException("anything")))
  }
}
