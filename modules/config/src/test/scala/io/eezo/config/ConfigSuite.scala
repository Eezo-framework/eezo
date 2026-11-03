package io.eezo.config

import com.typesafe.config.ConfigFactory

import io.eezo.core.Redacted
import io.eezo.core.html.Url

/** A Config read from a parsed file and an explicit map, never the process's environment, so a
  * suite run on a machine with a STRIPE_SECRET_KEY set cannot pass or fail because of it.
  */
class ConfigSuite extends munit.FunSuite {
  import ConfigSuite.*

  private val shop = ConfigFactory.parseResources("shop.conf")

  private val keys = Map("STRIPE_SECRET_KEY" -> "sk_test", "STRIPE_WEBHOOK_SECRET" -> "whsec_test")

  test("a class with a section reads every leaf at its full dotted path") {
    assertEquals(
      Config[ShopConfig].read(shop, keys + ("SHOP_URL" -> "https://shop.example")),
      Right(
        ShopConfig(
          8080,
          Stripe(Redacted("sk_test"), Redacted("whsec_test")),
          Url.Absolute("https://shop.example")
        )
      )
    )
  }

  test("a number in the file reaches the Parser as the text the tree renders for it") {
    assertEquals(Config[Raw].read(shop, keys), Right(Raw(Echo("8080"))))
  }

  test("one read names every offender at once, in declaration order") {
    val file = ConfigFactory.parseString("""
      port = "abc"
      stripe { webhookSecret = ${?STRIPE_WEBHOOK_SECRET} }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map.empty),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("port", "is not a number"),
            Config.Offender.Missing("stripe.secretKey", Nil),
            Config.Offender.Missing("stripe.webhookSecret", List("STRIPE_WEBHOOK_SECRET"))
          )
        )
      )
    )
  }

  test("the message has one line per offender, the path first, naming the fix") {
    val file = ConfigFactory.parseString("""
      port = "abc"
      stripe { webhookSecret = ${?STRIPE_WEBHOOK_SECRET} }
    """)
    assertNoDiff(
      Config[ShopConfig].read(file, Map.empty).left.map(_.message).merge.toString,
      """|port is not a number
         |stripe.secretKey is missing: add it to application.conf
         |stripe.webhookSecret is missing: set STRIPE_WEBHOOK_SECRET, which application.conf names for it
         |""".stripMargin
    )
  }

  test("a path fed by two variables names both, joined with or") {
    val file = ConfigFactory.parseString("""
      banner = ${?BANNER}
      banner = ${?SHOP_BANNER}
    """)
    val refused = Config[Plain].read(file, Map.empty).left.toOption.get
    assertEquals(
      refused.offenders,
      Seq(Config.Offender.Missing("banner", List("BANNER", "SHOP_BANNER")))
    )
    assertNoDiff(
      refused.message,
      "banner is missing: set BANNER or SHOP_BANNER, which application.conf names for it"
    )
  }

  test("an object or a list where one value was expected is malformed, named by its path") {
    val file = ConfigFactory.parseString("""
      port { value = 1 }
      stripe { secretKey = [1, 2], webhookSecret = ${?W} }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("port", "is an object, and one value was expected"),
            Config.Offender.Malformed("stripe.secretKey", "is a list, and one value was expected")
          )
        )
      )
    )
  }

  test("a section stated as a value is one malformed offender at the section, not one per field") {
    val file = ConfigFactory.parseString("""
      port = 1
      stripe = "sk"
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map.empty),
      Left(
        Config.Refused(
          Seq(Config.Offender.Malformed("stripe", "is a value, and a section was expected"))
        )
      )
    )
  }

  test("an absent field with a default reads the default, an absent Option reads None") {
    assertEquals(
      Config[Shop].read(ConfigFactory.parseString("debug = yes"), Map.empty),
      Right(Shop(debug = true))
    )
  }

  test("an absent field with no default is missing, a Boolean included, whatever its Parser says") {
    assertEquals(summon[io.eezo.core.Parser[Boolean]].absent, Some(false))
    assertEquals(
      Config[Shop].read(ConfigFactory.empty(), Map.empty),
      Left(Config.Refused(Seq(Config.Offender.Missing("debug", Nil))))
    )
  }
  test("read never consults the process's environment, only the map") {
    val file = ConfigFactory.parseString("banner = ${?HOME}")
    assertEquals(Config[Plain].read(file, Map("HOME" -> "/home/shop")), Right(Plain("/home/shop")))
    assertEquals(
      Config[Plain].read(file, Map.empty),
      Left(Config.Refused(Seq(Config.Offender.Missing("banner", List("HOME")))))
    )
  }

  test("a literal merged with a substitution reads the literal until the map sets the variable") {
    assertEquals(
      Config[ShopConfig].read(shop, keys).map(_.shopUrl),
      Right(Url.Absolute("http://localhost:8080"))
    )
    assertEquals(
      Config[ShopConfig].read(shop, keys + ("SHOP_URL" -> "https://shop.example")).map(_.shopUrl),
      Right(Url.Absolute("https://shop.example"))
    )
  }

  test("the resolved tree holds the file's paths and no key that comes from the map") {
    val resolved = Config.resolve(shop, keys + ("SHOP_URL" -> "https://shop.example"))
    assertEquals(resolved.getString("shopUrl"), "https://shop.example")
    assertEquals(
      resolved.root().keySet,
      java.util.Set.of("port", "stripe", "shopUrl")
    )
  }

  test("a required substitution left unset is a missing offender beside the others, not a throw") {
    val file = ConfigFactory.parseString("""
      port = "x"
      stripe { secretKey = ${STRIPE_KEY}, webhookSecret = ${STRIPE_HOOK} }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map.empty),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("port", "is not a number"),
            Config.Offender.Missing("stripe.secretKey", List("STRIPE_KEY")),
            Config.Offender.Missing("stripe.webhookSecret", List("STRIPE_HOOK"))
          )
        )
      )
    )
    assertEquals(
      Config[ShopConfig].read(file, Map("STRIPE_KEY" -> "k", "STRIPE_HOOK" -> "h")).map(_.stripe),
      Left(Config.Refused(Seq(Config.Offender.Malformed("port", "is not a number"))))
    )
  }

  test("names lists one Name per substitution of the file, in file order, by the path it feeds") {
    import Config.Need.*
    assertEquals(
      Config[ShopConfig].names(shop),
      Seq(
        Config.Name("STRIPE_SECRET_KEY", "stripe.secretKey", redacted = true, Required),
        Config.Name("STRIPE_WEBHOOK_SECRET", "stripe.webhookSecret", redacted = true, Required),
        Config.Name("SHOP_URL", "shopUrl", redacted = false, Defaulted)
      )
    )
  }

  test("names reads redaction and need from the class, and lists a path no field reads as plain") {
    import Config.Need.*
    val file = ConfigFactory.parseString("""
      debug = ${?DEBUG}
      webhook = ${?WEBHOOK}
      banner = ${?BANNER}
      port = ${?PORT}
      extra = ${?EXTRA}
      home = "/srv"
      inside = ${home}
    """)
    assertEquals(
      Config[Shop].names(file),
      Seq(
        Config.Name("DEBUG", "debug", redacted = false, Required),
        Config.Name("WEBHOOK", "webhook", redacted = true, Optional),
        Config.Name("BANNER", "banner", redacted = false, Optional),
        Config.Name("PORT", "port", redacted = false, Defaulted),
        Config.Name("EXTRA", "extra", redacted = false, Optional)
      )
    )
  }

  test("a leaf of a section merged with a substitution is named at its own path") {
    val replaced = ConfigFactory.parseString("""
      port = 1
      stripe { secretKey = ${?STRIPE_SECRET_KEY}, webhookSecret = ${?W} }
      stripe = ${?STRIPE}
    """)
    val inherited = ConfigFactory.parseString("""
      port = 1
      base { webhookSecret = ${?W} }
      stripe = ${base}
      stripe { secretKey = ${?STRIPE_SECRET_KEY} }
    """)
    Seq(replaced, inherited).foreach { file =>
      assertEquals(
        Config[ShopConfig].names(file).filter(_.variable == "STRIPE_SECRET_KEY"),
        Seq(
          Config.Name(
            "STRIPE_SECRET_KEY",
            "stripe.secretKey",
            redacted = true,
            Config.Need.Required
          )
        )
      )
      assertEquals(
        Config[ShopConfig].read(file, Map("W" -> "w")),
        Left(
          Config.Refused(
            Seq(Config.Offender.Missing("stripe.secretKey", List("STRIPE_SECRET_KEY")))
          )
        )
      )
    }
  }

  test("a variable copied into a section by a reference is named at the path the field reads") {
    val file = ConfigFactory.parseString("""
      port = 1
      base { secretKey = ${?STRIPE_SECRET_KEY}, webhookSecret = ${?W} }
      stripe = ${base}
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(
        Config.Refused(
          Seq(Config.Offender.Missing("stripe.secretKey", List("STRIPE_SECRET_KEY")))
        )
      )
    )
    assert(
      Config[ShopConfig]
        .names(file)
        .exists(n =>
          n.variable == "STRIPE_SECRET_KEY" && n.path == "stripe.secretKey" && n.redacted
        ),
      Config[ShopConfig].names(file)
    )
  }

  test("a substitution that feeds a whole section is named at the section") {
    val file = ConfigFactory.parseString("""
      stripe { secretKey = ${?STRIPE_SECRET_KEY} }
      stripe = ${?STRIPE}
    """)
    assertEquals(
      Config[ShopConfig].names(file).map(name => name.variable -> name.path),
      Seq("STRIPE_SECRET_KEY" -> "stripe.secretKey", "STRIPE" -> "stripe")
    )
  }

  test("a substitution of a variable under EEZO_ is refused, naming the path and the variable") {
    val file = ConfigFactory.parseString("banner = ${?EEZO_SECRET}")
    val read = Config[Plain].read(file, Map("EEZO_SECRET" -> "framework-only"))
    assertEquals(
      read,
      Left(Config.Refused(Seq(Config.Offender.Reserved("banner", "EEZO_SECRET"))))
    )
    assertNoDiff(
      read.left.map(_.message).merge.toString,
      "banner is fed from EEZO_SECRET, and EEZO_ is the framework's prefix for its own variables: " +
        "name another variable in application.conf"
    )
  }

  test("a path fed only from an EEZO_ variable left unset is missing with no variable to set") {
    val read = Config[Plain].read(ConfigFactory.parseString("banner = ${?EEZO_SECRET}"), Map.empty)
    assertEquals(
      read,
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Reserved("banner", "EEZO_SECRET"),
            Config.Offender.Missing("banner", Nil)
          )
        )
      )
    )
    assertNoDiff(
      read.left.map(_.message).merge.toString,
      "banner is fed from EEZO_SECRET, and EEZO_ is the framework's prefix for its own variables: " +
        "name another variable in application.conf\n" +
        "banner is missing: add it to application.conf"
    )
  }

  test("a Redacted path stated as a literal, alone or merged with a substitution, is refused") {
    val alone = ConfigFactory.parseString("""
      port = 1
      stripe { secretKey = "sk_live_alone", webhookSecret = ${?W} }
    """)
    val merged = ConfigFactory.parseString("""
      port = 1
      stripe {
        secretKey = "sk_live_merged"
        secretKey = ${?K}
        webhookSecret = ${?W}
      }
    """)
    Seq(alone -> "sk_live_alone", merged -> "sk_live_merged").foreach { (file, secret) =>
      val read = Config[ShopConfig].read(file, Map("K" -> "k", "W" -> "w"))
      assertEquals(read, Left(Config.Refused(Seq(Config.Offender.Literal("stripe.secretKey")))))
      val refused = read.left.toOption.get
      assertNoDiff(
        refused.message,
        "stripe.secretKey is stated as a literal in application.conf, and it is a Redacted: a " +
          "Redacted value reaches the file only through a variable"
      )
      (refused.toString +: refused.offenders.map(_.toString)).foreach { text =>
        assert(!text.contains(secret), text)
      }
    }
  }

  test("a Redacted literal under a section merged with a substitution is still refused") {
    val file = ConfigFactory.parseString("""
      port = 1
      stripe { secretKey = "sk_live_hidden", webhookSecret = ${?W} }
      stripe = ${?STRIPE}
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(Config.Refused(Seq(Config.Offender.Literal("stripe.secretKey"))))
    )
  }

  test("a Redacted literal copied into a section by a reference inside the file is refused") {
    val file = ConfigFactory.parseString("""
      port = 1
      base { secretKey = "sk_live_hidden", webhookSecret = ${?W} }
      stripe = ${base}
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(Config.Refused(Seq(Config.Offender.Literal("stripe.secretKey"))))
    )
  }

  test("a Redacted literal reached through a reference is refused also when a variable is set") {
    val scalar = ConfigFactory.parseString("""
      port = 1
      source = "sk_live_committed"
      stripe {
        secretKey = ${source}
        secretKey = ${?K}
        webhookSecret = ${?W}
      }
    """)
    val section = ConfigFactory.parseString("""
      port = 1
      base { secretKey = "sk_live_committed", webhookSecret = ${?W} }
      stripe = ${base}
      stripe { secretKey = ${?K} }
    """)
    for (file <- Seq(scalar, section); k <- Seq(Map("K" -> "k"), Map.empty[String, String])) {
      val read = Config[ShopConfig].read(file, k + ("W" -> "w"))
      assertEquals(read, Left(Config.Refused(Seq(Config.Offender.Literal("stripe.secretKey")))))
      val refused = read.left.toOption.get
      (refused.message +: refused.offenders.map(_.toString)).foreach { text =>
        assert(!text.contains("sk_live_committed"), text)
      }
    }
  }

  test("references that loop back on themselves do not hang the literal check") {
    val file = ConfigFactory.parseString("""
      port = 1
      a = ${b}
      b = ${a}
      stripe { secretKey = ${a}, secretKey = ${?K}, webhookSecret = ${?W} }
    """)
    assert(Config.literal(file.root(), List("stripe", "secretKey")).isEmpty)
  }

  test("a list fallback is no literal: the variable's value is read, and unset it is malformed") {
    val file = ConfigFactory.parseString("""
      port = 1
      stripe { secretKey = [1, 2], secretKey = ${?K}, webhookSecret = ${?W} }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("K" -> "k", "W" -> "w")).map(_.stripe.secretKey),
      Right(Redacted("k"))
    )
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("stripe.secretKey", "is a list, and one value was expected")
          )
        )
      )
    )
  }

  test("a reference inside a list fallback is no literal either, set or unset") {
    val file = ConfigFactory.parseString("""
      port = 1
      source = "sk_live_committed"
      stripe { secretKey = [${source}], secretKey = ${?K}, webhookSecret = ${?W} }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("K" -> "k", "W" -> "w")).map(_.stripe.secretKey),
      Right(Redacted("k"))
    )
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("stripe.secretKey", "is a list, and one value was expected")
          )
        )
      )
    )
  }

  test("a reference inside a list beside a section's fields is no copy of any of them") {
    val file = ConfigFactory.parseString("""
      port = 1
      leak { secretKey = "sk_live_committed" }
      base { webhookSecret = ${?W} }
      stripe = ${base} { secretKey = ${?K}, note = [${leak}] }
    """)
    assertEquals(
      Config[ShopConfig].read(file, Map("K" -> "k", "W" -> "w")).map(_.stripe.secretKey),
      Right(Redacted("k"))
    )
    assertEquals(
      Config[ShopConfig].read(file, Map("W" -> "w")),
      Left(Config.Refused(Seq(Config.Offender.Missing("stripe.secretKey", List("K")))))
    )
  }

  test("a quoted key that holds a dot is a path of its own, never the nested path") {
    val file = ConfigFactory.parseString("""
      port = 1
      stripe { webhookSecret = ${?W} }
      "stripe.secretKey" = ${?X}
    """)
    val env = Map("X" -> "x", "W" -> "w")
    assertEquals(
      Config[ShopConfig].read(file, env),
      Left(Config.Refused(Seq(Config.Offender.Missing("stripe.secretKey", Nil))))
    )
    assertEquals(
      Config[ShopConfig].names(file).map(n => (n.variable, n.path, n.redacted)),
      Seq(("W", "stripe.webhookSecret", true), ("X", "\"stripe.secretKey\"", false))
    )
  }

  test("a value that cannot be joined is one malformed offender beside the others, not a throw") {
    val file = ConfigFactory.parseString("""
      port = ${?PORT}
      stripe { secretKey = ${?K}, webhookSecret = ${?W} }
      shopUrl = ${?SHOP_URL} [1]
    """)
    val env     = Map("K" -> "k", "W" -> "w", "SHOP_URL" -> "s3cr3t-value")
    val read    = Config[ShopConfig].read(file, env)
    val refused = read.left.toOption.get
    assertEquals(
      refused.offenders,
      Seq(
        Config.Offender.Malformed("shopUrl", Unjoinable),
        Config.Offender.Missing("port", List("PORT"))
      )
    )
    assertNoDiff(
      refused.message,
      s"""|shopUrl ${Unjoinable}
          |port is missing: set PORT, which application.conf names for it
          |""".stripMargin
    )
    (refused.message +: refused.offenders.map(_.toString)).foreach { text =>
      assert(!text.contains("s3cr3t-value"), text)
    }
  }

  test("a value that cannot be joined through a reference blames the path that joins, once") {
    val file = ConfigFactory.parseString("""
      port = 8080
      stripe { secretKey = ${?K}, webhookSecret = ${?W} }
      b = ${?B}
      shopUrl = ${b} [1]
    """)
    val env     = Map("K" -> "k", "W" -> "w", "B" -> "s3cr3t-value")
    val refused = Config[ShopConfig].read(file, env).left.toOption.get
    assertEquals(refused.offenders, Seq(Config.Offender.Malformed("shopUrl", Unjoinable)))
    (refused.message +: refused.offenders.map(_.toString)).foreach { text =>
      assert(!text.contains("s3cr3t-value"), text)
    }
  }

  test("a value joined wrongly at the end of a chain is blamed, beside one that fails on its own") {
    val file = ConfigFactory.parseString("""
      stripe { secretKey = ${?K}, webhookSecret = ${?W} }
      a = ${?A}
      b = ${a}
      shopUrl = ${b} [1]
      port = ${?PORT} [1]
    """)
    val env     = Map("K" -> "k", "W" -> "w", "A" -> "s3cr3t-value", "PORT" -> "8080")
    val refused = Config[ShopConfig].read(file, env).left.toOption.get
    assertEquals(
      refused.offenders,
      Seq(
        Config.Offender.Malformed("port", Unjoinable),
        Config.Offender.Malformed("shopUrl", Unjoinable)
      )
    )
    assert(!refused.message.contains("s3cr3t-value"), refused.message)
  }

  test("a required field that cannot be joined is refused once, not again as missing") {
    val file = ConfigFactory.parseString("banner = ${?BANNER} [1]")
    assertEquals(
      Config[Plain].read(file, Map("BANNER" -> "s3cr3t-value")),
      Left(Config.Refused(Seq(Config.Offender.Malformed("banner", Unjoinable))))
    )
  }

  test("substitutions that loop are refused at the first path of the loop, not thrown") {
    val file = ConfigFactory.parseString("""
      banner = ${loop}
      loop = ${banner}
    """)
    val read = Config[Plain].read(file, Map.empty)
    assertEquals(
      read,
      Left(Config.Refused(Seq(Config.Offender.Malformed("banner", Unjoinable))))
    )
  }

  test("two loops, which no one path mends, are refused as the file, not thrown") {
    val file = ConfigFactory.parseString("""
      banner = ${b}
      b = ${banner}
      c = ${d}
      d = ${c}
    """)
    val refused = Config[Plain].read(file, Map.empty).left.toOption.get
    assertEquals(refused.offenders, Seq(Config.Offender.Malformed("application.conf", Unjoinable)))
    assertNoDiff(refused.message, s"application.conf $Unjoinable")
  }

  test("no offender, message or name ever holds text read from the file or the map") {
    val file = ConfigFactory.parseString("""
      port = "port-text-8080x"
      stripe { secretKey = "sk_live_in_file", webhookSecret = ${?W} }
      shopUrl = ${?SHOP_URL}
    """)
    val hidden  = Seq("port-text-8080x", "sk_live_in_file", "whsec_in_map", "http://[oops")
    val refused =
      Config[ShopConfig]
        .read(file, Map("W" -> "whsec_in_map", "SHOP_URL" -> "http://[oops"))
        .left
        .toOption
        .get
    assertEquals(refused.offenders.size, 3)
    val shown =
      Seq(refused.message, refused.toString) ++ refused.offenders.map(_.toString) ++
        Config[ShopConfig].names(file).map(_.toString)
    for (text <- shown; secret <- hidden) assert(!text.contains(secret), text)
  }
}

object ConfigSuite {

  /** The reason a refusal gives for a value resolution cannot build, written out here so that a
    * change of the words in the main source fails a test.
    */
  val Unjoinable =
    "cannot be resolved: it joins a list or an object with a single value, or substitutes itself"

  final case class Stripe(secretKey: Redacted, webhookSecret: Redacted) derives Config

  final case class ShopConfig(
      port: Int,
      stripe: Stripe,
      shopUrl: Url = Url.Absolute("http://localhost:9000")
  ) derives Config

  /** Holds the text its Parser was handed, so a suite can see what the tree rendered. */
  final case class Echo(text: String)

  object Echo {
    given io.eezo.core.Parser[Echo] = text => Right(Echo(text))
  }

  final case class Raw(port: Echo) derives Config

  final case class Plain(banner: String) derives Config

  final case class Shop(
      debug: Boolean,
      port: Int = 8080,
      banner: Option[String],
      webhook: Option[Redacted]
  ) derives Config

  object Shop {
    def apply(debug: Boolean): Shop = Shop(debug, 8080, None, None)
  }
}
