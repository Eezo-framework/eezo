package io.eezo.core.support

import io.eezo.core.{Id, Store}

/** The five assertions both halves of [[io.eezo.core.OwnedStore]] have to satisfy, written once.
  *
  * It lives in `core`'s test sources, and both edges reach it through their `test->test` dependency
  * on `core`, for the reason the seam itself lives in `core`: `db`'s half and `http`'s half are
  * siblings that never see each other, and a behaviour present in one and absent in the other is a
  * difference nobody notices until a browser shows it. Mixed into a suite, this turns that
  * difference into a failing test in the module that has it.
  *
  * Every hook is a `def` rather than a `val`. The tests below register while this trait's body
  * runs, which is before a mixing suite's own fields are initialised, so a `val` hook would be read
  * as `null` by whichever of them ran first.
  *
  * @tparam A
  *   the model, which each half brings its own of: `db` needs one carrying a `Table`, and `http`
  *   needs one carrying nothing at all.
  * @tparam V
  *   the owner's type, a `String` on both sides so that neither half has to invent a second user
  *   model to be scoped by.
  */
trait OwnedStoreContract[A, V] { self: munit.FunSuite =>

  /** An empty world: the unnarrowed store over the rows, and the narrowing of the owner aware half
    * over those same rows. Called once per test, so no test sees another's rows.
    *
    * A function rather than the [[io.eezo.core.OwnedStore]] itself, because a half whose `by` is
    * reached through an adapter is still the half under test, and each side's own suite is where
    * the type of what its factory answers belongs.
    */
  def emptyWorld(): (Store[A], V => Store[A])

  /** A key whose text sorts where the caller says, so the order assertion states an expected order
    * rather than discovering one.
    */
  def keyAt(nth: Int): Id[A] =
    Id.apply[A](java.util.UUID.fromString(f"$nth%08x-0000-4000-8000-000000000000"))

  def rowOf(key: Id[A], owner: V, label: String): A

  def labelOf(row: A): String

  /** The owner whose rows the tests narrow to, and the one whose rows have to be invisible. */
  def mine: V
  def theirs: V

  test("all() through a narrowed store is that owner's rows alone, in primary key order") {
    val (plain, by) = emptyWorld()
    plain.insert(keyAt(3), rowOf(keyAt(3), mine, "third"))
    plain.insert(keyAt(2), rowOf(keyAt(2), theirs, "hidden"))
    plain.insert(keyAt(1), rowOf(keyAt(1), mine, "first"))

    assertEquals(by(mine).all().map(labelOf), Seq("first", "third"))
    assertEquals(by(theirs).all().map(labelOf), Seq("hidden"))
  }

  test("find through a narrowed store answers nothing for a row somebody else owns") {
    val (plain, by) = emptyWorld()
    plain.insert(keyAt(1), rowOf(keyAt(1), theirs, "hidden"))

    assertEquals(by(mine).find(keyAt(1)), None)
    assertEquals(by(theirs).find(keyAt(1)).map(labelOf), Some("hidden"))
  }

  test("update through a narrowed store refuses a foreign row and leaves it as it was") {
    val (plain, by) = emptyWorld()
    plain.insert(keyAt(1), rowOf(keyAt(1), theirs, "hidden"))

    assertEquals(by(mine).update(keyAt(1), rowOf(keyAt(1), mine, "stolen")), false)
    assertEquals(plain.find(keyAt(1)).map(labelOf), Some("hidden"))
  }

  test("update through a narrowed store writes the owner's own row") {
    val (plain, by) = emptyWorld()
    plain.insert(keyAt(1), rowOf(keyAt(1), mine, "first"))

    assertEquals(by(mine).update(keyAt(1), rowOf(keyAt(1), mine, "renamed")), true)
    assertEquals(plain.find(keyAt(1)).map(labelOf), Some("renamed"))
  }

  test("delete through a narrowed store refuses a foreign row and the row survives") {
    val (plain, by) = emptyWorld()
    plain.insert(keyAt(1), rowOf(keyAt(1), theirs, "hidden"))

    assertEquals(by(mine).delete(keyAt(1)), false)
    assertEquals(plain.find(keyAt(1)).map(labelOf), Some("hidden"))
    assertEquals(by(theirs).delete(keyAt(1)), true)
    assertEquals(plain.find(keyAt(1)), None)
  }

  test("insert through a narrowed store writes the row exactly as it was given") {
    val (plain, by) = emptyWorld()
    val written     = rowOf(keyAt(1), theirs, "as given")
    by(mine).insert(keyAt(1), written)

    assertEquals(plain.find(keyAt(1)), Some(written))
  }
}
