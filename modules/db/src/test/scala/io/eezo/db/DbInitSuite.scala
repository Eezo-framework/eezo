package io.eezo.db

import munit.FunSuite

import java.time.Duration

/** The `DATABASE_URL` fallback parser: the one secret a PaaS injects, split into the three values
  * JDBC wants. Pure, so a plain suite.
  */
class DbInitSuite extends FunSuite {

  private def parsed(url: String) =
    DbInit.parseDatabaseUrl(url).getOrElse(fail(s"expected $url to parse"))

  test("the full form: user, password, host, port, database") {
    assertEquals(
      parsed("postgres://app:secret@db.internal:5433/prod"),
      DbInit.Parsed("jdbc:postgresql://db.internal:5433/prod", "app", "secret")
    )
  }

  test("postgresql scheme, no port, query string carried onto the jdbc url") {
    assertEquals(
      parsed("postgresql://app:secret@db.internal/prod?sslmode=require"),
      DbInit.Parsed("jdbc:postgresql://db.internal/prod?sslmode=require", "app", "secret")
    )
  }

  test("percent-encoded credentials are decoded") {
    assertEquals(
      parsed("postgres://us%40er:p%40ss%2Fword@host/db"),
      DbInit.Parsed("jdbc:postgresql://host/db", "us@er", "p@ss/word")
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
