package io.eezo.db.macros

import munit.FunSuite

/** The decisions of `derives Table` that need no compiler, called directly.
  *
  * `TableMacro` calls these while the compiler expands a derivation, where no assertion can reach
  * them, so this suite is what proves them. `TableDerivationSuite` and `CapabilitySuite` prove the
  * same rules again through a derived `Table`.
  */
class TableRulesSuite extends FunSuite {

  test("the table name is the snake cased class name") {
    assertEquals(TableRules.tableName("Book"), "book")
    assertEquals(TableRules.tableName("PublishingHouse"), "publishing_house")
  }

  test("a field is its snake cased name as a column") {
    assertEquals(TableRules.columnName("publishedOn", isRef = false), "published_on")
  }

  test("a Ref field is its snake cased name with `_id` as a column") {
    assertEquals(TableRules.columnName("publishedBy", isRef = true), "published_by_id")
  }

  test("`id` is the key, and no other name is") {
    assert(TableRules.isKey("id"))
    assert(!TableRules.isKey("ids"))
    assert(!TableRules.isKey(""))
  }

  test("the key name is spelled in lower case only") {
    assert(!TableRules.isKey("ID"))
    assert(!TableRules.isKey("Id"))
  }

  test("the key is found at its place among the fields") {
    assertEquals(TableRules.keyIndex("Book", List("title", "id", "isbn")), Right(1))
    assertEquals(TableRules.keyIndex("Book", List("id")), Right(0))
  }

  test("a model with no fields is refused before its key is looked for") {
    assertEquals(TableRules.keyIndex("Empty", Nil), Left("Empty has no fields."))
  }

  test("a model with no `id` field is refused, and the message says how to add one") {
    assertEquals(
      TableRules.keyIndex("Keyless", List("name", "ids")),
      Left(
        "Keyless has no `id` field. Every Eezo table needs one, e.g. `id: Id[Keyless]`."
      )
    )
  }

  test("an `id` of the wrong type is refused with the type it has") {
    assertEquals(
      TableRules.wrongKeyType("Bad", "String"),
      "`id` must be `Id[Bad]`, but is `String`."
    )
  }

  test("a field with no database mapping is refused, and the message shows the given to add") {
    assertEquals(
      TableRules.noMapping("java.net.URI", "home"),
      """No database mapping for java.net.URI at field `home`.
        |
        |Provide one in scope:
        |  given Column[java.net.URI] = Column[String].imap(...)(...)
        |""".stripMargin
    )
  }
}
