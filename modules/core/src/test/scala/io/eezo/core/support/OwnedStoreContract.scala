package io.eezo.core.support

import io.eezo.core.{Id, Store}

trait OwnedStoreContract[A, V] { self: munit.FunSuite =>

  def emptyWorld(): (Store[A], V => Store[A])

  def keyAt(nth: Int): Id[A] =
    Id.apply[A](java.util.UUID.fromString(f"$nth%08x-0000-4000-8000-000000000000"))

  def rowOf(key: Id[A], owner: V, label: String): A

  def labelOf(row: A): String

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

  test("every test starts from a world with no rows, whatever the tests before it wrote") {
    val (plain, by) = emptyWorld()

    assertEquals(plain.all(), Seq.empty[A])
    assertEquals(by(mine).all(), Seq.empty[A])
    assertEquals(by(theirs).all(), Seq.empty[A])
  }
}
