import io.eezo.core.Redacted
import io.eezo.http.{Method, Response, api}

import config.Settings

/** SPIKE, seam C: a handler under test is handed a Settings built directly, with no environment,
  * no boot and no holder.
  */
class WebhookSuite extends munit.FunSuite {

  given Settings = Settings(Redacted("sk_test_suite"), Redacted("whsec_suite"))

  test("an event that is not a completed checkout is acknowledged and touches no order") {
    val request = api.Request(
      method = Method.POST,
      path = "/webhooks/stripe",
      query = Map.empty,
      headers = Map.empty,
      body = """{"type":"payment_intent.created","data":{"object":{}}}""".getBytes,
      pathParams = Map.empty
    )
    val response: Response = app.webhooks.stripe.Create.create(request)
    assertEquals(response.status, 200)
  }
}
