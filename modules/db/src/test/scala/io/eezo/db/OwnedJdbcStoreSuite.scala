package io.eezo.db

import io.eezo.core.{Id, OwnedStore, OwnerOf, Store}
import io.eezo.core.support.OwnedStoreContract
import io.eezo.db.support.{DbSuite, Memo, Memos}

/** The JDBC half of `core`'s owner seam, against a real Postgres and against the same contract the
  * in-memory half answers.
  *
  * Every assertion below the fixtures comes from that shared contract, which is the point: the
  * derived seven cannot tell the two halves apart, so a difference between them is a defect nobody
  * sees until an application that had one store is given the other.
  */
class OwnedJdbcStoreSuite extends DbSuite with OwnedStoreContract[Memo, String] {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    create(Memos)
  }

  private def ownerOf: OwnerOf[Memo, String] = OwnerOf("owner", _.owner)

  def emptyWorld(): (Store[Memo], String => Store[Memo]) = {
    val owned: OwnedStore[Memo, String] = JdbcStore.owned(ownerOf)
    (JdbcStore[Memo](), owner => owned.by(owner))
  }

  def rowOf(key: Id[Memo], owner: String, label: String): Memo = Memo(key, owner, label)

  def labelOf(row: Memo): String = row.text

  def mine: String   = "ada"
  def theirs: String = "grace"

  test("the owner is bound through its own Column, so a value is escaped and never interpolated") {
    val store = JdbcStore[Memo]()
    store.insert(keyAt(1), Memo(keyAt(1), "ada' or '1'='1", "hers"))
    store.insert(keyAt(2), Memo(keyAt(2), "grace", "his"))

    val owned = JdbcStore.owned(ownerOf)
    assertEquals(owned.by("ada' or '1'='1").all().map(_.text), Seq("hers"))
    assertEquals(owned.by("' or '1'='1").all(), Seq.empty[Memo])
  }
}
