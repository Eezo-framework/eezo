import io.eezo.config.Config
import io.eezo.core.Redacted

import stripe.Stripe

/** SPIKE (issue 393): a suite installs only the section it tests, built directly, with no file, no
  * environment and no boot.
  */
class StripeSectionSuite extends munit.FunSuite {

  test("the Stripe object reads the section a suite installed") {
    Config[config.Stripe].installed(config.Stripe(Redacted("sk_test_suite"), Redacted("whsec_suite"))) {
      assertEquals(Stripe.key, "sk_test_suite")
    }
  }

  test("outside the scope, nothing was read") {
    intercept[IllegalStateException](Stripe.key)
  }
}
