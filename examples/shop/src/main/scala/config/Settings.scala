package config

import io.eezo.core.{Config, Redacted}
import io.eezo.core.html.Url

/** SPIKE, seam B: the entry trait member. Nothing in the companion; `Configured` on `Main` reads
  * it before the program and installs it where a handler can reach it.
  */
case class Settings(
    stripeSecretKey: Redacted,
    stripeWebhookSecret: Redacted,
    shopUrl: Url = Url.Absolute("http://localhost:8080")
) derives Config
