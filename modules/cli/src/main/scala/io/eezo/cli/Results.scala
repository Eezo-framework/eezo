package io.eezo.cli

import io.eezo.db.migrate.Resolution
import io.eezo.db.schema.Change
import io.eezo.http.{Action, Route}

import java.nio.file.Path

/** What the commands return. Layer 1's whole contract is here (design/cli.md §4): a command
  * computes one of these values and never prints, so a front-end can render it as text, as JSON, or
  * as a page — the machine-readable requirement of `design/objective.md` is satisfied by these
  * types or nowhere.
  */

/** The model/database diff. Empty means in sync. */
final case class StatusResult(changes: List[Change]) {
  def inSync: Boolean = changes.isEmpty
}

/** The diff, what part of it blocks an unforced apply, and whether it was executed.
  *
  * `applied` is false both when apply was never requested and when it was refused over `blocked`;
  * the caller knows which flags it passed, so the distinction is the front-end's to render rather
  * than a field's to carry.
  */
final case class SyncResult(changes: List[Change], blocked: List[Change], applied: Boolean)

/** The migration written, if there was drift to write. `resolutions` records what `decide` answered
  * per change, so a front-end can echo what was accepted, skipped, or hand-written.
  */
final case class FreezeResult(migration: Option[Path], resolutions: List[Resolution])

/** One on-disk migration the ledger has not seen, as `Migrator.status` reports it. */
final case class PendingMigration(number: Int, file: String, statements: List[String])

/** The four ways `migrate` ends. `drift` is `DeployCheck`'s answer after the ledger is settled:
  * empty means the database matches the model.
  */
enum MigrateResult {
  case Tampered(problems: List[String])
  case UpToDate(drift: List[Change])
  case Pending(pending: List[PendingMigration])
  case Applied(applied: List[PendingMigration], drift: List[Change])
}

/** What `drop` removed, by table name. The ledger is dropped too but not listed: `Introspect` hides
  * it from every snapshot, so listing it here would name a table no other command admits exists.
  */
final case class DropResult(tables: List[String])

final case class ResetResult(dropped: List[String], ddl: List[String])

/** [[io.eezo.http.Resource]]'s `Orphan`, re-stated with cli-owned visibility.
  *
  * `Orphan` itself is `private[eezo]`, so a public result type cannot carry it; the fields are
  * copied instead of the type being widened, because "a derived form page whose submit target is
  * not mounted" is a warning eezo emits, not a vocabulary users build on.
  */
final case class OrphanedPage(page: Action, pageRoute: String, target: Action, targetRoute: String)

/** The assembled table, with everything boot warns about: the derived routes handwritten ones
  * replaced, the pairs where an earlier route swallows a later one, and the form pages whose submit
  * target is not mounted.
  */
final case class RouteListing(
    routes: Seq[Route],
    overridden: Seq[Route],
    shadowed: Seq[(Route, Route)],
    orphans: Seq[OrphanedPage]
)
