package config

import io.eezo.core.{Config, Redacted}
import io.eezo.core.html.Url

/** SPIKE, seam C: `Configured` on `Main` still reads before the program, and the companion's
  * given forwards to what it installed, so a handler asks with `using Settings` and a test hands
  * one built directly.
  */
case class Settings(
    stripeSecretKey: Redacted,
    stripeWebhookSecret: Redacted,
    shopUrl: Url = Url.Absolute("http://localhost:8080")
) derives Config

object Settings {
  given Settings = Current[Settings]
}
