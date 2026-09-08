package io.eezo.db.cli

import io.eezo.db.migrate.{Decision, Resolution}
import io.eezo.db.schema.{Change, ColumnSnap}
import munit.FunSuite

/** The JSON shapes are a contract for tools, so they are pinned as strings: a field rename or a
  * shape change must fail here, not in someone's agent.
  */
class RenderJsonSuite extends FunSuite {

  private val drop = Change.DropColumn("todo", "notes")
  private val add  = Change.AddColumn(
    "todo",
    ColumnSnap("priority", "integer", nullable = true, primaryKey = false, Nil, None)
  )

  test("a change carries describe, both classifications, and its sql") {
    val json = RenderJson.status(StatusResult(List(drop)))
    assertEquals(
      json,
      """{
        |  "command": "status",
        |  "inSync": false,
        |  "changes": [
        |    {
        |      "describe": "- todo.notes",
        |      "destructive": true,
        |      "risky": false,
        |      "sql": "alter table \"todo\" drop column \"notes\""
        |    }
        |  ]
        |}""".stripMargin
    )
  }

  test("sync distinguishes refused from preview") {
    val result  = SyncResult(List(drop), blocked = List(drop), applied = false)
    val refused = RenderJson.sync(result, applyRequested = true)
    val preview = RenderJson.sync(result, applyRequested = false)
    assert(refused.contains("\"refused\": true"))
    assert(preview.contains("\"refused\": false"))
  }

  test("freeze renders the decision per resolution and null for no migration") {
    val none = RenderJson.freeze(FreezeResult(None, Nil))
    assert(none.contains("\"migration\": null"))

    val some = RenderJson.freeze(
      FreezeResult(
        Some(java.nio.file.Paths.get("db/migrations/0002_x.sql")),
        List(Resolution(add, Decision.Accept), Resolution(drop, Decision.Skip))
      )
    )
    assert(some.contains("\"migration\": \"db/migrations/0002_x.sql\""))
    assert(some.contains("\"decision\": \"accept\""))
    assert(some.contains("\"decision\": \"skip\""))
  }

  test("migrate discriminates on outcome") {
    assert(RenderJson.migrate(MigrateResult.UpToDate(Nil)).contains("\"outcome\": \"upToDate\""))
    assert(
      RenderJson
        .migrate(MigrateResult.Pending(List(PendingMigration(1, "0001_a.sql", List("create")))))
        .contains("\"outcome\": \"pending\"")
    )
    assert(
      RenderJson.migrate(MigrateResult.Tampered(List("bad"))).contains("\"outcome\": \"tampered\"")
    )
  }

  test("error escapes the message") {
    assertEquals(
      RenderJson.error("""a "quoted" thing"""),
      """{
        |  "error": "a \"quoted\" thing"
        |}""".stripMargin
    )
  }
}
