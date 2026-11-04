package io.eezo.db

import io.eezo.config.Config
import io.eezo.config.support.ConfigFileLoader
import io.eezo.core.support.Captured.captured
import io.eezo.db.engine.Database
import io.eezo.db.support.Library

/** The database layer's entry trait reads the application's Config before anything of its own: a
  * refused read builds no `Database`, so no pool opens for an application that cannot start. No
  * Postgres is needed, since nothing here may reach one.
  */
class DbAppConfigSuite extends munit.FunSuite {
  import DbAppConfigSuite.*

  /** A database only application that records, in place of connecting, whether a `Database` was
    * asked for and whether its program ran.
    */
  private class Job extends DbApp {
    var built = false
    var ran   = false

    override private[eezo] def environment: Map[String, String] = Map.empty

    override def schema: Schema = Library

    override protected def database: Database = {
      built = true
      throw new IllegalStateException("a refused Config built a Database")
    }

    override def boot(): Unit = ran = true
  }

  test("a refused Config is exit 1 with the line, before a Database is built") {
    val app              = new Job
    val (code, out, err) =
      ConfigFileLoader.generating(classOf[LedgerFile.type], Some("ledger.conf"))(
        captured(app.run(Nil))
      )
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      err.linesIterator.toList,
      List("[eezo] owner is missing: set LEDGER_OWNER, which application.conf names for it")
    )
    assert(!app.built, "the Database was built before the Config was read")
    assert(!app.ran)
  }

  test("help lists config once, and config answers without a Database") {
    val app            = new Job
    val (code, out, _) = captured(app.run(List("help")))
    assertEquals(code, 0)
    assertEquals(out.linesIterator.count(_.trim.startsWith("config")), 1, out)
    val (listed, rows, err) =
      ConfigFileLoader.generating(classOf[LedgerFile.type], Some("ledger.conf"))(
        captured(app.run(List("config")))
      )
    assertEquals(listed, 0, err)
    assertEquals(rows.linesIterator.toList, List("LEDGER_OWNER\towner\tplain\trequired"))
    assert(!app.built)
  }
}

object DbAppConfigSuite {

  /** Read by this suite alone, since the installed value is global to the JVM. */
  final case class Ledger(owner: String) derives Config

  object LedgerFile extends Config.Root.Generated {
    val root: Config.Root[Ledger] = Config.root[Ledger]
  }
}
