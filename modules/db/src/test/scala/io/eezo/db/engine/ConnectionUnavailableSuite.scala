package io.eezo.db.engine

import io.eezo.db.support.Refusing

import munit.FunSuite

import java.sql.SQLTransientConnectionException
import java.time.Duration

/** A borrow that times out, told in eezo's words. No Postgres: a local port nobody listens on
  * refuses every connect at once, so what each test waits on is the pool's timeout alone.
  */
class ConnectionUnavailableSuite extends FunSuite {

  private val timeout = Duration.ofMillis(250)

  private def borrowing[A](pool: Pool)(f: Pool => A): A =
    try f(pool)
    finally pool.close()

  private def refused(
      url: String = Refusing.jdbcUrl(),
      password: String = "nothing",
      size: Int = 1
  ): ConnectionUnavailable =
    borrowing(new Pool(url, "nobody", password, _ => (), size, timeout)) { pool =>
      intercept[ConnectionUnavailable](pool.acquire())
    }

  test("a borrow that times out is ConnectionUnavailable, with HikariCP's exception as the cause") {
    val e = refused()
    assert(e.getCause.isInstanceOf[SQLTransientConnectionException], String.valueOf(e.getCause))
  }

  test("the message names the pool size, the wait, the url and the driver's failure, on one line") {
    val url = Refusing.jdbcUrl()
    val e   = refused(url = url, size = 3)
    val m   = e.getMessage
    assert(!m.contains("\n"), m)
    assert(m.contains("pool of size 3"), m)
    assert(m.contains("250 ms"), m)
    assert(m.contains(url), m)
    assert(m.contains("Connection to 127.0.0.1"), m)
    assert(m.contains("refused"), m)
  }

  test("a password given beside the url never reaches the message") {
    val m = refused(password = "hunter2beside").getMessage
    assert(!m.contains("hunter2beside"), m)
  }

  test("a password in the url's query never reaches the message, first or later, encoded or not") {
    val base = Refusing.jdbcUrl()
    List(
      s"$base?password=hunter2first&user=nobody",
      s"$base?user=nobody&password=hunter2later",
      s"$base?user=nobody&password=hunter2%40encoded&sslmode=disable",
      s"$base?user=nobody&sslpassword=hunter2ssl",
      // pgjdbc splits the query on `&` alone, so a `#` is part of the password, not a fragment
      s"$base?user=nobody&password=hunter2#hunter2tail&sslmode=disable"
    ).foreach { url =>
      val m = refused(url = url).getMessage
      assert(!m.contains("hunter2"), m)
      assert(m.contains(base), s"the rest of the url should still be there: $m")
    }
  }

  test("a password in the url's userinfo never reaches the message") {
    val url = "jdbc:postgresql://nobody:hunter2info@127.0.0.1:1/none?password=hunter2query"
    val r   = Pool.redacted(url)
    assert(!r.contains("hunter2"), r)
    assert(r.contains("nobody"), r)
    assert(r.contains("127.0.0.1:1/none"), r)
  }

  test("a detached borrow that times out on an outage carries no detached hint") {
    // the outer scope is set aside, but the database is down and the pool holds nothing: telling
    // the reader the pool was full and the thread waited on itself would send them after a
    // deadlock that is not there. Whether the hint does fire on a full pool needs a real one, so
    // PoolSuite covers it.
    borrowing(new Pool(Refusing.jdbcUrl(), "nobody", "nothing", _ => (), 1, timeout)) { pool =>
      val e = intercept[ConnectionUnavailable](
        Scope.enter(ScopeKind.Read)(
          Scope.detached(Scope.enter(ScopeKind.Write)(pool.release(pool.acquire())))
        )
      )
      assert(e.getMessage.contains("refused"), e.getMessage)
      assert(!e.getMessage.contains("detached"), e.getMessage)
    }
  }

  /** A host that completes the handshake and then says nothing, as a firewalled or wedged database
    * does: HikariCP's first connect is still stuck in the driver when the borrow gives up, so no
    * failure is on record yet although the pool holds nothing.
    */
  private def silent[A](size: Int)(f: Pool => A): A = {
    val host = new java.net.ServerSocket(0)
    try
      borrowing(
        new Pool(
          s"jdbc:postgresql://127.0.0.1:${host.getLocalPort}/none",
          "nobody",
          "nothing",
          _ => (),
          size,
          timeout
        )
      )(f)
    finally host.close()
  }

  test("a first borrow from a host that never answers does not claim the pool was full") {
    silent(size = 10) { pool =>
      val m = intercept[ConnectionUnavailable](pool.acquire()).getMessage
      assert(!m.contains("in use"), m)
      assert(m.contains("still pending"), m)
    }
  }

  test("a detached borrow from a host that never answers carries no detached hint") {
    // the outer scope is set aside and no failure is on record yet, but the pool holds nothing: it
    // was never full, so the thread did not wait on itself
    silent(size = 2) { pool =>
      val e = intercept[ConnectionUnavailable](
        Scope.enter(ScopeKind.Read)(
          Scope.detached(Scope.enter(ScopeKind.Write)(pool.release(pool.acquire())))
        )
      )
      assert(!e.getMessage.contains("detached"), e.getMessage)
    }
  }

  test("an acquire timeout HikariCP would wait forever on, or refuse, is refused in eezo's words") {
    // HikariCP reads 0 as waiting forever, and a sub millisecond Duration truncates to 0, so an
    // override meaning "fail fast" would bring back the hang the timeout exists to prevent
    List(Duration.ZERO, Duration.ofNanos(500_000), Duration.ofMillis(249), Duration.ofMillis(-1))
      .foreach { t =>
        val e = intercept[IllegalArgumentException](
          new Pool(Refusing.jdbcUrl(), "nobody", "nothing", _ => (), 1, t).close()
        )
        assert(e.getMessage.contains("databaseAcquireTimeout"), e.getMessage)
        assert(e.getMessage.contains("250 ms"), e.getMessage)
      }
  }

  test("DbInit's pool size and acquire timeout are the ones the pool borrows under") {
    val url = Refusing.jdbcUrl()
    class Config extends io.eezo.db.DbInit {
      override def databaseUrl: String              = url
      override def databasePoolSize: Int            = 3
      override def databaseAcquireTimeout: Duration = Duration.ofMillis(300)
      def built: Database                           = database
    }
    val d = new Config().built
    try {
      val e = intercept[ConnectionUnavailable](d.pool.acquire())
      assert(e.getMessage.contains("pool of size 3"), e.getMessage)
      assert(e.getMessage.contains("within 300 ms"), e.getMessage)
    } finally d.close()
  }
}
