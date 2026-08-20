package io.eezo.db

import io.eezo.db.schema.*
import io.eezo.db.support.*

/** `sync`: reconcile a dev database against the model directly, no migration involved.
  * Was `Step5`, which printed the diff and the SQL and applied it behind a flag. */
class SyncSuite extends PgSuite {

  test("an empty database diffs to the whole model, and applying lands in sync") {
    val d = Differ.diff(live(), Library.snapshot)
    assert(d.nonEmpty)
    exec(Ddl.render(d)*)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("drift is rendered as SQL that repairs it") {
    create(Library)
    exec(
      """alter table "book" drop column "isbn"""",
      """alter table "book" add column "stray" integer""",
      """create index "by_hand" on "author" ("name")"""
    )
    val d = Differ.diff(live(), Library.snapshot)
    assertEquals(
      d.map(_.describe).sorted,
      List("+ book.isbn text null", "- book.stray", "- index by_hand on author")
    )
    exec(Ddl.render(d)*)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("sync is idempotent: a second run has nothing to do") {
    exec(Ddl.render(Differ.diff(live(), Library.snapshot))*)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
    assertEquals(Differ.diff(live(), Library.snapshot), Nil)
  }

  test("changes that lose data are flagged so sync can refuse them unattended") {
    // DESIGN §3.6: destructive loses data and always succeeds; risky loses nothing and may
    // fail against existing rows. They need different handling, so they are separate flags.
    create(Library)
    exec("""alter table "book" add column "stray" integer""")
    val d = Differ.diff(live(), Library.snapshot)
    assertEquals(d.map(_.describe), List("- book.stray"))
    assert(d.head.destructive)
    assert(!d.head.risky)
  }

  test("a narrowing change is risky but not destructive") {
    create(Library)
    exec("""alter table "book" alter column "format" drop not null""")
    val d = Differ.diff(live(), Library.snapshot)
    assertEquals(d.map(_.describe), List("~ book.format set not null"))
    assert(d.head.risky)
    assert(!d.head.destructive)
  }
}
