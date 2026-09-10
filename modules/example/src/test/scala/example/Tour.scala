package example

import io.eezo.db.DbApp
import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.core.Id
import io.eezo.db.migrate.*
import io.eezo.db.schema.*

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.io.StdIn
import scala.util.control.NonFatal

/** A guided run through everything the `db` module does, against a real Postgres.
  *
  * Each chapter changes something and then shows what the framework noticed. Nothing here is
  * simulated: the drift is real ALTERs, the rejections are real constraint violations, and the
  * migrations are real files on disk that you can open.
  *
  * sbt "example/Test/runMain example.Tour" steps through it; TOUR_NOPAUSE=1 runs it start to
  * finish. An environment variable rather than an argument, because the empty argument list is
  * the application and any first argument is a command; and rather than a `-D` property, because
  * `Test / run` forks a child JVM that inherits the environment but not the sbt launcher's system
  * properties.
  */
object Tour extends DbApp {

  /** The tour starts its own Postgres, so it needs nothing installed and disturbs nothing.
    *
    * It lives in `src/test` for the container rather than because it is a test: it asserts nothing
    * and prints everything. `sbt "example/Test/runMain example.Tour"`, `TOUR_NOPAUSE=1` to let it run
    * straight through.
    */
  private lazy val container: PostgreSQLContainer[?] = {
    val c = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    c.start()
    sys.addShutdownHook(c.stop())
    c
  }

  override def schema: Schema = AppSchema

  override def databaseUrl: String      = container.getJdbcUrl
  override def databaseUser: String     = container.getUsername
  override def databasePassword: String = container.getPassword

  /** The tour works in its own schema, so eezo's connections have to be told about it too;
    * otherwise the migrator writes to `public` while the drift checks read `eezo_tour`, and a
    * replay looks like it never happened. DESIGN §8.7.
    */
  // `Connection => Unit`, not `->`: this module does not enable capture checking, so eezo's pure
  // arrow presents here as an ordinary function type (research/capture-checking.md §6.3).
  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try st.execute(s"""create schema if not exists "$Schema0"""")
    catch { case NonFatal(_) => () }
    finally st.close()
    val st2 = c.createStatement()
    try st2.execute(s"""set search_path to "$Schema0"""")
    finally st2.close()
  }

  private var paused = true

  override def boot(): Unit = {
    paused = !sys.env.contains("TOUR_NOPAUSE")
    val migrations = Files.createTempDirectory("eezo-tour")

    connect() match {
      case None    => ()
      case Some(c) =>
        try {
          prepare(c)
          ch1_theModelIsTheSchema()
          ch2_ddlFromTheModel(c)
          ch3_rows(c)
          ch4_constraintsHold(c)
          ch5_drift(c)
          ch6_syncRepairs(c)
          ch7_destructiveAndRisky(c)
          ch8_freeze(c, migrations)
          ch9_replay(c, migrations)
          ch10_tamper(c, migrations)
          ch11_rollback(c)
          finish(c, migrations)
        } finally c.close()
    }
  }

  // ── chapters ─────────────────────────────────────────────────────────────────────────

  private def ch1_theModelIsTheSchema(): Unit = {
    chapter(1, "The model is the schema")
    note("`case class Book(...) derives Table` is the whole declaration. No annotations, no")
    note("XML, no separate migration DSL. Here is what the macro made of it:")
    blank()

    val t = Table[Book]
    println(s"  Table[Book].tableName = ${t.tableName}")
    blank()
    println(
      f"  ${"column"}%-18s ${"type"}%-14s ${"null"}%-6s ${"pk"}%-4s ${"references"}%-18s checks"
    )
    println("  " + "─" * 84)
    t.columns.foreach { c =>
      println(
        f"  ${c.name}%-18s ${c.pgType.render}%-14s ${c.nullable}%-6s ${c.primaryKey}%-4s " +
          f"${c.references.getOrElse("")}%-18s ${c.checks.mkString(", ")}"
      )
    }
    blank()
    note("Three things to notice, none of which the model mentions:")
    note("  · `author: Ref[Author]` became `author_id uuid`, with a foreign key to `author`.")
    note("  · `title: Title` carries a CHECK, because the opaque type's Column has one.")
    note("  · `Option[...]` is what made a column nullable.")
    pause()
  }

  private def ch2_ddlFromTheModel(c: Connection): Unit = {
    chapter(2, "DDL comes out of the same differ migrations use")
    note("`AppSchema.ddl` is the diff from an empty database to the model. That is the same")
    note("code path a migration takes, so `reset` and `migrate` cannot disagree.")
    blank()
    AppSchema.ddl.foreach(sql)
    pause("Press Enter to execute this against the database")
    AppSchema.ddl.foreach(exec(c, _))
    ok(s"${AppSchema.ddl.size} statements executed")
    pause()
  }

  private def ch3_rows(c: Connection): Unit = {
    chapter(3, "Rows go through generated codecs")
    note("`encode`/`decode` are straight-line code the macro wrote — no reflection, and the")
    note("ids are minted in Scala before the row exists, so a batch needs no RETURNING.")
    blank()

    val authors = Table[Author]
    val books   = Table[Book]
    val herbert = Author.herbert
    val student = Author.student

    val ai = c.prepareStatement(authors.insertSql)
    authors.encode(ai, 1, herbert); ai.addBatch()
    authors.encode(ai, 1, student); ai.addBatch()
    ai.executeBatch(); ai.close()

    val bi = c.prepareStatement(books.insertSql)
    Book.seedBooks.foreach { b => books.encode(bi, 1, b); bi.addBatch() }
    bi.executeBatch(); bi.close()
    ok(s"inserted 2 authors and ${Book.seedBooks.size} books")
    blank()

    val ps   = c.prepareStatement(books.selectAllSql)
    val rs   = ps.executeQuery()
    val rows = Iterator.continually(rs).takeWhile(_.next()).map(books.decode(_, 1)).toList
    rs.close(); ps.close()

    rows.foreach { b =>
      println(
        f"  ${b.title.value}%-16s published ${b.publishedOn.map(_.toString).getOrElse("(unknown)")}"
      )
    }
    blank()
    note("`(unknown)` is a real SQL NULL that came back as `None`, through the same")
    note("`Column[Option[A]]` given that made the column nullable in chapter 1.")
    pause()
  }

  private def ch4_constraintsHold(c: Connection): Unit = {
    chapter(4, "The constraints are real")
    note("Everything the model implied is enforced by Postgres, not by application code.")
    blank()

    val books = Table[Book]
    val ghost = Book(
      Id.gen(),
      Ref[Author](java.util.UUID.randomUUID()),
      Title("Ghost"),
      None,
      None,
      None,
      "paperback"
    )
    val dup     = Book.seedBooks.head.copy(id = Id.gen())
    val tooLong =
      Book(Id.gen(), Ref.to(Author.herbert.id), Title("x" * 101), None, None, None, "paperback")

    attempt(c, "an author that does not exist", books, ghost)
    attempt(c, "a title that is already taken", books, dup)
    attempt(c, "a title of 101 characters", books, tooLong)
    blank()
    note("The third one is the interesting one: nothing in `Book` mentions a length limit.")
    note("It arrived from `Title`'s own Column, and became a CHECK constraint.")
    pause()
  }

  private def ch5_drift(c: Connection): Unit = {
    chapter(5, "Drift: change the database behind the framework's back")
    note("This is what a colleague running an ALTER in production looks like. We do three:")
    blank()
    val edits = List(
      """alter table "book" drop column "isbn"""",
      """alter table "book" add column "internal_note" text""",
      """create index "by_hand" on "author" ("name")"""
    )
    edits.foreach { s => sql(s); exec(c, s) }
    pause("Press Enter to ask the framework what it thinks")

    val d = Differ.diff(snapshotOf(c), AppSchema.snapshot)
    println(s"  ${d.size} difference(s) between model and database:")
    blank()
    d.foreach(ch => println(s"    ${ch.describe}${flag(ch)}"))
    blank()
    note("Nobody told it what changed. It read the catalog, rendered it as a snapshot, and")
    note("compared it to the one derived from the model — the same `==` in both directions.")
    pause()
  }

  private def ch6_syncRepairs(c: Connection): Unit = {
    chapter(6, "Sync: reconcile a dev database against the model")
    val d = Differ.diff(snapshotOf(c), AppSchema.snapshot)
    note("The diff renders straight to SQL:")
    blank()
    Ddl.render(d).foreach(sql)
    pause("Press Enter to apply it")
    Ddl.render(d).foreach(exec(c, _))

    val after = Differ.diff(snapshotOf(c), AppSchema.snapshot)
    if (after.isEmpty) ok("in sync — the database now matches the model exactly")
    else warn(s"still ${after.size} difference(s)")
    pause()
  }

  private def ch7_destructiveAndRisky(c: Connection): Unit = {
    chapter(7, "Destructive and risky are different things")
    note("Losing data and failing to apply are separate problems, so they are separate flags.")
    note("Conflating them gives you one vague `are you sure?` that everybody clicks through.")
    blank()
    val edits = List(
      """alter table "book" add column "scratch" integer""",
      """alter table "book" alter column "format" drop not null"""
    )
    edits.foreach { s => sql(s); exec(c, s) }
    blank()

    val d = Differ.diff(snapshotOf(c), AppSchema.snapshot)
    d.foreach(ch => println(s"    ${ch.describe}${flag(ch)}"))
    blank()
    note("· destructive — always succeeds, and loses data. Dropping `scratch` is fine here,")
    note("  but the framework cannot know that, so a human decides at freeze time.")
    note("· risky — loses nothing, but may fail on the rows already in the table. Setting")
    note("  `format` back to NOT NULL fails if any row has NULL in it.")
    blank()
    note("`sync --apply` refuses to run either without --force. Let us prove the risky one")
    note("is not theoretical:")
    blank()
    val nullOut = """update "book" set "format" = null"""
    sql(nullOut)
    exec(c, nullOut)
    val risky = d.filter(_.risky)
    risky.foreach { ch =>
      val s = Ddl.render(ch)
      sql(s)
      try { exec(c, s); warn("...that succeeded, which it should not have") }
      catch { case NonFatal(e) => rejected(oneLine(e)) }
    }
    blank()
    note("That is the failure a migration has to plan for — with a backfill, or an explicit")
    note("waiver. Repairing the data first:")
    exec(c, """update "book" set "format" = 'paperback' where "format" is null""")
    Ddl.render(Differ.diff(snapshotOf(c), AppSchema.snapshot)).foreach(exec(c, _))
    ok("in sync again")
    pause()
  }

  private def ch8_freeze(c: Connection, dir: Path): Unit = {
    chapter(8, "Freeze: turn a model change into a reviewable file")
    note("`freeze` never touches a database. It compares the committed snapshot in")
    note("db/schema.json against the model, and writes the difference as SQL you review.")
    blank()
    note("To show that, pretend the last freeze happened before `isbn` was added:")
    blank()

    val v1 = SchemaSnap(AppSchema.snapshot.tables.map { t =>
      if (t.name == "book") t.copy(columns = t.columns.filterNot(_.name == "isbn")) else t
    })

    Freeze.write(
      "initial",
      Differ.diff(SchemaSnap(Nil), v1).map(Resolution(_, Decision.Accept)),
      v1,
      dir
    )
    ok("0001_initial.sql — the schema as it was")

    val pending = Differ.diff(v1, AppSchema.snapshot)
    pending.foreach(ch => println(s"    ${ch.describe}${flag(ch)}"))
    val file = Freeze.write(
      "add isbn to book",
      pending.map(Resolution(_, Decision.Accept)),
      AppSchema.snapshot,
      dir
    )
    blank()
    println(Files.readString(file).linesIterator.map("  " + _).mkString("\n"))
    note(s"Both files are in $dir")
    blank()
    note("The fingerprint is the point: it is a hash of the statements, and it is checked")
    note("before anything runs. A migration is generated, like package-lock.json — if it is")
    note("wrong you change the model and re-freeze, you do not edit the file.")
    pause()
  }

  private def ch9_replay(c: Connection, dir: Path): Unit = {
    chapter(9, "Migrate: replay from empty and verify against the model")
    note("Dropping everything first, so this is a genuine from-scratch replay.")
    blank()
    dropAll(c)
    ok("database emptied")

    transact { Migrator.status(dir) } match {
      case Migrator.Status.Tampered(p) => p.foreach(warn)
      case Migrator.Status.Ok(pending) =>
        println(s"  ${pending.size} pending migration(s):")
        pending.foreach { case (n, f, s) => println(f"    $n%04d  $f  (${s.size} statements)") }
        pause("Press Enter to apply them")
        transact { Migrator.apply(pending) }
        blank()
        DeployCheck.verify(c, AppSchema.snapshot, Schema0) match {
          case Right(_) => ok("replayed from empty and landed exactly on the model")
          case Left(d)  => d.foreach(ch => warn(ch.describe))
        }
    }
    blank()
    note("Note what `migrate` did not do: it never derived DDL from the model. It replayed")
    note("committed files, then checked the result. That is why the two are separate verbs.")
    pause()
  }

  private def ch10_tamper(c: Connection, dir: Path): Unit = {
    chapter(10, "Tampering is detected before anything runs")
    val file = Freeze.existing(dir).head._2
    note(s"Editing ${file.getFileName} by hand — changing a table name inside it:")
    Files.writeString(file, Files.readString(file).replace(""""author"""", """"writer""""))
    sql("""- create table "author" ...  ->  create table "writer" ...""")
    blank()

    transact { Migrator.status(dir) } match {
      case Migrator.Status.Ok(_)              => warn("the edit was not noticed — that is a bug")
      case Migrator.Status.Tampered(problems) =>
        problems.foreach(p => rejected(p))
        blank()
        note("Halted before executing a single statement. The file's own header says what it")
        note("should hash to, and it no longer does.")
    }
    pause()
  }

  private def ch11_rollback(c: Connection): Unit = {
    chapter(11, "A failed migration leaves nothing behind")
    note("Postgres has transactional DDL, and the whole batch runs in one `transact` — statements")
    note("and ledger rows together. The migrator does no transaction bookkeeping of its own; it")
    note("takes a `Tx` and writes. Here is a migration whose second statement is nonsense:")
    blank()
    val stmts = List("""create table "halfway" ("id" uuid primary key)""", "this is not sql")
    stmts.foreach(sql)
    pause("Press Enter to apply it")
    try transact { Migrator.apply(List((99, "0099_broken.sql", stmts))) }
    catch { case NonFatal(e) => rejected(oneLine(e)) }
    blank()

    val tables = snapshotOf(c).tables.map(_.name)
    if (tables.contains("halfway")) warn("`halfway` exists — the rollback did not happen")
    else ok("`halfway` does not exist: the first statement was rolled back with the second")
    if (transact { Migrator.applied() }.exists(_.number == 99)) warn("it entered the ledger anyway")
    else ok("nothing entered the migration ledger")
    pause()
  }

  // ── plumbing ─────────────────────────────────────────────────────────────────────────

  /** The tour works in its own Postgres schema, so it cannot disturb anything else. */
  private val Schema0 = "eezo_tour"

  private def connect(): Option[Connection] =
    try Some(DriverManager.getConnection(databaseUrl, databaseUser, databasePassword))
    catch {
      case NonFatal(e) =>
        println()
        warn("Could not start a Postgres container")
        println(s"  ${oneLine(e)}")
        println()
        note("The tour starts its own database through testcontainers, which needs a Docker")
        note("daemon. Check that one is running:")
        blank()
        sql("docker info")
        println()
        None
    }

  private def prepare(c: Connection): Unit = {
    header()
    exec(c, s"""drop schema if exists "$Schema0" cascade""")
    exec(c, s"""create schema "$Schema0"""")
    exec(c, s"""set search_path to "$Schema0"""")
    note(s"Working in schema `$Schema0` on ${Db.url}, so nothing else is touched.")
    note("Everything is dropped again at the end.")
    pause()
  }

  private def finish(c: Connection, dir: Path): Unit = {
    chapter(12, "Done")
    dropAll(c)
    exec(c, s"""drop schema if exists "$Schema0" cascade""")
    ok(s"schema `$Schema0` dropped")
    ok(s"migration files left in $dir if you want to read them")
    blank()
    note("The whole loop, in one sentence: one case class produced the DDL, the codecs, the")
    note("constraints and the FK graph; the differ compared that to a live catalog and to a")
    note("committed file; and the migrator replayed the result and checked it landed.")
    println()
  }

  private def snapshotOf(c: Connection): SchemaSnap = Introspect.snapshot(c, Schema0)

  private def dropAll(c: Connection): Unit = {
    snapshotOf(c).tables.foreach(t => exec(c, s"""drop table if exists "${t.name}" cascade"""))
    exec(c, """drop table if exists "eezo_migrations"""")
  }

  private def attempt[T](c: Connection, what: String, t: Table[T], row: T): Unit = {
    val ps = c.prepareStatement(t.insertSql)
    t.encode(ps, 1, row)
    try { ps.execute(); warn(s"$what was ACCEPTED — that is a bug") }
    catch { case NonFatal(e) => rejected(f"$what%-32s ${oneLine(e)}") }
    finally ps.close()
  }

  private def exec(c: Connection, s: String): Unit = {
    val st = c.createStatement()
    try st.execute(s)
    finally st.close()
  }

  private def flag(ch: Change): String =
    if (ch.destructive) "   [destructive]" else if (ch.risky) "   [risky]" else ""

  private def oneLine(e: Throwable): String = {
    val m     = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    val first = m.linesIterator.find(_.trim.nonEmpty).getOrElse(m).trim
    if (first.length > 96) first.take(93) + "..." else first
  }

  // ── output ───────────────────────────────────────────────────────────────────────────

  private def header(): Unit = {
    println()
    println("  ┌────────────────────────────────────────────────────────────────────────┐")
    println("  │  eezo db — a guided tour                                               │")
    println("  │  one case class, and everything downstream of it                       │")
    println("  └────────────────────────────────────────────────────────────────────────┘")
    println()
  }

  private def chapter(n: Int, title: String): Unit = {
    println()
    println(s"  ── $n. $title " + "─" * math.max(0, 68 - title.length - n.toString.length))
    println()
  }

  private def note(s: String): Unit     = println(s"  $s")
  private def sql(s: String): Unit      = println(s"      ${s.linesIterator.mkString("\n      ")}")
  private def ok(s: String): Unit       = println(s"  ✓ $s")
  private def warn(s: String): Unit     = println(s"  ⚠ $s")
  private def rejected(s: String): Unit = println(s"  ✗ rejected: $s")
  private def blank(): Unit             = println()

  private def pause(prompt: String = "Press Enter to continue"): Unit =
    if (paused) {
      print(s"\n  $prompt ... ")
      StdIn.readLine(): Unit
    } else println()
}
