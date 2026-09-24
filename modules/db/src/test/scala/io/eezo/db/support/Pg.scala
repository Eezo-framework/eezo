package io.eezo.db.support

import io.eezo.db.{DbInit, Schema, Table}
import io.eezo.db.engine.{Database, Installed}
import io.eezo.db.schema.{Introspect, SchemaSnap}

import munit.FunSuite
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.sql.Connection
import java.sql.DriverManager

/** One Postgres for the whole test run.
  *
  * Starting a container costs seconds. Isolating suites from each other costs nothing once the
  * server is up, because they are separated by Postgres *schema* rather than by server, which works
  * only because `Introspect.snapshot` already takes a schema name (DESIGN §6).
  *
  * A real Postgres is not an implementation detail of the suite. Half of what `db` claims is a
  * claim about Postgres: that the catalog reads back what we wrote, that constraints reject what
  * they should, that DDL is transactional. A substitute would test the other half twice and this
  * half not at all.
  */
object Pg {
  private lazy val container: PostgreSQLContainer[?] = {
    val c = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    c.start()
    sys.addShutdownHook(c.stop())
    c
  }

  def connect(): Connection =
    DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)

  /** The connection settings, for a suite that builds its own `Database` the way an application
    * does: through `DbInit`'s overrides rather than through [[database]].
    */
  def jdbcUrl: String  = container.getJdbcUrl
  def username: String = container.getUsername
  def password: String = container.getPassword

  /** A `Database` for one suite, isolated by Postgres schema.
    *
    * The `search_path` is set by the pool's connection init hook rather than once on a borrowed
    * connection, because `search_path` is per connection: set once on one connection, every other
    * connection the pool hands out would still be on `public` and the suite would silently read the
    * wrong schema. This is the reason `Pool` takes the hook at all (DESIGN §8.7).
    */
  def database(schema: String): Database =
    Database.connect(
      jdbcUrl,
      username,
      password,
      init = searchPath(schema),
      size = DbInit.DefaultPoolSize,
      acquireTimeout = DbInit.DefaultAcquireTimeout
    )

  /** The connection init hook that isolates a suite by schema: [[database]]'s, and the one a suite
    * that builds its own `Database` through `DbInit` names as `databaseInit`.
    */
  def searchPath(schema: String): Connection -> Unit = c => {
    val st = c.createStatement()
    try st.execute(s"""set search_path to "$schema" """): Unit
    finally st.close()
  }
}

/** A suite that installs a `Database` on eezo's holder for its duration, isolated by schema.
  *
  * One install point is enough because `Test / parallelExecution := false` is set on the `db`
  * project; see the comment on that setting in `build.sbt`.
  */
abstract class DbSuite extends PgSuite {

  /** Kept so [[afterAll]] can close it. A pool holds its connections open for its whole life, so a
    * suite that only uninstalled would leave them to the next suites, and a dozen suites of them
    * exhaust the container's `max_connections`.
    */
  private var database: Database | Null = null

  override def beforeAll(): Unit = {
    super.beforeAll()
    exec(s"""drop schema if exists "$pgSchema" cascade""", s"""create schema "$pgSchema"""")
    val d = Pg.database(pgSchema)
    database = d
    Installed.install(d)
  }

  override def afterAll(): Unit = {
    Installed.uninstall()
    val d = database
    if (d != null) d.close()
    super.afterAll()
  }
}

/** A suite with a private, empty Postgres schema restored before every test. */
abstract class PgSuite extends FunSuite {

  private var conn: Connection = null

  /** This suite's schema. Derived from the class name so two suites cannot collide. */
  protected lazy val pgSchema: String =
    "t_" + getClass.getSimpleName.replace("$", "").toLowerCase

  protected def db: Connection = conn

  protected def exec(sql: String*): Unit = {
    val st = conn.createStatement()
    try sql.foreach(st.execute)
    finally st.close()
  }

  /** The live schema as the differ sees it. */
  protected def live(): SchemaSnap = Introspect.snapshot(conn, pgSchema)

  /** Create every table in `s`, through the same path a migration would take. */
  protected def create(s: Schema): Unit = exec(s.ddl*)

  /** Insert rows as one batch, through the derived codec. */
  protected def insert[T](t: Table[T], rows: T*): Unit = {
    val ps = conn.prepareStatement(t.insertSql)
    try {
      rows.foreach { r => t.encode(ps, 1, r); ps.addBatch() }
      ps.executeBatch(): Unit
    } finally ps.close()
  }

  /** Read every row back through the derived codec. */
  protected def selectAll[T](t: Table[T]): List[T] = {
    val ps = conn.prepareStatement(t.selectAllSql)
    try {
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(t.decode(_, 1)).toList
      finally rs.close()
    } finally ps.close()
  }

  /** Run `f`, returning true if Postgres rejected it. */
  protected def rejected(f: => Any): Boolean =
    try { f; false }
    catch { case _: java.sql.SQLException => true }

  override def beforeAll(): Unit = conn = Pg.connect()

  override def beforeEach(context: BeforeEach): Unit = exec(
    s"""drop schema if exists "$pgSchema" cascade""",
    s"""create schema "$pgSchema"""",
    s"""set search_path to "$pgSchema""""
  )

  override def afterAll(): Unit =
    if (conn != null) {
      exec(s"""drop schema if exists "$pgSchema" cascade""")
      conn.close()
    }
}
