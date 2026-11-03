package io.eezo.db

import munit.FunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Statement}
import java.time.Duration
import scala.collection.mutable.ListBuffer

/** The `DATABASE_URL` fallback parser: the one secret a PaaS injects, split into the three values
  * JDBC wants; and the schema step the pool runs on every connection, read off a connection that
  * records what it is asked. Pure, so a plain suite.
  */
class DbInitSuite extends FunSuite {

  /** A connection with no database behind it: it writes down every call made on it and every
    * statement executed through it, and answers the catalog lookup as `schemaExists` says. With
    * `createFails`, the `create schema` statement fails with that SQLState, the way Postgres
    * answers the loser of two connections that both found the schema missing.
    */
  private final class Recording(schemaExists: Boolean, createFails: Option[String] = None) {
    val calls: ListBuffer[String]    = ListBuffer.empty
    val executed: ListBuffer[String] = ListBuffer.empty

    private def proxy[T](
        cls: Class[T]
    )(answer: (Method, Array[AnyRef] | Null) => AnyRef | Null): T =
      cls.cast(
        Proxy.newProxyInstance(
          getClass.getClassLoader,
          Array[Class[?]](cls),
          new InvocationHandler {
            def invoke(p: AnyRef, m: Method, args: Array[AnyRef] | Null): AnyRef | Null =
              answer(m, args)
          }
        )
      )

    private val resultSet: ResultSet = proxy(classOf[ResultSet]) { (m, _) =>
      if (m.getName == "next") java.lang.Boolean.valueOf(schemaExists) else null
    }

    private val prepared: PreparedStatement = proxy(classOf[PreparedStatement]) { (m, _) =>
      if (m.getName == "executeQuery") resultSet else null
    }

    private val statement: Statement = proxy(classOf[Statement]) { (m, args) =>
      if (m.getName == "execute") {
        val sql = String.valueOf(args.nn(0))
        executed += sql
        createFails match {
          case Some(state) if sql.startsWith("create schema") =>
            throw new SQLException("duplicate key value violates unique constraint", state)
          case _ => java.lang.Boolean.FALSE
        }
      } else null
    }

    val connection: Connection = proxy(classOf[Connection]) { (m, _) =>
      calls += m.getName
      m.getName match {
        case "createStatement"  => statement
        case "prepareStatement" => prepared
        case _                  => null
      }
    }
  }

  test(
    "the default public schema runs nothing on the connection, so its search_path is Postgres's"
  ) {
    val c = Recording(schemaExists = true)
    DbInit.connectionInit("public", _ => ())(c.connection)
    assertEquals(c.calls.toList, Nil)
  }

  test("a named schema is created when missing, then becomes the search_path, before the hook") {
    val c = Recording(schemaExists = false)
    DbInit.connectionInit(
      "shop",
      conn => conn.createStatement().execute("set application_name to 'x'"): Unit
    )(
      c.connection
    )
    assertEquals(
      c.executed.toList,
      List(
        """create schema if not exists "shop"""",
        """set search_path to "shop"""",
        "set application_name to 'x'"
      )
    )
  }

  test(
    "a schema that exists is not created again, so a role without CREATE on the database connects"
  ) {
    val c = Recording(schemaExists = true)
    DbInit.connectionInit("shop", _ => ())(c.connection)
    assertEquals(c.executed.toList, List("""set search_path to "shop""""))
  }

  test(
    "losing the race to create the schema to another connection still enters it, either SQLState"
  ) {
    for (state <- List("23505", "42P06")) {
      val c = Recording(schemaExists = false, createFails = Some(state))
      DbInit.connectionInit("shop", _ => ())(c.connection)
      assertEquals(
        c.executed.toList,
        List("""create schema if not exists "shop"""", """set search_path to "shop""""),
        state
      )
    }
  }

  test("any other failure to create the schema fails the connection") {
    val c = Recording(schemaExists = false, createFails = Some("42501"))
    val e = intercept[SQLException](DbInit.connectionInit("shop", _ => ())(c.connection))
    assertEquals(e.getSQLState, "42501")
    assertEquals(c.executed.toList, List("""create schema if not exists "shop""""))
  }

  test("a double quote in the schema name is doubled, so the name stays one identifier") {
    val c = Recording(schemaExists = false)
    DbInit.connectionInit("""a"; drop table x; --""", _ => ())(c.connection)
    assertEquals(
      c.executed.toList,
      List(
        """create schema if not exists "a""; drop table x; --"""",
        """set search_path to "a""; drop table x; --""""
      )
    )
  }

  private def parsed(url: String) =
    DbInit.parseDatabaseUrl(url).getOrElse(fail(s"expected $url to parse"))

  test("the full form: user, password, host, port, database, and TLS required when no query") {
    assertEquals(
      parsed("postgres://app:secret@db.internal:5433/prod"),
      DbInit.Parsed("jdbc:postgresql://db.internal:5433/prod?sslmode=require", "app", "secret")
    )
  }

  test("postgresql scheme, no port, an explicit sslmode=require is not doubled") {
    assertEquals(
      parsed("postgresql://app:secret@db.internal/prod?sslmode=require"),
      DbInit.Parsed("jdbc:postgresql://db.internal/prod?sslmode=require", "app", "secret")
    )
  }

  test("a query without sslmode keeps its parameters, in order, and gains sslmode=require last") {
    assertEquals(
      parsed(
        "postgres://app:secret@host/db?application_name=x&options=-c%20search_path%3Dapp"
      ).jdbcUrl,
      "jdbc:postgresql://host/db?application_name=x&options=-c%20search_path%3Dapp&sslmode=require"
    )
  }

  test("an empty query is no query: sslmode=require joins with a question mark alone") {
    assertEquals(
      parsed("postgres://app@host/db?").jdbcUrl,
      "jdbc:postgresql://host/db?sslmode=require"
    )
  }

  test("an explicit sslmode wins, whatever its value: the query passes through byte for byte") {
    assertEquals(
      parsed("postgres://app@host/db?sslmode=disable").jdbcUrl,
      "jdbc:postgresql://host/db?sslmode=disable",
      "Fly's attach writes sslmode=disable on purpose"
    )
    assertEquals(
      parsed("postgres://app@host/db?sslmode=verify-full&sslrootcert=%2Fetc%2Fca.pem").jdbcUrl,
      "jdbc:postgresql://host/db?sslmode=verify-full&sslrootcert=%2Fetc%2Fca.pem"
    )
  }

  test("sslmode counts wherever it sits among the parameters") {
    assertEquals(
      parsed("postgres://app@host/db?application_name=x&sslmode=disable").jdbcUrl,
      "jdbc:postgresql://host/db?application_name=x&sslmode=disable"
    )
  }

  test("a bare sslmode with no value is still an explicit sslmode, left for the driver to refuse") {
    assertEquals(
      parsed("postgres://app@host/db?sslmode").jdbcUrl,
      "jdbc:postgresql://host/db?sslmode"
    )
  }

  test("only the sslmode key counts, not a key that ends in it or a value that mentions it") {
    assertEquals(
      parsed("postgres://app@host/db?foosslmode=disable").jdbcUrl,
      "jdbc:postgresql://host/db?foosslmode=disable&sslmode=require"
    )
    assertEquals(
      parsed("postgres://app@host/db?application_name=sslmode=disable").jdbcUrl,
      "jdbc:postgresql://host/db?application_name=sslmode=disable&sslmode=require"
    )
  }

  test("ssl=true is not sslmode: it still gains sslmode=require, which the driver ranks above it") {
    assertEquals(
      parsed("postgres://app@host/db?ssl=true").jdbcUrl,
      "jdbc:postgresql://host/db?ssl=true&sslmode=require",
      "only the sslmode key is matched; a URL wanting verify-full spells sslmode=verify-full"
    )
  }

  test("percent-encoded credentials are decoded") {
    assertEquals(
      parsed("postgres://us%40er:p%40ss%2Fword@host/db"),
      DbInit.Parsed("jdbc:postgresql://host/db?sslmode=require", "us@er", "p@ss/word")
    )
  }

  test("user without password, and no userinfo at all") {
    assertEquals(parsed("postgres://app@host/db").user, "app")
    assertEquals(parsed("postgres://app@host/db").password, "")
    assertEquals(parsed("postgres://host/db").user, "postgres")
  }

  test("not a postgres url, or missing the database: no half-applied parse") {
    assertEquals(DbInit.parseDatabaseUrl("mysql://host/db"), None)
    assertEquals(DbInit.parseDatabaseUrl("postgres://host"), None)
    assertEquals(DbInit.parseDatabaseUrl("postgres://host/"), None)
    assertEquals(DbInit.parseDatabaseUrl("not a url at all ::"), None)
  }

  test("pool size: unset is 10, a positive integer is taken, anything else falls back to 10") {
    assertEquals(DbInit.poolSize(None), 10)
    assertEquals(DbInit.poolSize(Some("25")), 25)
    assertEquals(DbInit.poolSize(Some(" 4 ")), 4)
    assertEquals(DbInit.poolSize(Some("ten")), 10)
    assertEquals(DbInit.poolSize(Some("")), 10)
    assertEquals(DbInit.poolSize(Some("0")), 10, "HikariCP refuses a pool smaller than one")
    assertEquals(DbInit.poolSize(Some("-3")), 10)
  }

  test("acquire timeout: unset is 5 s, milliseconds are taken, anything else falls back to 5 s") {
    assertEquals(DbInit.acquireTimeout(None), Duration.ofSeconds(5))
    assertEquals(DbInit.acquireTimeout(Some("1500")), Duration.ofMillis(1500))
    assertEquals(DbInit.acquireTimeout(Some("250")), Duration.ofMillis(250))
    assertEquals(DbInit.acquireTimeout(Some("5s")), Duration.ofSeconds(5))
    assertEquals(DbInit.acquireTimeout(Some("249")), Duration.ofSeconds(5), "HikariCP's floor")
    assertEquals(DbInit.acquireTimeout(Some("0")), Duration.ofSeconds(5), "0 waits forever")
    assertEquals(DbInit.acquireTimeout(Some("-1")), Duration.ofSeconds(5))
  }
}
