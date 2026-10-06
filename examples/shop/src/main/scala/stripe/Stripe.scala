package stripe

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import io.eezo.http.{Forbidden, Guarded, Route}

object Stripe {

  val key: String = sys.env("STRIPE_SECRET_KEY")

  private val webhookSecret: String = sys.env("STRIPE_WEBHOOK_SECRET")

  /** Where Stripe sends the buyer afterwards: this application's own address, `SHOP_URL` on the
    * deployed copy.
    */
  val back: String = sys.env.getOrElse("SHOP_URL", "http://localhost:8080")

  /** Who may call the webhook: a caller whose `Stripe-Signature` matches the raw body. Anyone else
    * is refused with 403 before the handler runs. The wrapper sees the `Request`; the handler after
    * it sees the `ApiRequest`.
    */
  def signed[A]: Guarded[A] =
    Guarded(
      actions = Set.empty,
      through = {
        case route: Route.Http =>
          route.copy(handler =
            request =>
              if (verified(request.header("Stripe-Signature"), request.body)) route.handler(request)
              else throw Forbidden("the Stripe signature is missing or wrong")
          )
        case other => other
      },
      carries = Seq.empty
    )

  /** Stripe's scheme: `t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">`. */
  private def verified(header: Option[String], body: Array[Byte]): Boolean =
    header.exists { value =>
      val parts = value.split(",").map(_.split("=", 2)).collect { case Array(k, v) => k -> v }.toMap
      (parts.get("t"), parts.get("v1")) match {
        case (Some(t), Some(v1)) =>
          val mac = Mac.getInstance("HmacSHA256")
          mac.init(new SecretKeySpec(webhookSecret.getBytes(UTF_8), "HmacSHA256"))
          mac.update(s"$t.".getBytes(UTF_8))
          mac.update(body)
          val expected = mac.doFinal().map("%02x".format(_)).mkString
          MessageDigest.isEqual(expected.getBytes(UTF_8), v1.getBytes(UTF_8))
        case _ => false
      }
    }

  /** The `url` of a Checkout session reply: where the browser goes to pay. */
  def sessionUrl(json: String): String = ujson.read(json)("url").str

  /** The order id of a `checkout.session.completed` event, `None` for any other event. */
  def completed(body: Array[Byte]): Option[UUID] = {
    val event = ujson.read(body)
    Option.when(event("type").str == "checkout.session.completed")(
      UUID.fromString(event("data")("object")("client_reference_id").str)
    )
  }
}
