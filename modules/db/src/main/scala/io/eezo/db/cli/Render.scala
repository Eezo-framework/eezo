package io.eezo.db.cli

import io.eezo.db.migrate.{Decision, Resolution}
import io.eezo.db.schema.Change

/** The text front end's rendering: result values in, `String` out, printing left to the caller.
  *
  * This is `example/Cli.scala`'s output, moved. It stays a separate object from [[Commands]]
  * because it is one front end among several (the JSON renderer is the same shape over the same
  * values), and because layer 1's rule is that nothing in it decides what a user sees.
  */
object Render {

  private def flag(c: Change): String =
    if (c.destructive) "  [destructive]" else if (c.risky) "  [risky]" else ""

  /** One change with its classification: the line every listing prints, exposed for front ends that
    * lay their own page around it; the dev server's drift page is one.
    */
  def change(c: Change): String = s"${c.describe}${flag(c)}"

  private def changes(cs: List[Change]): String =
    cs.map(c => s"  ${change(c)}").mkString("\n")

  def status(r: StatusResult): String =
    if (r.inSync) "in sync ✓"
    else s"${r.changes.size} difference(s) between model and database:\n\n${changes(r.changes)}"

  /** `applyRequested` is the flag the caller passed, which the result deliberately does not carry:
    * it is what distinguishes "refused" from "preview" when nothing was applied.
    */
  def sync(r: SyncResult, applyRequested: Boolean): String =
    if (r.changes.isEmpty) "in sync ✓"
    else {
      val tail =
        if (r.applied) "applied ✓"
        else if (applyRequested)
          s"refusing: ${r.blocked.size} change(s) need review. --force to override."
        else "--apply to execute"
      s"${changes(r.changes)}\n\n$tail"
    }

  def freeze(r: FreezeResult): String = r.migration match {
    case None      => "nothing to freeze"
    case Some(out) =>
      val lines = r.resolutions
        .map { case Resolution(c, decision) =>
          val note = decision match {
            case Decision.Accept    => ""
            case Decision.Skip      => "  (skipped)"
            case Decision.Manual(_) => "  (manual sql)"
          }
          s"  ${c.describe}${flag(c)}$note"
        }
        .mkString("\n")
      s"${r.resolutions.size} change(s) since last freeze:\n\n$lines\n\nwrote $out"
  }

  def migrate(r: MigrateResult): String = r match {
    case MigrateResult.Tampered(problems) =>
      val listed = problems.map(p => s"  ✗ $p").mkString("\n")
      s"$listed\nmigration integrity check failed.\n" +
        "Migrations are generated. Change the model and re-freeze; don't edit the files."
    case MigrateResult.UpToDate(drift) =>
      s"no pending migrations\n${verified(drift)}"
    case MigrateResult.Pending(pending) =>
      s"${listing(pending)}\n\n--apply to execute"
    case MigrateResult.Applied(applied, drift) =>
      s"${listing(applied)}\n\napplied ✓\n${verified(drift)}"
  }

  private def listing(pending: List[PendingMigration]): String =
    pending
      .map(p => f"  ${p.number}%04d  ${p.file}  (${p.statements.size} statements)")
      .mkString("\n")

  private def verified(drift: List[Change]): String =
    if (drift.isEmpty) "database matches model ✓"
    else s"${changes(drift)}\ndatabase does not match model (${drift.size} difference(s))"
}
