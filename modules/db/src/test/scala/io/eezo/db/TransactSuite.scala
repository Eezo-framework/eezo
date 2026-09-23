package io.eezo.db

import io.eezo.db.engine.ReentrantScope
import io.eezo.db.Scopes.*
import io.eezo.db.support.DbSuite
import java.sql.SQLException
import java.util.concurrent.TimeoutException
import org.postgresql.util.PSQLException

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
      val failed = attempt[SQLException] { note(1, "duplicate key") }
      assert(failed.isLeft, "the duplicate insert should have failed")
      note(2, "b") // without a savepoint Postgres would refuse: the transaction would be aborted
    }
    assertEquals(read { ids() }, List(1, 2))
  }

  test("attempt returns the failure its caller named, and the outer transaction commits alone") {
    val failed = transact {
      note(1, "a")
      val failed = attempt[SQLException] {
        note(5, "sub unit")
        note(1, "duplicate key")
      }
      note(2, "b")
      failed
    }
    failed match {
      case Left(e) =>
        assert(e.isInstanceOf[PSQLException], s"a subclass of the named type is owned: $e")
      case other => fail(s"the duplicate insert should have been owned by attempt: $other")
    }
    assertEquals(read { ids() }, List(1, 2))
  }

  test("attempt rethrows a failure its caller did not name, and the transaction rolls back") {
    val boom   = new IllegalStateException("a defect, not a database refusal")
    val thrown = intercept[IllegalStateException] {
      transact {
        note(1, "outer")
        attempt[SQLException] {
          note(2, "sub unit")
          throw boom
        }: Unit
      }
    }
    assert(thrown eq boom, s"the defect should reach the caller unchanged: $thrown")
    assertEquals(read { ids() }, Nil)
  }

  test("attempt owns each member of a named union and nothing more") {
    // A union has no single class to test against: judged by its common superclass, Exception,
    // the savepoint would own the defect below as well.
    val timeout = new TimeoutException("a service did not answer")
    val owned   = transact {
      attempt[SQLException | TimeoutException] { throw timeout }
    }
    assertEquals(owned, Left(timeout))
    val boom   = new IllegalStateException("a defect, in neither member")
    val thrown = intercept[IllegalStateException] {
      transact {
        attempt[SQLException | TimeoutException] { throw boom }: Unit
      }
    }
    assert(thrown eq boom, s"the defect should reach the caller unchanged: $thrown")
  }

  test("attempt rolls its savepoint back before it rethrows an unnamed failure") {
    // Catching the rethrow inside the transaction is what makes the order visible: if the savepoint
    // were still open, the sub unit's row would commit with the outer ones.
    transact {
      note(1, "outer")
      try
        attempt[SQLException] {
          note(2, "sub unit")
          throw new IllegalStateException("unnamed")
        }: Unit
      catch { case _: IllegalStateException => () }
      note(3, "outer again")
    }
    assertEquals(read { ids() }, List(1, 3))
  }

  test("attempt returns the body's value on success and its work commits") {
    val result = transact {
      note(1, "outer")
      attempt[SQLException] {
        note(2, "sub unit")
        42
      }
    }
    assertEquals(result, Right(42))
    assertEquals(read { ids() }, List(1, 2))
  }

  test("a savepoint that cannot roll back keeps the body's failure and never owns it") {
    // Ending the transaction from inside the body removes the savepoint, so rolling back to it
    // fails. That rollback failure must not replace the body's own, and must not be returned as a
    // Left either: the caller would carry on in a transaction that is not where it thinks.
    val boom   = new SQLException("the sub unit's own failure")
    val thrown = intercept[SQLException] {
      transact {
        attempt[SQLException] {
          val st = summon[Tx].connection.createStatement()
          try st.execute("rollback"): Unit
          finally st.close()
          throw boom
        }: Unit
      }
    }
    assert(thrown eq boom, s"the body's failure should reach the caller: $thrown")
    assert(
      thrown.getSuppressed.exists(_.isInstanceOf[SQLException]),
      "the rollback failure is kept"
    )
  }
}
