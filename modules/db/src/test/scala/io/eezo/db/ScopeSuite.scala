package io.eezo.db

import io.eezo.db.engine.{ReentrantScope, Scope, ScopeKind}
import munit.FunSuite

/** The reentry check, on its own. No database: `Scope` is the marker logic and nothing else, and
  * every case here is about what the marker does rather than about SQL.
  */
class ScopeSuite extends FunSuite {

  private def inScope[A](body: => A): A = Scope.enter(ScopeKind.Write)(body)

  test("a scope runs its body and releases") {
    assertEquals(inScope(41 + 1), 42)
    assertEquals(inScope("again"), "again") // the marker was restored, not left behind
  }

  test("a second scope on the same thread is rejected, naming both frames") {
    val e = intercept[ReentrantScope](inScope(inScope(())))
    assert(e.getMessage.contains("a transact scope is already open on this thread"))
    assert(e.getMessage.contains("outer scope: transact"))
    assert(e.getMessage.contains("def audit(e: String)(using Tx)"), "should say how to fix it")
  }

  test("a read inside a transaction is rejected too") {
    val e = intercept[ReentrantScope](inScope(Scope.enter(ScopeKind.Read)(())))
    assert(e.getMessage.contains("a transact scope is already open"))
    assert(e.getMessage.contains("this read would open a second one"))
  }

  test("a forked thread is told it cannot join, not to take a Tx") {
    var caught: Option[ReentrantScope] = None
    inScope {
      val t = Thread
        .ofVirtual()
        .unstarted(() =>
          caught = try { inScope(()); None }
          catch { case e: ReentrantScope => Some(e) }
        )
      t.start()
      t.join()
    }
    val e = caught.getOrElse(fail("the fork was allowed to open its own transaction"))
    assert(e.getMessage.contains("forked inside a transact scope"))
    assert(e.getMessage.contains("cannot join the scope it was forked from"))
    assert(e.getMessage.contains("detached"), "should point at the escape hatch")
  }

  test("an inherited marker is rejected even after the parent scope closed") {
    // deliberately strict: whether the fork starts before or after the parent commits is a race,
    // and a check that fires intermittently is worse than one that is always strict
    var t: Thread = null
    inScope {
      t = Thread.ofVirtual().unstarted(() => intercept[ReentrantScope](inScope(())): Unit)
    }
    t.start()
    t.join()
  }

  test("detached clears the marker for its body, and restores it after") {
    inScope {
      Scope.detached(inScope("independent")): Unit
      intercept[ReentrantScope](inScope(())) // the outer marker is back
    }
  }

  test("a thread that ran a FAILED scope carries no marker into the next one") {
    // the failure mode that would make this check worse than not having it: a pooled worker
    // inheriting a stale marker from the request before it
    intercept[RuntimeException](inScope(throw new RuntimeException("boom")))
    assertEquals(inScope("clean"), "clean")
  }
}
