package io.eezo.db.schema

import io.eezo.db.internal.util.canonicalCheck

import java.sql.{Connection, SQLException}

/** The model's checks in Postgres's own spelling, so that a live database can be compared with the
  * model without reporting a check that is already there.
  *
  * Postgres keeps a check as an expression tree and hands back its own rendering of it: `between`
  * becomes two comparisons, a literal on a numeric column gains a cast, `in` becomes `= any`. No
  * list of rewrites on our side would keep up with that deparser, so the model's checks are given
  * to Postgres to spell, on a temporary table inside a transaction or savepoint that is always
  * rolled back. This happens only when comparing with a live database: the snapshot, `freeze` and
  * the migration files keep the readable spelling and never need a database (issue 322).
  *
  * A check Postgres refuses to create here, an invalid expression, a column whose name needs
  * quoting, a read only connection, keeps the model's spelling: the comparison then reports it, as
  * it did before, rather than failing, because the dev server asks for this on every request.
  */
object CheckSpelling {

  private val probe = "eezo_check_probe"

  /** One entry point for `status`, `sync` and `DeployCheck.verify`, so the three cannot disagree on
    * which spelling they compare: `Differ` compares strings and knows nothing of a database.
    */
  def drift(c: Connection, model: SchemaSnap, schema: String): List[Change] =
    Differ.diff(canonical(Introspect.snapshot(c, schema)), postgres(c, model))

  /** Public beside [[drift]] for a caller that diffs on its own, as the suites do: a snapshot whose
    * checks Postgres has spelled is the only one a live database can be compared with.
    */
  def postgres(c: Connection, model: SchemaSnap): SchemaSnap =
    if (!model.tables.exists(_.columns.exists(_.checks.nonEmpty))) model
    else
      inTransaction(c) {
        model.copy(tables = model.tables.map { t =>
          val checked = t.columns.filter(_.checks.nonEmpty)
          if (checked.isEmpty) t
          else {
            val spelled = spell(c, checked)
            t.copy(columns =
              t.columns.map(col => spelled.get(col.name).fold(col)(cs => col.copy(checks = cs)))
            )
          }
        })
      }

  /** The live side through the same canonicaliser as the model's, here and not in `Introspect`,
    * because `drop` and the suites read `Introspect`'s output as Postgres gave it.
    */
  private def canonical(live: SchemaSnap): SchemaSnap =
    live.copy(tables = live.tables.map { t =>
      t.copy(columns =
        t.columns.map(col => col.copy(checks = col.checks.map(canonicalCheck).sorted))
      )
    })

  /** One temporary table for every checked column of a model table; one per check if Postgres
    * refuses the lot, so one bad check does not cost the others their spelling.
    */
  private def spell(c: Connection, columns: List[ColumnSnap]): Map[String, List[String]] =
    attempt(c)(created(c, columns)).getOrElse {
      columns.map { col =>
        col.name -> col.checks.map { ck =>
          attempt(c)(created(c, List(col.copy(checks = List(ck)))))
            .flatMap(_.get(col.name))
            .collect { case List(one) => one }
            .getOrElse(canonicalCheck(ck))
        }.sorted
      }.toMap
    }

  /** A column whose checks do not come back one for one keeps the model's spelling, because a check
    * Postgres does not tie to exactly that column is one `Introspect` cannot see either, and a
    * spelling borrowed from another check would hide the difference.
    */
  private def created(c: Connection, columns: List[ColumnSnap]): Map[String, List[String]] = {
    val st = c.createStatement()
    try st.execute(Ddl.probeTable(probe, columns)): Unit
    finally st.close()
    val read = Introspect.columnChecks(c, "pg_temp", probe)
    columns.map { col =>
      val back = read.getOrElse(col.name, Nil).map(canonicalCheck)
      col.name -> (if (back.size == col.checks.size) back.sorted
                   else col.checks.map(canonicalCheck).sorted)
    }.toMap
  }

  /** `body` on a savepoint that is rolled back whatever happens, so the probe table is gone before
    * the next probe and a transaction Postgres aborted on a refused check is usable again. Only
    * ever the savepoint: the transaction around it is the caller's, and rolling that back would
    * undo what `sync` or `migrate` has just done in it.
    */
  private def attempt[A](c: Connection)(body: => A): Option[A] = {
    val sp = c.setSavepoint()
    try Some(body)
    catch { case _: SQLException => None }
    finally {
      c.rollback(sp)
      c.releaseSavepoint(sp)
    }
  }

  /** A savepoint needs a transaction, so a connection in autocommit gets one of its own, rolled
    * back, and its autocommit back: `status` borrows its connection from the pool, and one handed
    * back out of autocommit would change the next borrower's transactions.
    */
  private def inTransaction[A](c: Connection)(body: => A): A =
    if (!c.getAutoCommit) body
    else {
      c.setAutoCommit(false)
      try body
      finally
        try c.rollback()
        finally c.setAutoCommit(true)
    }

}
