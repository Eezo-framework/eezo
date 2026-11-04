package io.eezo.config

import com.typesafe.config.Config as Tree

import io.eezo.config.support.ConfigFileLoader
import io.eezo.core.Redacted

/** The report the dev console reads, from the one read boot does: the variables the file names and
  * whether each is set, and every offender, from a single read that installs a good value once.
  *
  * The installed value is global to the JVM, so the classes read here are read by this suite alone.
  */
class ConfigReportSuite extends munit.FunSuite {
  import ConfigReportSuite.*

  private def boot(conf: Option[String], env: Map[String, String]): Option[ConfigReport] = {
    Counting.reset()
    ConfigFileLoader.generating(classOf[StoreFile.type], conf)(ConfigApp.boot(env))
  }

  test("a good boot reads once, installs once, and reports every variable with no offender") {
    val report = boot(Some("boot-shop.conf"), Map("STRIPE_SECRET_KEY" -> "sk_test"))
    assertEquals(Counting.reads, 1)
    assertEquals(Counting.installs, 1)
    assertEquals(report.map(_.offenders), Some(Nil))
    assertEquals(
      report.toList.flatMap(_.variables).map(v => (v.name.variable, v.name.path, v.set)),
      List(
        ("SHOP_PORT", "port", false),
        ("STRIPE_SECRET_KEY", "stripe.secretKey", true),
        ("STRIPE_CURRENCY", "stripe.currency", false)
      )
    )
    assertEquals(Config[Store].current.stripe.secretKey, Redacted("sk_test"))
  }

  test("a refusal reads once, installs nothing, and its lines are boot's refusal lines") {
    val report = boot(Some("boot-shop-unfed.conf"), Map("SHOP_PORT" -> "eighty"))
    assertEquals(Counting.reads, 1)
    assertEquals(Counting.installs, 0)
    val lines = report.toList.flatMap(_.lines)
    assertEquals(lines.length, 3, lines)
    assertEquals(lines(0), "name is missing: add it to application.conf")
    assert(lines(1).startsWith("port "), lines)
    assertEquals(lines(2), "stripe.secretKey is missing: add it to application.conf")
  }

  test("an absent or unparsable file is one offender, and nothing is read") {
    val absent = boot(conf = None, Map.empty)
    assertEquals(Counting.reads, 0)
    assertEquals(
      absent.toList.flatMap(_.lines),
      List(
        "application.conf is missing: the build names a Config class with eezoConfigClass, " +
          "and boot reads it from src/main/resources/application.conf"
      )
    )
    val unparsable = boot(Some("checked-unparsable.conf"), Map.empty)
    assertEquals(
      unparsable.toList.flatMap(_.lines),
      List("application.conf does not parse at line 4: fix its syntax there.")
    )
  }

  test("a build that names no class has no report") {
    assertEquals(ConfigFileLoader.plain(Some("boot-shop.conf"))(ConfigApp.boot(Map.empty)), None)
  }
}

object ConfigReportSuite {

  final case class Card(secretKey: Redacted, currency: String = "eur") derives Config

  final case class Store(name: String, port: Int, stripe: Card) derives Config

  /** [[Store]]'s Config, counting what boot asks of it. */
  object Counting extends Config[Store] {
    private val config     = Config[Store]
    @volatile var reads    = 0
    @volatile var installs = 0

    def reset(): Unit = {
      reads = 0
      installs = 0
    }

    def read(file: Tree, env: Map[String, String]): Either[Config.Refused, Store] = {
      reads += 1
      config.read(file, env)
    }
    def names(file: Tree): Seq[Config.Name] = config.names(file)
    def install(value: Store): Unit         = {
      installs += 1
      config.install(value)
    }
    def current: Store                                        = config.current
    def installed[R](value: Store)(body: => R): R             = config.installed(value)(body)
    private[config] def slots: List[Config.Slot]              = config.slots
    private[config] def put(value: Option[Store]): () => Unit = config.put(value)
    private[config] def readAt(
        tree: Option[com.typesafe.config.ConfigObject],
        prefix: List[String],
        file: Config.Written
    ): Either[List[Config.Offender], Store] = config.readAt(tree, prefix, file)
  }

  object StoreFile extends Config.Root.Generated {
    val root: Config.Root[Store] = new Config.Root(Counting)
  }
}
