import com.typesafe.config.ConfigFactory

import io.eezo.config.Config
import io.eezo.core.html.Url

import shop.{CreateUserConfig, ShopConfig}

/** The shop's own `application.conf` read against the shop's own class, as boot reads it.
  *
  * Every read hands an environment map of its own rather than the process's, so a developer or a CI
  * runner with `STRIPE_SECRET_KEY` exported cannot turn a refusal into a success.
  */
class ShopConfigSuite extends munit.FunSuite {

  /** The file boot reads, the first `application.conf` on the classpath, parsed and unresolved. */
  private lazy val file = ConfigFactory.parseURL(getClass.getResource("/application.conf"))

  test("without the two Stripe variables the read is refused, each path beside its variable") {
    val refused = Config[ShopConfig].read(file, Map.empty).left.map(_.lines)
    assertEquals(
      refused,
      Left(
        Seq(
          "stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it",
          "stripe.webhookSecret is missing: set STRIPE_WEBHOOK_SECRET, which application.conf " +
            "names for it"
        )
      )
    )
  }

  /** The two Stripe variables set, so a refusal can only come from the values a test adds. */
  private val stripe =
    Map("STRIPE_SECRET_KEY" -> "sk_test_suite", "STRIPE_WEBHOOK_SECRET" -> "whsec_suite")

  test("with only the two Stripe variables the shop boots on the laptop's address, no account") {
    val read = Config[ShopConfig].read(file, stripe).map(c => (c.shopUrl, c.createUser))
    assertEquals(
      read,
      Right((Url.Absolute("http://localhost:8080"), CreateUserConfig(None, None)))
    )
  }

  test("a SHOP_PASSWORD bcrypt cannot read is refused by the read with the password's own reason") {
    val refused =
      Config[ShopConfig].read(file, stripe + ("SHOP_PASSWORD" -> "e" * 73)).left.map(_.lines)
    assertEquals(refused, Left(Seq("createUser.password is longer than the 72 bytes bcrypt reads")))
  }

  test("config lists the Stripe secrets as redacted and required, the rest as not required") {
    assertEquals(
      Config[ShopConfig].names(file),
      Seq(
        Config.Name("STRIPE_SECRET_KEY", "stripe.secretKey", redacted = true, Config.Need.Required),
        Config.Name(
          "STRIPE_WEBHOOK_SECRET",
          "stripe.webhookSecret",
          redacted = true,
          Config.Need.Required
        ),
        Config.Name("SHOP_URL", "shopUrl", redacted = false, Config.Need.Defaulted),
        Config.Name("SHOP_EMAIL", "createUser.email", redacted = false, Config.Need.Optional),
        Config.Name("SHOP_PASSWORD", "createUser.password", redacted = false, Config.Need.Optional)
      )
    )
  }
}
