package io.eezo.db

import munit.FunSuite

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
}
