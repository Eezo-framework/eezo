package config

import io.eezo.auth.Password
import io.eezo.core.Config

/** SPIKE: what the operator's tool reads, `SHOP_EMAIL` and `SHOP_PASSWORD`. */
case class Ops(shopEmail: String, shopPassword: Password) derives Config
