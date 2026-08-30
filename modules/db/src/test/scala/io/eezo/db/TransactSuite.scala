package io.eezo.db

import io.eezo.db.engine.ReentrantScope
import io.eezo.db.Scopes.*
import io.eezo.db.support.DbSuite

/** The scopes against a real Postgres: what commits, what rolls back, and what the runtime checks
  * catch that the compiler cannot see.
  */
class TransactSuite extends DbSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    exec("""create table "note" (id int primary key, body text not null)""")
  }

  private def note(id: Int, body: String)(using tx: Tx): Unit = {
    val ps = tx.connection.prepareStatement("""insert into "note" (id, body) values (?, ?)""")
    try {
      ps.setInt(1, id)
      ps.setString(2, body)
      ps.execute(): Unit
    } finally ps.close()
  }

  private def ids()(using s: DB): List[Int] = {
    val st = s.connection.createStatement()
    try {
      val rs = st.executeQuery("""select id from "note" order by id""")
      try Iterator.continually(rs).takeWhile(_.next()).map(_.getInt(1)).toList
      finally rs.close()
    } finally st.close()
  }

  test("a transaction commits its work") {
    transact { note(1, "a") }
    assertEquals(read { ids() }, List(1))
  }

  test("a failed transaction rolls the whole thing back") {
    intercept[RuntimeException] {
      transact {
        note(1, "a")
        note(2, "b")
        throw new RuntimeException("boom")
      }
    }
    assertEquals(read { ids() }, Nil)
  }

  test("reads work inside a transaction, and see its own uncommitted writes") {
    val seen = transact {
      note(1, "a")
      ids() // Tx <: DB, so the read joins the transaction rather than opening a second scope
    }
    assertEquals(seen, List(1))
  }

  test("a helper that opens its own transaction is caught at runtime") {
    // nothing in `audit`'s signature says it opens a scope, so neither summonFrom nor capture
    // checking can see this. DESIGN §8.4 is why it fails anyway.
    def audit(): Unit = transact { note(99, "audit") }

    val e = intercept[ReentrantScope] {
      transact {
        note(1, "a")
        audit()
      }
    }
    assert(e.getMessage.contains("a transact scope is already open on this thread"))
    assertEquals(read { ids() }, Nil, "the outer transaction rolled back")
  }

  test("detached commits even though the caller rolled back") {
    intercept[RuntimeException] {
      transact {
        note(1, "rolled back")
        detached { note(2, "independent") }
        throw new RuntimeException("boom")
      }
    }
    assertEquals(read { ids() }, List(2))
  }

  test("attempt rolls back its sub-unit and the transaction carries on") {
    transact {
      note(1, "a")
      val failed = attempt { note(1, "duplicate key") }
      assert(failed.isLeft, "the duplicate insert should have failed")
      note(2, "b") // without a savepoint Postgres would refuse: the transaction would be aborted
    }
    assertEquals(read { ids() }, List(1, 2))
  }
}
