package io.eezo.core

import io.eezo.core.html.Url

/** A Config read against explicit maps, never the process's environment, so a suite run on a
  * machine with a STRIPE_SECRET_KEY set cannot pass or fail because of it.
  */
class ConfigSuite extends munit.FunSuite {
  import ConfigSuite.*

  final case class Inner(port: Int = 9000, name: String) derives Config

  test("a case class nested in a class reads its default through the enclosing instance") {
    assertEquals(Config[Inner].read(Map("NAME" -> "n")), Right(Inner(name = "n")))
  }

  test("a case class deriving Config reads every variable present into an instance") {
    val read: Either[Config.Refused, Settings] = Config[Settings].read(
      Map(
        "STRIPE_SECRET_KEY"     -> "sk_test",
        "STRIPE_WEBHOOK_SECRET" -> "whsec_test",
        "SHOP_URL"              -> "https://shop.example"
      )
    )
    assertEquals(
      read,
      Right(
        Settings(Redacted("sk_test"), Redacted("whsec_test"), Url.Absolute("https://shop.example"))
      )
    )
  }

  test("read with no map type checks without the suite running it") {
    assertNoDiff(
      compileErrors(
        "val r: Either[Config.Refused, ConfigSuite.Settings] = Config[ConfigSuite.Settings].read()"
      ),
      ""
    )
  }

  test("an empty map gives a defaulted field its default and an Option field None") {
    assertEquals(Config[Shop].read(Map.empty), Right(Shop()))
  }

  test("every missing and malformed field is one Refused, in declaration order") {
    assertEquals(
      Config[Server].read(Map("PORT" -> "abc", "TIMEOUT" -> "soon", "NAME" -> "shop")),
      Left(
        Config.Refused(
          Seq(
            Config.Offender.Malformed("PORT", "is not a number"),
            Config.Offender.Missing("HOST"),
            Config.Offender.Missing("DEBUG"),
            Config.Offender.Malformed("TIMEOUT", "is not a number")
          )
        )
      )
    )
  }

  test("a Boolean with no default is missing when absent, whatever its Parser says absent means") {
    assertEquals(summon[Parser[Boolean]].absent, Some(false))
    assertEquals(
      Config[Server].read(Map("PORT" -> "1", "HOST" -> "h", "TIMEOUT" -> "2", "NAME" -> "n")),
      Left(Config.Refused(Seq(Config.Offender.Missing("DEBUG"))))
    )
  }

  test("a present variable goes to its Parser even when its text is empty") {
    assertEquals(
      Config[Server].read(
        Map("PORT" -> "", "HOST" -> "", "DEBUG" -> "yes", "TIMEOUT" -> "2", "NAME" -> "")
      ),
      Left(Config.Refused(Seq(Config.Offender.Malformed("PORT", "is not a number"))))
    )
    assertEquals(
      Config[Server].read(
        Map("PORT" -> "1", "HOST" -> "", "DEBUG" -> "yes", "TIMEOUT" -> "2", "NAME" -> "")
      ),
      Right(Server(1, "", true, 2L, ""))
    )
  }

  test("an Option field present reads Some, blank reads None, and malformed is Malformed") {
    assertEquals(
      Config[Shop].read(Map("BANNER" -> "sale", "RETRIES" -> "7", "PORT" -> "80")),
      Right(Shop(80, Some("sale"), None, Some(7)))
    )
    assertEquals(Config[Shop].read(Map("RETRIES" -> " ")), Right(Shop(8080, None, None, None)))
    assertEquals(
      Config[Shop].read(Map("RETRIES" -> "many")),
      Left(Config.Refused(Seq(Config.Offender.Malformed("RETRIES", "is not a number"))))
    )
  }

  test("the message has one line per offender, naming its variable and never its text") {
    val refused = Config[Server].read(Map("PORT" -> "abc", "TIMEOUT" -> "soon")).left.toOption.get
    assertNoDiff(
      refused.message,
      """|PORT is not a number
         |HOST is missing
         |DEBUG is missing
         |TIMEOUT is not a number
         |NAME is missing
         |""".stripMargin
    )
    assert(!refused.message.contains("abc"), refused.message)
    assert(!refused.message.contains("soon"), refused.message)
  }

  test("a Redacted value never shows in the refusal, its offenders, or its own toString") {
    val hidden  = "sk_live_do_not_log"
    val refused = Config[Settings]
      .read(Map("STRIPE_SECRET_KEY" -> hidden, "SHOP_URL" -> "http://[oops"))
      .left
      .toOption
      .get
    val shown = Seq(refused.message, refused.toString, Redacted(hidden).toString) ++
      refused.offenders.map(_.toString)
    shown.foreach(text => assert(!text.contains(hidden), text))
    assertEquals(Redacted("x").toString, "Redacted(redacted)")
  }

  test("names lists every field in declaration order, with its variable, redaction and need") {
    import Config.Need.*
    assertEquals(
      Config[Settings].names,
      Seq(
        Config.Name("STRIPE_SECRET_KEY", "stripeSecretKey", redacted = true, Required),
        Config.Name("STRIPE_WEBHOOK_SECRET", "stripeWebhookSecret", redacted = true, Required),
        Config.Name("SHOP_URL", "shopUrl", redacted = false, Defaulted)
      )
    )
    assertEquals(
      Config[Shop].names,
      Seq(
        Config.Name("PORT", "port", redacted = false, Defaulted),
        Config.Name("BANNER", "banner", redacted = false, Optional),
        Config.Name("WEBHOOK", "webhook", redacted = true, Optional),
        Config.Name("RETRIES", "retries", redacted = false, Defaulted)
      )
    )
  }

  test("a field's variable is its name in upper snake case, with no prefix") {
    List(
      "stripeSecretKey" -> "STRIPE_SECRET_KEY",
      "shopUrl"         -> "SHOP_URL",
      "port"            -> "PORT",
      "shopURL"         -> "SHOP_URL",
      "urlPath"         -> "URL_PATH",
      "s3Bucket"        -> "S3_BUCKET",
      "already_snake"   -> "ALREADY_SNAKE",
      "eezo"            -> "EEZO"
    ).foreach((field, variable) => assertEquals(Config.variable(field), variable, field))
  }

  test("a field's variable does not depend on the default locale") {
    assertEquals(VariableUnderLocale.run("tr", "idleTimeout"), "IDLE_TIMEOUT")
  }

  test("a field whose variable would start with EEZO_ does not compile, and says why") {
    val errors = compileErrors(
      "case class Clash(port: Int, eezoSecret: String, eezo_db: String) derives Config"
    )
    assert(errors.contains("`eezoSecret`"), errors)
    assert(errors.contains("`eezo_db`"), errors)
    assert(
      errors.contains(
        "Field `eezoSecret` would be read from EEZO_SECRET, and EEZO_ is the framework's prefix " +
          "for its own variables. Rename the field."
      ),
      errors
    )
    assert(
      errors.contains(
        "Field `eezo_db` would be read from EEZO_DB, and EEZO_ is the framework's prefix " +
          "for its own variables. Rename the field."
      ),
      errors
    )
    assert(errors.contains("Clash cannot derive Config, for the reasons above."), errors)
    assert(!errors.contains("`port`"), errors)
  }

  test("a field named eezo reads EEZO, since the framework's prefix is EEZO_") {
    assertEquals(Config[Named].read(Map("EEZO" -> "x")), Right(Named("x")))
  }

  test("a field whose type has no Parser does not compile, naming the field and the fix") {
    val errors = compileErrors(
      "case class Unreadable(port: Int, widget: ConfigSuite.Widget) derives Config"
    )
    assert(errors.contains("`widget`"), errors)
    assert(
      errors.contains(
        """|No Parser for io.eezo.core.ConfigSuite.Widget at field `widget`, so WIDGET cannot be read.
           |Provide one in scope:
           |  given Parser[io.eezo.core.ConfigSuite.Widget] = text => ... // Right(value), or Left("is not a ...")
           |""".stripMargin
      ),
      errors
    )
    assert(errors.contains("Unreadable cannot derive Config, for the reasons above."), errors)
    assert(!errors.contains("`port`"), errors)
  }

  test("an Option field whose inner type has no Parser asks for the inner Parser") {
    val errors = compileErrors(
      "case class MaybeUnreadable(widget: Option[ConfigSuite.Widget]) derives Config"
    )
    assert(
      errors.contains(
        """|No Parser for io.eezo.core.ConfigSuite.Widget at field `widget`, so WIDGET cannot be read.
           |Provide one in scope:
           |  given Parser[io.eezo.core.ConfigSuite.Widget] = text => ... // Right(value), or Left("is not a ...")
           |""".stripMargin
      ),
      errors
    )
    assert(!errors.contains("Parser[scala.Option"), errors)
  }

  test("a Some field is not an Option, so the Parser asked for is its own") {
    val errors = compileErrors("case class Surely(port: Some[Int]) derives Config")
    assert(
      errors.contains(
        """|No Parser for scala.Some[scala.Int] at field `port`, so PORT cannot be read.
           |Provide one in scope:
           |  given Parser[scala.Some[scala.Int]] = text => ... // Right(value), or Left("is not a ...")
           |""".stripMargin
      ),
      errors
    )
  }

  test("a class that cannot be read field by field is refused instead of miscompiled") {
    val generic = compileErrors("case class Two[T](t: T) derives Config")
    assert(
      generic.contains(
        "Two has type parameters, and a Config is read from one fixed set of variables."
      ),
      generic
    )
    val lists = compileErrors("case class Lists(a: Int)(b: Int) derives Config")
    assert(
      lists.contains(
        "No given instance of type scala.deriving.Mirror.ProductOf[Lists] was found for " +
          "parameter m of method derived in object Config. Failed to synthesize an instance of " +
          "type scala.deriving.Mirror.ProductOf[Lists]: class Lists is not a generic product " +
          "because it takes more than one parameter list"
      ),
      lists
    )
    val local = compileErrors("case class Here(a: Int = 1) derives Config")
    assert(
      local.contains(
        "Here is defined inside a method and has a default, which a Config cannot read " +
          "there. Move it to an object or the top level."
      ),
      local
    )
  }

  test("a case object is refused, since only a case class has fields to read") {
    val errors = compileErrors("case object Solo; Config.derived[Solo.type]")
    assert(errors.contains("Solo is not a case class, and only one can derive Config."), errors)
  }
}

object ConfigSuite {
  final case class Settings(
      stripeSecretKey: Redacted,
      stripeWebhookSecret: Redacted,
      shopUrl: Url = Url.Absolute("http://localhost:8080")
  ) derives Config

  final case class Shop(
      port: Int = 8080,
      banner: Option[String],
      webhook: Option[Redacted],
      retries: Option[Int] = Some(3)
  ) derives Config

  final case class Server(port: Int, host: String, debug: Boolean, timeout: Long, name: String)
      derives Config

  final case class Named(eezo: String) derives Config

  final case class Widget(name: String)

  object Shop {
    def apply(): Shop = Shop(8080, None, None, Some(3))
  }
}

/** Prints the variable of a field in a JVM of its own. The default locale belongs to the whole JVM,
  * and core's suites run side by side in one, so a Turkish default set in a test would reach every
  * other suite's case conversions while it lasted.
  */
object VariableUnderLocale {
  def main(args: Array[String]): Unit = print(Config.variable(args(0)))

  def run(language: String, field: String): String = {
    val classpath =
      List(classOf[Config[?]], classOf[scala.Option[?]], classOf[scala.Tuple], getClass)
        .map(c => java.nio.file.Paths.get(c.getProtectionDomain.getCodeSource.getLocation.toURI))
        .distinct
        .mkString(java.io.File.pathSeparator)
    val launcher = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val builder  = new ProcessBuilder(
      launcher,
      s"-Duser.language=$language",
      "-cp",
      classpath,
      "io.eezo.core.VariableUnderLocale",
      field
    ).redirectErrorStream(true)
    // Either one makes the JVM announce it on the stream the variable is read from.
    builder.environment.remove("JAVA_TOOL_OPTIONS")
    builder.environment.remove("JDK_JAVA_OPTIONS")
    val process = builder.start()
    val out     = new String(process.getInputStream.readAllBytes(), "UTF-8")
    process.waitFor()
    out
  }
}
