package shop

import io.eezo.auth.Password
import io.eezo.config.Config
import io.eezo.core.html.Url

import stripe.Stripe

/** The shop's own values, read from `src/main/resources/application.conf` before the shop starts.
  * The build names this class with `eezoConfigClass`, so boot refuses to start the server or the
  * operator's job while a value it needs is missing.
  *
  * `shopUrl` holds the laptop's address as its default, here and not in the file, so the class is
  * the one place that says what an unset `SHOP_URL` means.
  */
final case class ShopConfig(
    stripe: Stripe,
    shopUrl: Url = Url.Absolute("http://localhost:8080"),
    createUser: CreateUserConfig
) derives Config

/** What [[CreateUser]] needs and nothing else does. Both fields are optional, so the web server
  * boots without them and a deployment is not asked for an account it never creates; the job
  * refuses an absent one itself.
  */
final case class CreateUserConfig(email: Option[String], password: Option[Password]) derives Config
