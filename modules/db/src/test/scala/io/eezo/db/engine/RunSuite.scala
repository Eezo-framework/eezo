package io.eezo.db.engine

import io.eezo.db.Scopes.attempt
import munit.FunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException}

/** pgjdbc cannot be made to fail on demand while it is open, so a stub stands in for the connection
  * HikariCP would hand out, and the release is counted rather than inferred from a pool that may
  * evict the connection.
  */
class RunSuite extends FunSuite {

  /** Any call can be made to fail by name. A failing rollback makes an unexpected rollback visible
    * twice: in `calls` and in the `getSuppressed` of the exception the caller sees.
    */
  private final class Stub(fail: Map[String, Throwable]) {
    @volatile var calls: Vector[String] = Vector.empty

    private val handler: InvocationHandler = new InvocationHandler {
      def invoke(proxy: Object, m: Method, args: Array[Object] | Null): Object = {
        val shown = Option(args).fold("")(_.mkString(","))
        val call  = s"${m.getName}($shown)"
        calls = calls :+ call
        fail.get(call).orElse(fail.get(m.getName)) match {
          case Some(e) => throw e
          case None    => null
        }
      }
    }

    val connection: Connection =
      Proxy
        .newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), handler)
        .asInstanceOf[Connection]
  }

  private final class Released {
    @volatile var times: Int        = 0
    val release: Connection -> Unit = _ => times += 1
  }

  test("a failed setAutoCommit reaches the caller as is, and the connection is still released") {
    val boom = new SQLException("connection is closed", "08003")
    val stub = new Stub(
      Map(
        "setAutoCommit(false)" -> boom,
        "rollback"             -> new SQLException("Cannot rollback when autoCommit is enabled")
      )
    )
    val released      = new Released
    @volatile var ran = false
    val e             = intercept[SQLException](
      Run.transacting(stub.connection, released.release) { ran = true }
    )
    assert(e.eq(boom), s"expected the setAutoCommit failure itself, got $e")
    assertEquals(released.times, 1)
    assert(!ran, "the body ran on a connection that never left auto commit")
    assert(!stub.calls.exists(c => c.startsWith("commit") || c.startsWith("rollback")), stub.calls)
  }

  test("a body that returns commits once, never rolls back, and releases once") {
    val stub     = new Stub(Map.empty)
    val released = new Released
    assertEquals(Run.transacting(stub.connection, released.release)(42), 42)
    assertEquals(stub.calls, Vector("setAutoCommit(false)", "commit()"))
    assertEquals(released.times, 1)
  }

  test("a body that throws rolls back, reaches the caller as is, and releases once") {
    val boom     = new RuntimeException("boom")
    val stub     = new Stub(Map.empty)
    val released = new Released
    val e        = intercept[RuntimeException](
      Run.transacting(stub.connection, released.release)(throw boom)
    )
    assert(e.eq(boom), s"expected the body's own failure, got $e")
    assertEquals(stub.calls, Vector("setAutoCommit(false)", "rollback()"))
    assertEquals(released.times, 1)
  }

  test("a commit that fails rolls back, reaches the caller as is, and releases once") {
    val boom     = new SQLException("could not serialize access", "40001")
    val stub     = new Stub(Map("commit" -> boom))
    val released = new Released
    val e        = intercept[SQLException](Run.transacting(stub.connection, released.release)(()))
    assert(e.eq(boom), s"expected the commit failure itself, got $e")
    assertEquals(stub.calls, Vector("setAutoCommit(false)", "commit()", "rollback()"))
    assertEquals(released.times, 1)
  }

  test("a rollback that fails after the body throws rides along on the body's failure") {
    val boom     = new RuntimeException("boom")
    val refused  = new SQLException("connection reset", "08006")
    val stub     = new Stub(Map("rollback" -> refused))
    val released = new Released
    val e        = intercept[RuntimeException](
      Run.transacting(stub.connection, released.release)(throw boom)
    )
    assert(e.eq(boom), s"expected the body's own failure, got $e")
    assertEquals(e.getSuppressed.toList, List(refused))
    assertEquals(released.times, 1)
  }

  test("a rollback that fails after the commit fails rides along on the commit's failure") {
    val boom     = new SQLException("could not serialize access", "40001")
    val refused  = new SQLException("connection reset", "08006")
    val stub     = new Stub(Map("commit" -> boom, "rollback" -> refused))
    val released = new Released
    val e        = intercept[SQLException](Run.transacting(stub.connection, released.release)(()))
    assert(e.eq(boom), s"expected the commit failure itself, got $e")
    assertEquals(e.getSuppressed.toList, List(refused))
    assertEquals(released.times, 1)
  }

  test("a rollback that throws the commit's own failure leaves it as is") {
    val boom     = new SQLException("connection reset", "08006")
    val stub     = new Stub(Map("commit" -> boom, "rollback" -> boom))
    val released = new Released
    val e        = intercept[SQLException](Run.transacting(stub.connection, released.release)(()))
    assert(e.eq(boom), s"expected the commit failure itself, got $e")
    assertEquals(e.getSuppressed.toList, Nil)
    assertEquals(released.times, 1)
  }

  test("a rollback that fails after an exit that takes no suppressed exception is thrown instead") {
    val exit     = new RuntimeException("exit", null, false, false) {}
    val refused  = new SQLException("connection reset", "08006")
    val stub     = new Stub(Map("rollback" -> refused))
    val released = new Released
    val e        = intercept[SQLException](
      Run.transacting(stub.connection, released.release)(throw exit)
    )
    assert(e.eq(refused), s"expected the rollback failure, got $e")
    assertEquals(released.times, 1)
  }

  test("a savepoint rollback that throws the body's own failure leaves it as is") {
    val boom     = new SQLException("connection reset", "08006")
    val stub     = new Stub(Map("rollback(null)" -> boom))
    val released = new Released
    val e        = intercept[SQLException](
      Run.transacting(stub.connection, released.release)(
        attempt[SQLException](throw boom)
      )
    )
    assert(e.eq(boom), s"expected the body's own failure, got $e")
    assertEquals(e.getSuppressed.toList, Nil)
    assertEquals(
      stub.calls,
      Vector("setAutoCommit(false)", "setSavepoint()", "rollback(null)", "rollback()")
    )
    assertEquals(released.times, 1)
  }

  test("a read leaves auto commit alone and releases once, whether its body returns or throws") {
    val stub     = new Stub(Map.empty)
    val released = new Released
    assertEquals(Run.reading(stub.connection, released.release)("a"), "a")
    val boom = new RuntimeException("boom")
    val e = intercept[RuntimeException](Run.reading(stub.connection, released.release)(throw boom))
    assert(e.eq(boom), s"expected the body's own failure, got $e")
    assertEquals(stub.calls, Vector.empty)
    assertEquals(released.times, 2)
  }
}
