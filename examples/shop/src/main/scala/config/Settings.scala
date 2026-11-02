package config

import io.eezo.config.Config
import io.eezo.core.Redacted
import io.eezo.core.html.Url

/** SPIKE (issue 393): the shop's one class, with its sections. */
case class Stripe(secretKey: Redacted, webhookSecret: Redacted) derives Config

case class Settings(stripe: Stripe, shopUrl: Url = Url.Absolute("http://localhost:8080"))
    derives Config
