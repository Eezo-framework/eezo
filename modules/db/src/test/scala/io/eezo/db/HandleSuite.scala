package io.eezo.db

import java.sql.Connection
import io.eezo.db.capability.{EscapedScope, OffThread, TxHandle}
import munit.FunSuite

/** The two runtime checks on the scope handle. Both fire before the connection is touched, so no
  * database is needed to exercise them.
  */
class HandleSuite extends FunSuite {

  private def handle(): TxHandle = new TxHandle(null.asInstanceOf[Connection])

  test("a retired scope names the usual cause") {
    val h = handle()
    h.retire()
    val e = intercept[EscapedScope](h.connection)
    assert(e.getMessage.contains("outlived the block that created it"))
    assert(e.getMessage.contains("lazy Iterator"))
  }

  test("a scope used from another thread names both threads") {
    val h                         = handle()
    var caught: Option[OffThread] = None
    val t                         = Thread
      .ofVirtual()
      .unstarted(() =>
        caught = try { h.connection; None }
        catch { case e: OffThread => Some(e) }
      )
    t.start()
    t.join()
    val e = caught.getOrElse(fail("a connection was handed out on the wrong thread"))
    assert(e.getMessage.contains("not safe for concurrent use"))
    assert(e.getMessage.contains(Thread.currentThread().getName))
  }
}
