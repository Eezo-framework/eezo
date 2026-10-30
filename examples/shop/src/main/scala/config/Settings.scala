package config

import io.eezo.core.{Config, Redacted}
import io.eezo.core.html.Url

/** SPIKE, seam A: the using signature. The companion's given is what every handler's `using
  * Settings` resolves to from the generated table, and reading it is what refuses the boot.
  */
case class Settings(
    stripeSecretKey: Redacted,
    stripeWebhookSecret: Redacted,
    shopUrl: Url = Url.Absolute("http://localhost:8080")
) derives Config

object Settings {
  given Settings = {
    println("[spike] Settings read from the environment")
    Config[Settings]
      .read()
      .fold(refused => throw new IllegalStateException(refused.message), identity)
  }
}
