package io.eezo.sbt.dev

/** The reader against the shapes `docs/reference/cli.md` documents, pretty printed as the commands
  * print them and compact as the marker lines carry them.
  */
class JsonReaderSuite extends munit.FunSuite {

  val status: String =
    """{
      |  "command": "status",
      |  "inSync": false,
      |  "changes": [
      |    {
      |      "describe": "- book.notes",
      |      "destructive": true,
      |      "risky": false,
      |      "sql": "alter table \"book\" drop column \"notes\""
      |    }
      |  ]
      |}""".stripMargin

  test("the status shape, pretty printed") {
    val j = JsonReader.parse(status).fold(fail(_), identity)
    assertEquals(j("command").str, Some("status"))
    assertEquals(j("inSync").bool, Some(false))
    val change = j("changes").arr.head
    assertEquals(change("describe").str, Some("- book.notes"))
    assertEquals(change("destructive").bool, Some(true))
    assertEquals(change("sql").str, Some("alter table \"book\" drop column \"notes\""))
  }

  test("the same shape compact, as a marker carries it") {
    val line =
      """{"command":"status","inSync":false,"changes":[{"describe":"- book.notes","destructive":true,"risky":false,"sql":"alter table \"book\" drop column \"notes\""}]}"""
    assertEquals(
      JsonReader.parse(line).map(Drift.fromJson),
      Right(
        Drift(
          Vector(
            Change(
              "- book.notes",
              destructive = true,
              risky = false,
              "alter table \"book\" drop column \"notes\""
            )
          ),
          blocking = true
        )
      )
    )
  }

  test("a routes listing decodes into rows, shadowed pairs and orphans") {
    val line =
      """{"command":"routes","routes":[{"method":"GET","path":"/","provenance":"file","api":false},{"method":"WS","path":"/live","provenance":"framework","api":false}],
        |"overridden":[],"shadowed":[{"earlier":{"method":"GET","path":"/*rest","provenance":"file","api":false},"later":{"method":"GET","path":"/x","provenance":"derived","api":false}}],
        |"orphans":[{"page":"new","pageRoute":"/books/new","target":"create","targetRoute":"/books"}]}""".stripMargin
    val listing = JsonReader.parse(line).map(RouteListing.fromJson).fold(fail(_), identity)
    assertEquals(listing.routes.map(_.path), Vector("/", "/live"))
    assertEquals(listing.shadowed.head._2.path, "/x")
    assertEquals(listing.orphans.head.targetRoute, "/books")
  }

  test("a missing field reads as null, so a lookup chain never throws") {
    val j = JsonReader.parse("""{"a":{"b":1}}""").fold(fail(_), identity)
    assertEquals(j("a")("b").num, Some(1L))
    assertEquals(j("a")("c")("d").str, None)
    assertEquals(j("x").arr, Vector.empty[JValue])
    assertEquals(j("x").strOr("dflt"), "dflt")
  }

  test("escapes, negative numbers, literals and whitespace") {
    assertEquals(
      JsonReader.parse("""  [ "a\"b\\c\né" , -12 , true , false , null ]  """),
      Right(JArr(Vector(JStr("a\"b\\c\né"), JNum(-12), JBool(true), JBool(false), JNull)))
    )
  }

  test("malformed input is refused with an offset") {
    assert(JsonReader.parse("""{"a":""").isLeft)
    assert(JsonReader.parse("""[1,]""").left.exists(_.contains("offset")))
    assert(JsonReader.parse("""{"a":1} trailing""").left.exists(_.contains("end of input")))
    assert(JsonReader.parse("1.5").left.exists(_.contains("integer")))
  }
}
