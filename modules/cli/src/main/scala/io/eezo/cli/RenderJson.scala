package io.eezo.cli

import io.eezo.db.internal.J
import io.eezo.db.migrate.{Decision, Resolution}
import io.eezo.db.schema.{Change, Ddl, SchemaSnap}
import io.eezo.http.Route

/** The machine front-end: the same result values [[Render]] turns into text, as JSON.
  *
  * This is `design/objective.md`'s "every step emits machine-readable output" landing — an agent
  * drives the loop on `--json` and never parses prose. It exists this cheaply only because layer 1
  * returns values; every function here is a fold over one of them.
  *
  * The encoding is deliberately hand-rolled over `J` rather than derived: these shapes are a public
  * contract for tools, and a contract should not silently change because a field was renamed in a
  * Scala case class. Each object carries a `"command"` discriminator so a stream of results needs
  * no out-of-band context.
  *
  * Every change carries its `sql` alongside the classification, because the reader most likely to
  * be here is deciding whether to run it.
  */
object RenderJson {

  private def change(c: Change): J = J.O(
    List(
      "describe"    -> J.S(c.describe),
      "destructive" -> J.B(c.destructive),
      "risky"       -> J.B(c.risky),
      "sql"         -> J.S(Ddl.render(c))
    )
  )

  private def changes(cs: List[Change]): J = J.A(cs.map(change))

  def status(r: StatusResult): String = J.render(
    J.O(
      List(
        "command" -> J.S("status"),
        "inSync"  -> J.B(r.inSync),
        "changes" -> changes(r.changes)
      )
    )
  )

  def sync(r: SyncResult, applyRequested: Boolean): String = J.render(
    J.O(
      List(
        "command" -> J.S("sync"),
        "applied" -> J.B(r.applied),
        "refused" -> J.B(applyRequested && !r.applied && r.changes.nonEmpty),
        "changes" -> changes(r.changes),
        "blocked" -> changes(r.blocked)
      )
    )
  )

  private def decision(d: Decision): J = d match {
    case Decision.Accept    => J.S("accept")
    case Decision.Skip      => J.S("skip")
    case Decision.Manual(_) => J.S("manual")
  }

  def freeze(r: FreezeResult): String = J.render(
    J.O(
      List(
        "command"     -> J.S("freeze"),
        "migration"   -> r.migration.map(p => J.S(p.toString)).getOrElse(J.Nul),
        "resolutions" -> J.A(
          r.resolutions.map { case Resolution(c, d) =>
            J.O(List("change" -> change(c), "decision" -> decision(d)))
          }
        )
      )
    )
  )

  private def pending(p: PendingMigration): J = J.O(
    List(
      "number"     -> J.N(p.number),
      "file"       -> J.S(p.file),
      "statements" -> J.A(p.statements.map(J.S.apply))
    )
  )

  def migrate(r: MigrateResult): String = {
    val fields = r match {
      case MigrateResult.Tampered(problems) =>
        List("outcome" -> J.S("tampered"), "problems" -> J.A(problems.map(J.S.apply)))
      case MigrateResult.UpToDate(drift) =>
        List("outcome" -> J.S("upToDate"), "drift" -> changes(drift))
      case MigrateResult.Pending(ps) =>
        List("outcome" -> J.S("pending"), "pending" -> J.A(ps.map(pending)))
      case MigrateResult.Applied(ps, drift) =>
        List(
          "outcome" -> J.S("applied"),
          "applied" -> J.A(ps.map(pending)),
          "drift"   -> changes(drift)
        )
    }
    J.render(J.O(("command" -> J.S("migrate")) :: fields))
  }

  private def route(r: Route): J = {
    val (method, path) = r match {
      case Route.Http(m, pattern, _, _) => (m.toString, pattern.render)
      case Route.Ws(pattern, _, _)      => ("WS", pattern.render)
    }
    J.O(
      List(
        "method"     -> J.S(method),
        "path"       -> J.S(path),
        "provenance" -> J.S(r.provenance.toString.toLowerCase)
      )
    )
  }

  def routes(r: RouteListing): String = J.render(
    J.O(
      List(
        "command"    -> J.S("routes"),
        "routes"     -> J.A(r.routes.toList.map(route)),
        "overridden" -> J.A(r.overridden.toList.map(route)),
        "shadowed"   -> J.A(
          r.shadowed.toList.map { case (earlier, later) =>
            J.O(List("earlier" -> route(earlier), "later" -> route(later)))
          }
        ),
        "orphans" -> J.A(
          r.orphans.toList.map(o =>
            J.O(
              List(
                "page"        -> J.S(o.page.toString),
                "pageRoute"   -> J.S(o.pageRoute),
                "target"      -> J.S(o.target.toString),
                "targetRoute" -> J.S(o.targetRoute)
              )
            )
          )
        )
      )
    )
  )

  def drop(r: DropResult): String = J.render(
    J.O(List("command" -> J.S("drop"), "dropped" -> J.A(r.tables.map(J.S.apply))))
  )

  def reset(r: ResetResult): String = J.render(
    J.O(
      List(
        "command" -> J.S("reset"),
        "dropped" -> J.A(r.dropped.map(J.S.apply)),
        "ddl"     -> J.A(r.ddl.map(J.S.apply))
      )
    )
  )

  def ddl(statements: List[String]): String = J.render(
    J.O(List("command" -> J.S("ddl"), "statements" -> J.A(statements.map(J.S.apply))))
  )

  def dump(snapshot: SchemaSnap): String = J.render(
    J.O(List("command" -> J.S("dump"), "schema" -> snapshot.toJson))
  )

  def error(message: String): String =
    J.render(J.O(List("error" -> J.S(message))))
}
