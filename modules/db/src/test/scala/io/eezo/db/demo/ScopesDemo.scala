package io.eezo.db.demo

import io.eezo.db.*
import io.eezo.db.capability.OffThread
import io.eezo.db.engine.{Installed, ReentrantScope}
import io.eezo.db.Scopes.*
import io.eezo.db.support.Pg
import java.sql.SQLException

/** A runnable walk through phase 1. Not a test: it prints what happens so the behaviour and the
  * messages can be read.
  *
  * sbt "db/Test/runMain io.eezo.db.demo.scopes"
  *
  * It starts its own Postgres through testcontainers, so no local database is needed.
  *
  * It lives under the db module's test sources because installing a `Database` and reaching a
  * `Connection` are both `private[eezo]`, and `EezoApp` — the public way in — arrives in phase 5.
  * When it does, this moves to `modules/example`.
  */
object ScopesDemo {

  private val schema = "demo"

  private def step(n: String)(body: => Unit): Unit = {
    println(s"\n── $n ${"─" * math.max(0, 60 - n.length)}")
    try body
    catch {
      case e: ReentrantScope => println(indent(e.getMessage))
      case e: OffThread      => println(indent(e.getMessage))
      case e: Throwable      => println(s"   ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }

  private def indent(s: String): String = s.linesIterator.map("   " + _).mkString("\n")

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

  private def rows(): String = read { ids() }.mkString("[", ", ", "]")

  /** Opens its own scope, and says nothing about that in its signature — which is exactly why
    * neither `summonFrom` nor capture checking can see it, and why `Scope` exists.
    */
  private def audit(id: Int): Unit = transact { note(id, "audit") }

  def main(args: Array[String]): Unit = {
    val setup = Pg.connect()
    val st    = setup.createStatement()
    st.execute(s"""drop schema if exists "$schema" cascade"""): Unit
    st.execute(s"""create schema "$schema""""): Unit
    st.execute(s"""set search_path to "$schema""""): Unit
    st.execute("""create table "note" (id int primary key, body text not null)"""): Unit
    st.close()
    setup.close()

    Installed.install(Pg.database(schema))

    step("1. a transaction commits") {
      transact { note(1, "committed") }
      println(s"   rows: ${rows()}")
    }

    step("2. a failed transaction rolls the whole thing back") {
      try
        transact {
          note(2, "written")
          note(3, "also written")
          throw new RuntimeException("something went wrong")
        }
      catch { case _: RuntimeException => println("   the block threw") }
      println(s"   rows: ${rows()}  ← neither 2 nor 3 survived")
    }

    step("3. reads work inside a transaction and see its own writes") {
      val seen = transact { note(4, "uncommitted"); ids() }
      println(s"   inside the transaction: ${seen.mkString("[", ", ", "]")}")
      println(s"   after it committed:     ${rows()}")
    }

    step("4. a helper that opens its own transaction — caught at runtime") {
      // nothing in `audit`'s signature says it opens a scope, so neither the compiler nor capture
      // checking can see this one
      transact {
        note(5, "never committed")
        audit(98)
      }
    }
    println(s"   rows: ${rows()}  ← the outer transaction rolled back")

    step("5. a task forked inside a transaction, opening its own — a different message") {
      transact {
        note(6, "committed anyway — see below")
        val t = Thread.ofVirtual().unstarted(() => audit(97))
        t.setUncaughtExceptionHandler((_, e) => println(indent(e.getMessage)))
        t.start()
        t.join()
      }
    }
    println(s"   rows: ${rows()}  ← 6 IS here, and that is correct")
    println("""   The fork's ReentrantScope was thrown on the FORK's thread. `join()` waits, it does
   not rethrow, so the outer block completed normally and committed. Compare step 4, where
   `audit` ran on this thread and the exception rolled the outer transaction back.
   The handler above exists only to make the failure visible: real fire-and-forget code has
   none, and then nothing is printed at all while 6 still commits (DESIGN §8.6).""")

    step("6. a forked task using the CALLER's transaction — the other runtime check") {
      // `example` and this file do not enable capture checking, so this compiles. In a
      // capture-checked build it would not: see research/capture-checking.md §6.3 for why that
      // guarantee reaches consenting builds only, and why these runtime checks exist for everyone.
      transact { (tx: Tx) ?=>
        val t = Thread.ofVirtual().unstarted(() => note(96, "off thread")(using tx))
        t.setUncaughtExceptionHandler((_, e) => println(indent(e.getMessage)))
        t.start()
        t.join()
      }
    }
    println(s"   rows: ${rows()}  ← 96 is absent: the insert never ran, the handle refused first")

    step("7. detached commits even though the caller rolled back") {
      try
        transact {
          note(7, "rolled back")
          detached { note(8, "independent") }
          throw new RuntimeException("the caller fails after the detached work")
        }
      catch { case _: RuntimeException => println("   the caller threw") }
      println(s"   rows: ${rows()}  ← 8 survived, 7 did not")
    }

    step("8. attempt rolls back a sub-unit and the transaction carries on") {
      transact {
        note(9, "kept")
        val failed = attempt[SQLException] { note(9, "duplicate key") }
        println(s"   the sub-unit failed: ${failed.isLeft}")
        note(10, "still writable") // without a savepoint Postgres would refuse this
      }
      println(s"   rows: ${rows()}")
    }

    println("\n── what the compiler refused before any of this ran ──────────")
    println(
      """   transact { transact { … } }        already inside a transaction: remove this `transact`
   read { transact { … } }            cannot open a transaction inside a `read` scope
   read { insert(…) }                 a `DB` is not enough: writes do not work inside a read
   val t: Tx = transact { … }         a scope cannot outlive the block that created it"""
    )

    Installed.uninstall()
  }
}

@main def scopes(): Unit = ScopesDemo.main(Array.empty)
