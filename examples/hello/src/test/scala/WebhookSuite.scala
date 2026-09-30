import io.eezo.generated.Routes
import io.eezo.http.{Forbidden, Method, Request, Response}
import lib.Stripe

class WebhookSuite extends munit.FunSuite {

  private val table = Routes.table()

  private def post(body: String, headers: (String, String)*): Request =
    Request(
      Method.POST,
      "/webhooks/stripe",
      Map.empty,
      headers.map { case (k, v) => k -> Seq(v) }.toMap,
      body.getBytes("UTF-8"),
      Map.empty
    )

  test("a signed call from a browser never seen before reaches the handler, no token, no cookie") {
    val body     = """{"client_reference_id":"order-1"}"""
    val response = table.dispatch(post(body, "Stripe-Signature" -> Stripe.sign(body.getBytes("UTF-8"))))
    assertEquals(response.status, 200)
    assertEquals(response.session, None)
  }

  test("an unsigned call is refused before the handler") {
    val body = """{"client_reference_id":"order-1"}"""
    intercept[Forbidden](table.dispatch(post(body)))
  }

  test("a wrongly signed call is refused before the handler") {
    val body = """{"client_reference_id":"order-1"}"""
    intercept[Forbidden](table.dispatch(post(body, "Stripe-Signature" -> "deadbeef")))
  }

  test("a browser route still gets a session minted, the API route does not") {
    val page = table.dispatch(post("").copy(path = "/hello", method = Method.GET))
    assert(page.session.isDefined)
  }

  test("the listing marks the API route") {
    assert(table.routes.map(_.describe).contains("POST /webhooks/stripe api"), table.routes.map(_.describe))
  }
}
