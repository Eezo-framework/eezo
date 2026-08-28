package io.eezo.db

import io.eezo.db.Scopes.*
import io.eezo.db.support.{Book, DbSuite, Library, PublishingHouse}
import io.eezo.core.Id

/** The query DSL: what it renders, and what it does against Postgres.
  *
  * The rendering cases need no database — a `Query[T]` is a pure description — but they live here
  * so that the same suite proves the SQL it pins is the SQL that runs.
  */
class QuerySuite extends DbSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    create(Library)
  }

  private val house = Table[PublishingHouse]
  private val cols  = """"id", "name", "location""""

  private def sqlOf(q: Query[PublishingHouse]): String = q.selectSql._1
  private def bindsOf(q: Query[PublishingHouse]): Int  = q.selectSql._2.size

  // ── what it renders ────────────────────────────────────────────────────────────────

  test("no predicate renders a whole-table select") {
    assertEquals(sqlOf(house.query), s"""select $cols from "publishing_house" where true""")
  }

  test("one comparison is parameterised") {
    val q = house.where(_.name === "Faber")
    assertEquals(sqlOf(q), s"""select $cols from "publishing_house" where "name" = ?""")
    assertEquals(bindsOf(q), 1)
  }

  test("chained wheres do not accumulate `true and`") {
    val q = house.where(_.name === "Faber").where(_.location === "London")
    assertEquals(
      sqlOf(q),
      s"""select $cols from "publishing_house" where ("name" = ? and "location" = ?)"""
    )
    assertEquals(bindsOf(q), 2)
  }

  test("ordering, limit and offset land in that order") {
    val q =
      house.where(_.name <> "x").orderBy(_.name.desc).orderBy(_.location.asc).limit(20).offset(40)
    assertEquals(
      sqlOf(q),
      s"""select $cols from "publishing_house" where "name" <> ? order by "name" desc, "location" asc limit 20 offset 40"""
    )
  }

  test("in renders one hole per value") {
    val q = house.where(_.name in Seq("a", "b", "c"))
    assertEquals(sqlOf(q), s"""select $cols from "publishing_house" where "name" in (?, ?, ?)""")
    assertEquals(bindsOf(q), 3)
  }

  test("an empty in matches nothing rather than being a syntax error") {
    val q = house.where(_.name in Nil)
    assertEquals(sqlOf(q), s"""select $cols from "publishing_house" where false""")
    assertEquals(bindsOf(q), 0)
  }

  test("negation and disjunction") {
    val q = house.where(c => !(c.name === "a") or c.location === "b")
    assertEquals(
      sqlOf(q),
      s"""select $cols from "publishing_house" where (not "name" = ? or "location" = ?)"""
    )
  }

  test("a Ref column renders its _id name, not the field name") {
    val q = Table[Book].query.where(_.author === Ref.to(Id.gen[io.eezo.db.support.Author]()))
    assert(q.selectSql._1.contains(""""author_id" = ?"""), q.selectSql._1)
  }

  test("the select list is the one TableDef renders, not a second copy") {
    assert(sqlOf(house.query).startsWith(house.selectAllSql), house.selectAllSql)
  }

  test("count and delete render against the same table") {
    assertEquals(
      house.query.where(_.name === "x").countSql._1,
      """select count(*) from "publishing_house" where "name" = ?"""
    )
    assertEquals(house.tableDef.deleteAll, """delete from "publishing_house"""")
  }

  // ── what it does ───────────────────────────────────────────────────────────────────

  private def seed(): Unit = transact {
    house.insert(PublishingHouse(Id.gen(), "Faber", "London"))
    house.insert(PublishingHouse(Id.gen(), "Verso", "London"))
    house.insert(PublishingHouse(Id.gen(), "Fitzcarraldo", "Deptford"))
  }

  test("list applies the predicate") {
    seed()
    assertEquals(
      read { house.where(_.location === "London").list() }.map(_.name).sorted,
      List("Faber", "Verso")
    )
  }

  test("ordering and paging are the database's, not Scala's") {
    seed()
    val names = read { house.orderBy(_.name.asc).limit(2).list() }.map(_.name)
    assertEquals(names, List("Faber", "Fitzcarraldo"))
    val skipped = read { house.orderBy(_.name.asc).limit(2).offset(2).list() }.map(_.name)
    assertEquals(skipped, List("Verso"))
  }

  test("first returns one row or none") {
    seed()
    assertEquals(read { house.where(_.name === "Verso").first() }.map(_.location), Some("London"))
    assertEquals(read { house.where(_.name === "nobody").first() }, None)
  }

  test("count counts without decoding") {
    seed()
    assertEquals(read { house.where(_.location === "London").count() }, 2L)
    assertEquals(read { house.query.count() }, 3L)
  }

  test("in matches a set of values") {
    seed()
    assertEquals(read { house.where(_.name in Seq("Faber", "Fitzcarraldo")).count() }, 2L)
    assertEquals(read { house.where(_.name in Nil).count() }, 0L)
  }

  test("deleteWhere removes only what it matched, and says how many") {
    seed()
    val removed = transact { house.deleteWhere(_.location === "London") }
    assertEquals(removed, 2)
    assertEquals(read { house.query.count() }, 1L)
  }

  test("deleteAll wipes the table") {
    seed()
    assertEquals(transact { house.deleteAll() }, 3)
    assertEquals(read { house.query.count() }, 0L)
  }

  test("a query is a value: it can be built outside any scope and run later") {
    seed()
    val londoners = house.where(_.location === "London").orderBy(_.name.asc)
    assertEquals(read { londoners.list() }.map(_.name), List("Faber", "Verso"))
    assertEquals(read { londoners.count() }, 2L)
  }
}
