package io.eezo.db.cli

import io.eezo.core.internal.Json
import io.eezo.db.migrate.{Decision, Resolution}
import io.eezo.db.schema.{Change, Ddl, SchemaSnap}

/** The machine front end: the same result values [[Render]] turns into text, as JSON.
  *
  * This is `design/objective.md`'s "every step emits machine-readable output" landing: an agent
  * drives the loop on `--json` and never parses prose. It exists this cheaply only because layer 1
  * returns values; every function here is a fold over one of them.
  *
  * The encoding is deliberately hand rolled over `Json` rather than derived: these shapes are a
  * public contract for tools, and a contract should not silently change because a field was renamed
  * in a Scala case class. Each object carries a `"command"` discriminator so a stream of results
  * needs no out of band context.
  *
  * Every change carries its `sql` alongside the classification, because the reader most likely to
  * be here is deciding whether to run it.
  */
object RenderJson {

  private def change(c: Change): Json = Json.Obj(
    List(
      "describe"    -> Json.Str(c.describe),
      "destructive" -> Json.Bool(c.destructive),
      "risky"       -> Json.Bool(c.risky),
      "sql"         -> Json.Str(Ddl.render(c))
    )
  )

  private def changes(cs: List[Change]): Json = Json.Arr(cs.map(change))

  def status(r: StatusResult): String = Json.render(
    Json.Obj(
      List(
        "command" -> Json.Str("status"),
        "inSync"  -> Json.Bool(r.inSync),
        "changes" -> changes(r.changes)
      )
    )
  )

  def sync(r: SyncResult, applyRequested: Boolean): String = Json.render(
    Json.Obj(
      List(
        "command" -> Json.Str("sync"),
        "applied" -> Json.Bool(r.applied),
        "refused" -> Json.Bool(applyRequested && !r.applied && r.changes.nonEmpty),
        "changes" -> changes(r.changes),
        "blocked" -> changes(r.blocked)
      )
    )
  )

  private def decision(d: Decision): Json = d match {
    case Decision.Accept    => Json.Str("accept")
    case Decision.Skip      => Json.Str("skip")
    case Decision.Manual(_) => Json.Str("manual")
  }

  def freeze(r: FreezeResult): String = Json.render(
    Json.Obj(
      List(
        "command"     -> Json.Str("freeze"),
        "migration"   -> r.migration.map(p => Json.Str(p.toString)).getOrElse(Json.Null),
        "resolutions" -> Json.Arr(
          r.resolutions.map { case Resolution(c, d) =>
            Json.Obj(List("change" -> change(c), "decision" -> decision(d)))
          }
        )
      )
    )
  )

  private def pending(p: PendingMigration): Json = Json.Obj(
    List(
      "number"     -> Json.Num(p.number),
      "file"       -> Json.Str(p.file),
      "statements" -> Json.Arr(p.statements.map(Json.Str.apply))
    )
  )

  def migrate(r: MigrateResult): String = {
    val fields = r match {
      case MigrateResult.Tampered(problems) =>
        List(
          "outcome"  -> Json.Str("tampered"),
          "problems" -> Json.Arr(problems.map(Json.Str.apply))
        )
      case MigrateResult.UpToDate(drift) =>
        List("outcome" -> Json.Str("upToDate"), "drift" -> changes(drift))
      case MigrateResult.Pending(ps) =>
        List("outcome" -> Json.Str("pending"), "pending" -> Json.Arr(ps.map(pending)))
      case MigrateResult.Applied(ps, drift) =>
        List(
          "outcome" -> Json.Str("applied"),
          "applied" -> Json.Arr(ps.map(pending)),
          "drift"   -> changes(drift)
        )
    }
    Json.render(Json.Obj(("command" -> Json.Str("migrate")) :: fields))
  }

  def drop(r: DropResult): String = Json.render(
    Json.Obj(
      List("command" -> Json.Str("drop"), "dropped" -> Json.Arr(r.tables.map(Json.Str.apply)))
    )
  )

  def reset(r: ResetResult): String = Json.render(
    Json.Obj(
      List(
        "command" -> Json.Str("reset"),
        "dropped" -> Json.Arr(r.dropped.map(Json.Str.apply)),
        "ddl"     -> Json.Arr(r.ddl.map(Json.Str.apply))
      )
    )
  )

  def ddl(statements: List[String]): String = Json.render(
    Json.Obj(
      List("command" -> Json.Str("ddl"), "statements" -> Json.Arr(statements.map(Json.Str.apply)))
    )
  )

  def dump(snapshot: SchemaSnap): String = Json.render(
    Json.Obj(List("command" -> Json.Str("dump"), "schema" -> snapshot.toJson))
  )
}
