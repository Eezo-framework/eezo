package app.webhooks.stripe

import io.eezo.http.{ApiRequest, Body, Guarded, Response}
import lib.Stripe

/** SPIKE: `app/webhooks/stripe/Create.scala` mounts `POST /webhooks/stripe`. An API route,
  * because the handler takes an `ApiRequest`: no session takes part, so no CSRF token is checked.
  */
object Create {

  /** Whoever signs as Stripe may call it. */
  given Guarded[Create.type] = Stripe.signed

  def create(request: ApiRequest): Response = {
    val body = new String(request.body, "UTF-8")
    // `Response.Ok` takes Html; a plain text answer is built by hand today.
    Response(200, Seq("Content-Type" -> "text/plain; charset=utf-8"), Body.Bytes(s"paid: $body".getBytes("UTF-8")))
  }
}
