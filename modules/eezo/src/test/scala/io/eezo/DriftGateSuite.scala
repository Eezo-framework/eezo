package io.eezo

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import io.eezo.core.html.Html
import io.eezo.core.internal.Json
import io.eezo.db.schema.Change
import io.eezo.http.*

/** The drift page's forms. What they post to is served through the same dispatch as any application
  * route, so a `POST` without the token is refused there; this suite pins the other half, that the
  * page hands the browser the token to return.
  */
class DriftGateSuite extends munit.FunSuite {

  private val drift = List(Change.DropColumn("posts", "body"))

  private def markup(token: Csrf.Token): String =
    DriftGate.refusal(drift, error = None, token = token).body match {
      case Body.Html(node) => node.render
      case other           => fail(s"expected an HTML body, got $other")
    }

  test("both forms on the drift page carry the CSRF token, since both apply changes") {
    val token  = Csrf.Token.gen()
    val page   = markup(token)
    val hidden = Csrf.hidden(token).render
    val forms  = page.split("<form").drop(1)
    assertEquals(forms.length, 2, page)
    forms.foreach(form => assert(form.contains(hidden), form))
  }

  test("the drift page is a whole document, so it goes out as written whatever the layout") {
    val token  = Csrf.Token.gen()
    val served = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/"),
          _ => DriftGate.refusal(drift, error = None, token = token)
        )
      )
    )
    val failing: Layout = (_, _, _) => throw new IllegalStateException("the drift page was framed")
    val server          = HttpServer.start(0, served, HttpConfig(layout = failing))
    try {
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create(s"http://localhost:${server.port}/")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
      assertEquals(response.statusCode(), 503)
      assertEquals(response.body(), markup(token))
      assert(clue(response.body()).startsWith(Html.doctype.render))
    } finally server.stop()
  }

  test("the drift marker is one line of the status shape, carrying the gate's blocking decision") {
    val blocking = DriftGate.marker(drift)
    assert(blocking.startsWith("eezo dev: drift "), blocking)
    assert(!blocking.contains('\n'))
    val json = Json.parse(blocking.stripPrefix("eezo dev: drift ")).fold(fail(_), identity)
    json match {
      case Json.Obj(fields) =>
        assertEquals(fields.collectFirst { case ("command", v) => v }, Some(Json.Str("status")))
        assertEquals(fields.collectFirst { case ("blocking", v) => v }, Some(Json.Bool(true)))
        assertEquals(fields.collectFirst { case ("inSync", v) => v }, Some(Json.Bool(false)))
      case other => fail(other.toString)
    }
  }

  test("additive drift and no drift are not blocking") {
    val additive = DriftGate.marker(
      List(
        Change.AddColumn(
          "posts",
          io.eezo.db.schema.ColumnSnap(
            "body",
            "text",
            nullable = true,
            primaryKey = false,
            checks = Nil,
            references = None
          )
        )
      )
    )
    assert(additive.contains("\"blocking\":false"), additive)
    val clean = DriftGate.marker(Nil)
    assert(clean.contains("\"inSync\":true") && clean.contains("\"blocking\":false"), clean)
  }
}
