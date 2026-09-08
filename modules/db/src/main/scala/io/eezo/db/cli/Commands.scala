package io.eezo.db.cli

import io.eezo.db.{DB, Schema, Tx}
import io.eezo.db.migrate.{Decision, DeployCheck, Freeze, Migrator, Resolution}
import io.eezo.db.schema.{Change, Ddl, Differ, Introspect}

import java.nio.file.Path

/** The database edge's commands, as library functions. Ported from `example/Cli.scala`, which was
  * their specification (design/cli.md §3); the two rules of layer 1 apply to every entry point
  * here: return values, never print; take capabilities, not connections. The http edge's are in
  * `io.eezo.http.cli.Commands`, in the same shape.
  *
  * `dbSchema` is the Postgres schema commands introspect, defaulting to `public` the way
  * `Introspect.snapshot` does. It is a parameter for the same reason `Freeze.defaultDbDir` is: with
  * it baked in, nothing here could be exercised outside the real database of whatever process is
  * running — the suites isolate by Postgres schema, and pass their own.
  *
  * `freeze` takes no capability: it diffs the committed snapshot against the code and writes files,
  * and the live database is deliberately not consulted (that is `sync`'s job). Front-ends must not
  * install a `Database` to run it.
  */
object Commands {

  /** The live database against the model, as a diff. */
  def status(schema: Schema, dbSchema: String = "public")(using DB): StatusResult =
    StatusResult(Differ.diff(Introspect.snapshot(Conn.connection, dbSchema), schema.snapshot))

  /** The same diff, executed directly when `apply` — dev only, migrations are the reviewed path.
    *
    * A destructive or risky change refuses an unforced apply, exactly as `example/Cli.scala` did;
    * `force` applies everything, including what was blocked.
    */
  def sync(schema: Schema, apply: Boolean, force: Boolean, dbSchema: String = "public")(using
      Tx
  ): SyncResult = {
    val changes = Differ.diff(Introspect.snapshot(Conn.connection, dbSchema), schema.snapshot)
    val blocked = changes.filter(c => c.destructive || c.risky)
    val run     = apply && changes.nonEmpty && (blocked.isEmpty || force)
    if (run) execute(Ddl.render(changes))
    SyncResult(changes, blocked, applied = run)
  }

  /** Drift since the last freeze, written as a numbered, fingerprinted migration.
    *
    * `decide` is asked about **every** change, not only destructive ones: policy belongs to the
    * front-end — the interactive one auto-accepts what is safe and prompts on the rest, an agent
    * passes its own — and a mechanism that pre-filtered would leave `--skip-destructive`-shaped
    * policies nowhere to live.
    */
  def freeze(
      schema: Schema,
      name: String,
      decide: Change => Decision,
      dir: Path = Freeze.defaultDbDir
  ): FreezeResult = {
    val to      = schema.snapshot
    val changes = Differ.diff(Freeze.committedSnapshot(dir), to)
    if (changes.isEmpty) FreezeResult(None, Nil)
    else {
      val resolutions = changes.map(c => Resolution(c, decide(c)))
      FreezeResult(Some(Freeze.write(name, resolutions, to, dir)), resolutions)
    }
  }

  /** Pending migrations listed, applied when `apply`, and the database verified against the model
    * once the ledger is settled.
    */
  def migrate(
      schema: Schema,
      apply: Boolean,
      dir: Path = Freeze.defaultDbDir,
      dbSchema: String = "public"
  )(using Tx): MigrateResult =
    Migrator.status(dir) match {
      case Migrator.Status.Tampered(problems) => MigrateResult.Tampered(problems)
      case Migrator.Status.Ok(Nil)            => MigrateResult.UpToDate(verify(schema, dbSchema))
      case Migrator.Status.Ok(pending)        =>
        val listed = pending.map { case (n, file, stmts) => PendingMigration(n, file, stmts) }
        if (!apply) MigrateResult.Pending(listed)
        else {
          Migrator.apply(pending)
          MigrateResult.Applied(listed, verify(schema, dbSchema))
        }
    }

  /** Every table in the live schema dropped, and the migration ledger with them. */
  def drop(dbSchema: String = "public")(using Tx): DropResult = {
    val tables = Introspect.snapshot(Conn.connection, dbSchema).tables.map(_.name)
    execute(
      tables.map(t => s"""drop table if exists "$t" cascade""") :+
        """drop table if exists "eezo_migrations""""
    )
    DropResult(tables)
  }

  /** [[drop]], then the model's full DDL — the same code path migrations take. */
  def reset(schema: Schema, dbSchema: String = "public")(using Tx): ResetResult = {
    val dropped = drop(dbSchema)
    execute(schema.ddl)
    ResetResult(dropped.tables, schema.ddl)
  }

  def ddl(schema: Schema): List[String] = schema.ddl

  def dump(schema: Schema): String = schema.snapshot.render

  private def verify(schema: Schema, dbSchema: String)(using Tx): List[Change] =
    DeployCheck.verify(Conn.connection, schema.snapshot, dbSchema).fold(identity, _ => Nil)

  private def execute(statements: List[String])(using Tx): Unit = {
    val st = Conn.connection.createStatement()
    try statements.foreach(s => st.execute(s))
    finally st.close()
  }
}
