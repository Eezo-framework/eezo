package io.eezo.http

import io.eezo.core.{Id, OwnedStore, OwnerOf, Store}
import io.eezo.core.support.OwnedStoreContract

/** The in-memory half of `core`'s owner seam, pinned against the contract `db`'s half is pinned
  * against.
  *
  * Almost nothing here is in-memory specific, and that is the point: every assertion in the
  * mixed-in contract is one the JDBC half answers identically, and a suite of its own would be
  * where the two quietly drift apart.
  */
class OwnedInMemoryStoreSuite
    extends munit.FunSuite
    with OwnedStoreContract[OwnedInMemoryStoreSuite.Widget, String] {

  import OwnedInMemoryStoreSuite.Widget

  private def ownerOf: OwnerOf[Widget, String] = OwnerOf("owner", _.owner)

  def emptyWorld(): (Store[Widget], String => Store[Widget]) = {
    val scoped = InMemoryStore.scoped(ownerOf)
    (scoped, owner => scoped.by(owner))
  }

  def rowOf(key: Id[Widget], owner: String, label: String): Widget = Widget(key, owner, label)

  def labelOf(row: Widget): String = row.name

  def mine: String   = "ada"
  def theirs: String = "grace"

  test("owned takes no given, and by shares one set of rows across every call on it") {
    val owned: OwnedStore[Widget, String] = InMemoryStore.owned(ownerOf)
    owned.by("grace").insert(keyAt(1), rowOf(keyAt(1), "grace", "hidden"))

    assertEquals(owned.by("ada").all(), Seq.empty[Widget])
    assertEquals(owned.by("grace").all().map(labelOf), Seq("hidden"))
  }

  test("owned's rows belong to that one call alone, unreachable from a store built apart from it") {
    val separate = InMemoryStore[Widget]()
    val owned    = InMemoryStore.owned(ownerOf)
    owned.by("ada").insert(keyAt(1), rowOf(keyAt(1), "ada", "invisible to separate"))

    assertEquals(separate.all(), Seq.empty[Widget])
  }

  test("a scoped store is a Store of everything, which is what an uncovered action reads") {
    val scoped = InMemoryStore.scoped(ownerOf)
    scoped.insert(keyAt(1), Widget(keyAt(1), "ada", "first"))
    scoped.insert(keyAt(2), Widget(keyAt(2), "grace", "second"))

    assertEquals(scoped.all().map(_.name), Seq("first", "second"))
    assertEquals(scoped.by("ada").all().map(_.name), Seq("first"))
  }
}

object OwnedInMemoryStoreSuite {
  case class Widget(id: Id[Widget], owner: String, name: String)
}
