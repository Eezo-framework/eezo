package io.eezo.http

import io.eezo.core.html.Tags.p
import io.eezo.core.internal.Json
import io.eezo.core.support.Captured.captured

/** The marker lines the dev console reads off the application's stdout: one line each, the
  * documented JSON shape compacted, printed only when the plugin asked.
  */
class DevMarkersSuite extends munit.FunSuite {

  private val ok: Handler = _ => Response.Ok(p("ok"))

  private val table: RouteTable =
    RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), ok)))

  test("a marker is one line, whatever the value holds") {
    val line = DevMarkers.line("drift", Json.Obj(List("sql" -> Json.Str("a\nb"))))
    assertEquals(line, "eezo dev: drift {\"sql\":\"a\\nb\"}")
    assert(!line.contains('\n'))
  }

  test("the markers are on only when the variable is set and non empty") {
    assert(DevMarkers.enabledIn(Map(DevMarkers.EnvVar -> "1")))
    assert(!DevMarkers.enabledIn(Map(DevMarkers.EnvVar -> "")))
    assert(!DevMarkers.enabledIn(Map.empty))
  }

  test("a dev server with markers on prints the route table as a value beside the listing") {
    val (_, out, _) = captured {
      val server =
        HttpServer.start(port = 0, routes = table, config = HttpConfig(dev = true, markers = true))
      server.stop()
      0
    }
    val marker = out.linesIterator.find(_.startsWith("eezo dev: routes ")).getOrElse(fail(out))
    val json   = Json.parse(marker.stripPrefix("eezo dev: routes ")).fold(fail(_), identity)
    json match {
      case Json.Obj(fields) =>
        assertEquals(fields.collectFirst { case ("command", v) => v }, Some(Json.Str("routes")))
        assert(
          fields.exists { case ("routes", Json.Arr(rs)) => rs.size == 1; case _ => false },
          marker
        )
      case other => fail(other.toString)
    }
    assert(out.contains("1 route:"), out)
  }

  test("a dev server without markers prints the listing alone") {
    val (_, out, _) = captured {
      val server = HttpServer.start(port = 0, routes = table, config = HttpConfig(dev = true))
      server.stop()
      0
    }
    assert(!out.contains("eezo dev:"), out)
    assert(out.contains("1 route:"), out)
  }
}
