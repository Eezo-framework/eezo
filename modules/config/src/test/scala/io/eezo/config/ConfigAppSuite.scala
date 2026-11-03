package io.eezo.config

import io.eezo.config.support.ConfigFileLoader
import io.eezo.core.Redacted
import io.eezo.core.internal.Json
import io.eezo.core.support.Captured.captured

/** What every entry trait does with the configuration before the application starts, and the
  * `config` command, pinned on the trait itself. The layers' suites pin that each one reaches it
  * before anything of its own.
  *
  * The installed value is global to the JVM, so the classes read here are read by this suite alone.
  */
class ConfigAppSuite extends munit.FunSuite {
  import ConfigAppSuite.*

  /** An application that records whether it started and what it saw installed, in place of serving.
    */
  private class App(env: Map[String, String] = Map.empty) extends ConfigApp {
    var started                      = false
    var seen: Option[(Shop, Stripe)] = None

    override private[eezo] def environment: Map[String, String] = env

    protected def program(): Unit = {
      started = true
      seen = Some(Config[Shop].current -> Config[Stripe].current)
    }
  }

  test("a build that names no class starts as before and prints nothing, file or no file") {
    List(None, Some("boot-shop.conf")).foreach { conf =>
      val app = new App {
        override protected def program(): Unit = started = true
      }
      val (code, out, err) = ConfigFileLoader.plain(conf)(captured(app.run(Nil)))
      assertEquals(code, 0)
      assertEquals(out, "")
      assertEquals(err, "")
      assert(app.started)
    }
  }

  /** Boots `app` as an application whose build names [[Shop]] and whose file is `conf`. */
  private def boot(app: App, conf: Option[String], args: List[String] = Nil) =
    ConfigFileLoader.generating(classOf[ShopFile.type], conf)(captured(app.run(args)))

  test("a refused read is exit 1, one line per offender on stderr, and nothing started") {
    val app              = new App(Map("SHOP_PORT" -> "eighty"))
    val (code, out, err) = boot(app, Some("boot-shop-unfed.conf"))
    assertEquals(code, 1)
    assertEquals(out, "")
    val lines = err.linesIterator.toList
    assertEquals(lines.length, 3, err)
    assertEquals(lines(0), "[eezo] name is missing: add it to application.conf")
    assert(lines(1).startsWith("[eezo] port "), err)
    assertEquals(lines(2), "[eezo] stripe.secretKey is missing: add it to application.conf")
    assert(!err.contains("Exception") && !err.contains("\tat "), err)
    assert(!app.started)
  }

  test("a path the file feeds from an unset variable names the variable, and only it") {
    val (code, _, err) = boot(new App, Some("boot-shop.conf"))
    assertEquals(code, 1)
    assertEquals(
      err.linesIterator.toList,
      List(
        "[eezo] stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf " +
          "names for it"
      )
    )
  }

  test("a refusal never prints a value the environment holds, a Redacted one included") {
    val env = Map(
      "STRIPE_SECRET_KEY" -> "sk_sentinel_redacted",
      "SHOP_PORT"         -> "port_sentinel_plain"
    )
    val (code, out, err) = boot(new App(env), Some("boot-shop.conf"))
    assertEquals(code, 1)
    assert(err.contains("[eezo] port "), err)
    List("sentinel", "sk_", "port_").foreach { text =>
      assert(!out.contains(text), out)
      assert(!err.contains(text), err)
    }
  }

  test("a good read starts the application with the root and every section current") {
    val app              = new App(Map("STRIPE_SECRET_KEY" -> "sk_test"))
    val (code, out, err) = boot(app, Some("boot-shop.conf"))
    assertEquals(code, 0, err)
    assertEquals(out, "")
    val stripe = Stripe(Redacted("sk_test"), "eur")
    assertEquals(app.seen, Some(Shop("Shop", 8080, stripe) -> stripe))
  }

  test("a build that names a class and has no application.conf is refused in one line") {
    val app              = new App(Map("STRIPE_SECRET_KEY" -> "sk_test"))
    val (code, out, err) = boot(app, conf = None)
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      err.linesIterator.toList,
      List(
        "[eezo] application.conf is missing: the build names a Config class with " +
          "eezoConfigClass, and boot reads it from src/main/resources/application.conf"
      )
    )
    assert(!app.started)
  }

  test("a file that does not parse is refused with its line, never its text") {
    val app              = new App(Map("STRIPE_SECRET_KEY" -> "sk_test"))
    val (code, out, err) = boot(app, Some("checked-unparsable.conf"))
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      err.linesIterator.toList,
      List("[eezo] application.conf does not parse at line 4: fix its syntax there.")
    )
    assert(!app.started)
  }

  test("boot reads the application's own application.conf, and nothing merged into it") {
    val other  = ConfigFileLoader.resource("boot-shop.conf")
    val loader = new ConfigFileLoader(
      Some(classOf[ShopFile.type]),
      Some(ConfigFileLoader.resource("boot-shop-unfed.conf")),
      Map("application.conf" -> List(other), "reference.conf" -> List(other))
    )
    val properties = Map("stripe.secretKey" -> "sk_property", "config.file" -> other.getPath)
    properties.foreach(System.setProperty(_, _))
    val (code, _, err) =
      try ConfigFileLoader.within(loader)(captured(new App().run(Nil)))
      finally properties.keys.foreach(System.clearProperty)
    assertEquals(code, 1)
    assertEquals(
      err.linesIterator.toList,
      List(
        "[eezo] name is missing: add it to application.conf",
        "[eezo] port is missing: set SHOP_PORT, which application.conf names for it",
        "[eezo] stripe.secretKey is missing: add it to application.conf"
      )
    )
  }

  /** What `config` lists for `boot-shop.conf`: the variable, the path, and two fixed words. */
  private val listed = List(
    "SHOP_PORT\tport\tplain\trequired",
    "STRIPE_SECRET_KEY\tstripe.secretKey\tredacted\trequired",
    "STRIPE_CURRENCY\tstripe.currency\tplain\tdefaulted"
  )

  /** An application whose every read of the environment fails the test. */
  private class Blind extends App {
    override private[eezo] def environment: Map[String, String] =
      throw new AssertionError("config read the environment")
  }

  test("config lists one line per variable the file names, in file order, and starts nothing") {
    val app              = new Blind
    val (code, out, err) = boot(app, Some("boot-shop.conf"), List("config"))
    assertEquals(code, 0, err)
    assertEquals(err, "")
    assertEquals(out.linesIterator.toList, listed)
    assert(!app.started)
  }

  test("config reads no environment: every variable set or none, the list is the same") {
    val set = Map(
      "SHOP_PORT"         -> "port_sentinel",
      "STRIPE_SECRET_KEY" -> "sk_sentinel",
      "STRIPE_CURRENCY"   -> "currency_sentinel"
    )
    val outputs = List(new App(set), new App, new Blind).map { app =>
      val (code, out, err) = boot(app, Some("boot-shop.conf"), List("config"))
      assertEquals(code, 0, err)
      assert(!out.contains("sentinel"), out)
      out
    }
    assertEquals(outputs.distinct, List(listed.mkString("", "\n", "\n")))
  }

  test("config refuses a file that feeds from EEZO_ or states a Redacted, and lists nothing") {
    val (code, out, err) = boot(new Blind, Some("boot-shop-literal.conf"), List("config"))
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      err.linesIterator.toList,
      List(
        "[eezo] stripe.currency is fed from EEZO_CURRENCY, and EEZO_ is the framework's prefix " +
          "for its own variables: name another variable in application.conf",
        "[eezo] stripe.secretKey is stated as a literal in application.conf, and it is a " +
          "Redacted: a Redacted value reaches the file only through a variable"
      )
    )
    assert(!err.contains("sentinel"), err)
  }

  test("config never reports a missing value: the file names it, and that is all it lists") {
    val (code, out, err) = boot(new Blind, Some("boot-shop-unfed.conf"), List("config"))
    assertEquals(code, 0, err)
    assertEquals(out.linesIterator.toList, List("SHOP_PORT\tport\tplain\trequired"))
  }

  test("config on a build that names no class prints nothing") {
    List(None, Some("boot-shop.conf")).foreach { conf =>
      val (code, out, err) = ConfigFileLoader.plain(conf)(captured(new Blind().run(List("config"))))
      assertEquals(code, 0)
      assertEquals(out, "")
      assertEquals(err, "")
    }
  }

  /** One row of `config --json`. */
  private def variable(name: String, path: String, redacted: Boolean, need: String): Json =
    Json.Obj(
      List(
        "variable" -> Json.Str(name),
        "path"     -> Json.Str(path),
        "redacted" -> Json.Bool(redacted),
        "need"     -> Json.Str(need)
      )
    )

  private def body(rows: Json*): Json =
    Json.Obj(List("command" -> Json.Str("config"), "variables" -> Json.Arr(rows.toList)))

  test("config --json carries the same rows, and a build with no class an empty list") {
    val (code, out, err) = boot(new Blind, Some("boot-shop.conf"), List("config", "--json"))
    assertEquals(code, 0, err)
    assertEquals(
      Json.parse(out.trim),
      Right(
        body(
          variable("SHOP_PORT", "port", redacted = false, "required"),
          variable("STRIPE_SECRET_KEY", "stripe.secretKey", redacted = true, "required"),
          variable("STRIPE_CURRENCY", "stripe.currency", redacted = false, "defaulted")
        )
      )
    )

    val (none, empty, _) =
      ConfigFileLoader.plain(None)(captured(new Blind().run(List("config", "--json"))))
    assertEquals(none, 0)
    assertEquals(Json.parse(empty.trim), Right(body()))
  }

  test("config refuses under --json with one error object per offender") {
    // `Json.render` writes an object of one field on three lines.
    def errors(text: String): List[String] =
      text.linesIterator.grouped(3).toList.map { lines =>
        Json.parse(lines.mkString("\n")) match {
          case Right(Json.Obj(List("error" -> Json.Str(message)))) => message
          case other                                               => fail(s"not an error: $other")
        }
      }
    val (code, out, err) =
      boot(new Blind, Some("boot-shop-literal.conf"), List("config", "--json"))
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      errors(err).map(_.takeWhile(_ != ' ')),
      List("stripe.currency", "stripe.secretKey")
    )

    val (missing, _, missingErr) = boot(new Blind, conf = None, List("config", "--json"))
    assertEquals(missing, 1)
    assertEquals(errors(missingErr).map(_.takeWhile(_ != ':')), List("application.conf is missing"))
  }

  test("help lists config once, beside help's own row") {
    val (code, out, _) = captured(new App().run(List("help")))
    assertEquals(code, 0)
    val lines = out.linesIterator.toList
    assertEquals(lines.count(_.trim.startsWith("config")), 1, out)
    assert(
      lines.contains("  config  the variables application.conf names, without reading them"),
      out
    )
  }
}

object ConfigAppSuite {

  final case class Stripe(secretKey: Redacted, currency: String = "eur") derives Config

  final case class Shop(name: String, port: Int, stripe: Stripe) derives Config

  object ShopFile extends Config.Root.Generated {
    val root: Config.Root[Shop] = Config.root[Shop]
  }
}
