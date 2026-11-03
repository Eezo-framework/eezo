import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import io.eezo.config.Config
import io.eezo.core.Redacted
import io.eezo.core.html.Url
import io.eezo.http.{Forbidden, Method, Request, Response}

import shop.{CreateUserConfig, ShopConfig}
import stripe.Stripe

/** What the shop sends Stripe and what it accepts from Stripe, under values the suite installs.
  *
  * The values are built here, never read from the environment, so the suite proves that the code
  * reads the installed Config and not a variable some developer happens to have exported.
  */
class StripeSuite extends munit.FunSuite {

  private val installed = ShopConfig(
    stripe = Stripe(secretKey = Redacted("sk_test_suite"), webhookSecret = Redacted("whsec_suite")),
    shopUrl = Url.Absolute("http://localhost:8080"),
    createUser = CreateUserConfig(email = None, password = None)
  )

  test("the Checkout call carries the installed key as its Bearer token") {
    Config[ShopConfig].installed(installed) {
      assertEquals(Stripe.authorization, "Authorization" -> "Bearer sk_test_suite")
    }
  }

  test("Stripe sends the buyer back to the installed shop URL, with ?paid after paying") {
    Config[ShopConfig].installed(installed) {
      assertEquals(Stripe.returns, ("http://localhost:8080/?paid", "http://localhost:8080"))
    }
  }

  test("a shop URL that ends in a slash gives one slash before ?paid") {
    Config[ShopConfig].installed(installed.copy(shopUrl = Url.Absolute("https://shop.example/"))) {
      assertEquals(Stripe.returns, ("https://shop.example/?paid", "https://shop.example/"))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The webhook
  // ---------------------------------------------------------------------------------------------

  /** An event the handler acknowledges without touching an order, so no database is needed. */
  private val event = """{"type":"customer.created"}""".getBytes(UTF_8)

  /** Stripe's scheme, written out here rather than borrowed from `Stripe`, so the suite checks the
    * guard against the scheme and not against itself.
    */
  private def signature(secret: String, body: Array[Byte]): String = {
    val t   = "1700000000"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.update(s"$t.".getBytes(UTF_8))
    mac.update(body)
    s"t=$t,v1=${mac.doFinal().map("%02x".format(_)).mkString}"
  }

  private def webhook(signature: Option[String]): Response =
    Config[ShopConfig].installed(installed) {
      Main.routes.dispatch(
        Request(
          method = Method.POST,
          path = "/webhooks/stripe",
          query = Map.empty,
          headers = signature.fold(Map.empty)(s => Map("Stripe-Signature" -> Seq(s))),
          body = event,
          pathParams = Map.empty
        )
      )
    }

  test("a webhook signed with the installed secret is answered 200") {
    assertEquals(webhook(Some(signature("whsec_suite", event))).status, 200)
  }

  private val refusal = "the Stripe signature is missing or wrong"

  test("a webhook signed with another secret is refused before the handler runs") {
    val refused = intercept[Forbidden](webhook(Some(signature("whsec_other", event))))
    assertEquals(refused.getMessage, refusal)
  }

  test("a webhook with no signature is refused before the handler runs") {
    val refused = intercept[Forbidden](webhook(None))
    assertEquals(refused.getMessage, refusal)
  }
}
