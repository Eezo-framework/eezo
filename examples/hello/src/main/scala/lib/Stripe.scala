package lib

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import io.eezo.http.{Forbidden, Guarded, Request, Route}

/** SPIKE: the skeleton's half of the webhook. Stripe's scheme stays in the application; eezo
  * only sees a `Guarded`, the same kind of declaration the password guard produces.
  */
object Stripe {

  /** The webhook secret, `whsec_...` in a real deployment. Read from the environment there. */
  val secret: String = sys.env.getOrElse("STRIPE_WEBHOOK_SECRET", "whsec_spike")

  /** Stripe's `Stripe-Signature` header, simplified to the HMAC of the body for the spike. The
    * real one carries a timestamp and `v1=` pairs; the shape of the check is the same.
    */
  def sign(body: Array[Byte]): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(body).map("%02x".format(_)).mkString
  }

  private def verified(request: Request): Boolean =
    request.header("Stripe-Signature").exists { given_ =>
      MessageDigest.isEqual(
        given_.getBytes(StandardCharsets.UTF_8),
        sign(request.body).getBytes(StandardCharsets.UTF_8)
      )
    }

  /** Who may call the webhook: whoever signs as Stripe. A `Guarded` built by hand: no action is
    * covered (a page has none), the wrapper refuses before the handler, no login page is carried,
    * nobody is named.
    */
  def signed[A]: Guarded[A] =
    Guarded(
      actions = Set.empty,
      through = {
        case route: Route.Http =>
          route.copy(handler = request =>
            if (verified(request)) route.handler(request)
            else throw Forbidden("the Stripe signature is missing or wrong")
          )
        case other => other
      },
      carries = Seq.empty
    )
}
