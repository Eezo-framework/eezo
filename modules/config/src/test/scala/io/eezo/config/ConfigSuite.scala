package io.eezo.config

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions, ConfigResolver, ConfigValue}

import io.eezo.core.Redacted
import io.eezo.core.html.Url

/** SPIKE (issue 393): the derivation over a tree, on the shop's file, with the library's own
  * behaviour printed where the ticket asks what it exposes.
  */
class ConfigSuite extends munit.FunSuite {
  import ConfigSuite.*

  val shopFile = ConfigFactory.parseResources("shop.conf")

  test("substitutions come out of the unresolved tree, by the path they feed, in file order") {
    val subs = Config.substitutions(shopFile)
    println(s"[spike] substitutions of shop.conf:\n  ${subs.mkString("\n  ")}")
    assertEquals(
      subs,
      List(
        Config.Substitution("stripe.secretKey", "STRIPE_SECRET_KEY", optional = true),
        Config.Substitution("stripe.webhookSecret", "STRIPE_WEBHOOK_SECRET", optional = true),
        Config.Substitution("shopUrl", "SHOP_URL", optional = true)
      )
    )
  }

  test("the resolver hook sees the variable and not the path it feeds") {
    var looked = List.empty[String]
    val recorder: ConfigResolver = new ConfigResolver {
      def lookup(path: String): ConfigValue = { looked = looked :+ path; null }
      def withFallback(f: ConfigResolver): ConfigResolver = this
    }
    shopFile.resolve(
      ConfigResolveOptions
        .defaults()
        .setUseSystemEnvironment(false)
        .setAllowUnresolved(true)
        .appendResolver(recorder)
    )
    println(s"[spike] the resolver hook was asked for: $looked")
    assertEquals(looked, List("STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "SHOP_URL"))
  }

  test("a file plus a map reads the whole tree, sections included, with no environment") {
    val read = Config[Settings].read(
      shopFile,
      Map(
        "STRIPE_SECRET_KEY"     -> "sk_test",
        "STRIPE_WEBHOOK_SECRET" -> "whsec_test",
        "SHOP_URL"              -> "https://shop.example"
      )
    )
    assertEquals(
      read,
      Right(
        Settings(Stripe(Redacted("sk_test"), Redacted("whsec_test")), Url.Absolute("https://shop.example"))
      )
    )
  }

  test("an unset variable leaves the literal before it, and the default where there is none") {
    val read = Config[Settings].read(
      shopFile,
      Map("STRIPE_SECRET_KEY" -> "sk_test", "STRIPE_WEBHOOK_SECRET" -> "whsec_test")
    )
    assertEquals(read.map(_.shopUrl), Right(Url.Absolute("http://localhost:8080")))
  }

  test("the real environment is never consulted") {
    val read = Config[Settings].read(shopFile, Map.empty)
    println(s"[spike] refusal with an empty map:\n${read.left.map(_.message).merge}")
    assertEquals(
      read,
      Left(
        Config.Refused(
          List(
            Config.Offender.Missing("stripe.secretKey", List("STRIPE_SECRET_KEY")),
            Config.Offender.Missing("stripe.webhookSecret", List("STRIPE_WEBHOOK_SECRET"))
          )
        )
      )
    )
  }

  test("every offender at once: a path the file never states, a malformed leaf, a section as a value") {
    val file = ConfigFactory.parseString("""
      stripe = "not a section"
      shopUrl = "not a url"
    """)
    val read = Config[Settings].read(file, Map.empty)
    println(s"[spike] refusal of a wrong file:\n${read.left.map(_.message).merge}")
    assertEquals(
      read,
      Left(
        Config.Refused(
          List(
            Config.Offender.Malformed("stripe", "is a value, and a section was expected"),
            Config.Offender.Malformed("shopUrl", "is not a url")
          )
        )
      )
    )
  }

  test("a section the file never states is every required field of it, with no variable to set") {
    val read = Config[Settings].read(ConfigFactory.empty(), Map.empty)
    assertEquals(
      read.left.map(_.message),
      Left(
        "stripe.secretKey is missing: application.conf never states it\n" +
          "stripe.webhookSecret is missing: application.conf never states it"
      )
    )
  }

  test("a leaf is read as the text the tree renders for it: a number, a bare string, a quoted one") {
    val file = ConfigFactory.parseString("""
      port = 8080
      name = bare words here
      quoted = "with = sign"
    """)
    assertEquals(Config[Plain].read(file, Map.empty), Right(Plain(8080, "bare words here", "with = sign")))
  }

  test("install puts the root and every section under its own Config") {
    val value = Settings(Stripe(Redacted("k"), Redacted("w")), Url.Absolute("https://x"))
    Config[Settings].installed(value) {
      assertEquals(Config[Stripe].current, value.stripe)
      assertEquals(Config[Settings].current, value)
    }
    intercept[IllegalStateException](Config[Settings].current)
  }

  test("the root value compiles with no file check when the build hands the macro no path") {
    val root = Config.root[Settings]
    assertEquals(root.resource, "application.conf")
  }
}

object ConfigSuite {

  final case class Stripe(secretKey: Redacted, webhookSecret: Redacted) derives Config

  final case class Settings(stripe: Stripe, shopUrl: Url = Url.Absolute("http://localhost:8080"))
      derives Config

  final case class Plain(port: Int, name: String, quoted: String) derives Config
}
