package io.eezo.db

import io.eezo.db.Scopes.*
import io.eezo.db.engine.{ConnectionUnavailable, Database, Installed, Pool}
import io.eezo.db.support.{Pg, PgSuite, Refusing}

import java.net.{ServerSocket, Socket, URI}
import java.sql.{Connection, SQLException}
import java.time.Duration
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** The pool against a real Postgres: what it reuses, when it opens, and how it fails.
  *
  * Each test builds its own `Database` rather than taking one from `DbSuite`, because what is under
  * test is the pool's lifecycle, and a `Database` shared across tests would carry connections
  * opened by the previous one.
  */
class PoolSuite extends PgSuite {

  private def backend()(using s: DB): Int = {
    val st = s.connection.createStatement()
    try {
      val rs = st.executeQuery("select pg_backend_pid()")
      try { rs.next(); rs.getInt(1) }
      finally rs.close()
    } finally st.close()
  }

  /** A pool of one, so that "the same connection" is a fact about the pool rather than about which
    * thread HikariCP's bag happened to hand back to.
    */
  private def single(init: Connection -> Unit = _ => ()): Database =
    new Database(
      new Pool(Pg.jdbcUrl, Pg.username, Pg.password, init, size = 1, Duration.ofSeconds(5))
    )

  private def millisSince(start: Long): Long = (System.nanoTime() - start) / 1000000

  /** A local port in front of the shared container that refuses until it is switched on and then
    * forwards. The pool keeps the one url it was built with, so what the test sees is the database
    * going away and coming back under the same pool, while the shared container keeps serving every
    * other suite: stopping the container itself would take them all down with it.
    */
  private final class Outage extends AutoCloseable {
    private val target       = new URI(Pg.jdbcUrl.stripPrefix("jdbc:"))
    private val server       = new ServerSocket(0)
    private val open         = new ConcurrentLinkedQueue[Socket]()
    @volatile private var up = false

    /** The container's url with only the port changed, so database and credentials still match. */
    val url: String =
      s"jdbc:postgresql://127.0.0.1:${server.getLocalPort}${target.getRawPath}" +
        Option(target.getRawQuery).fold("")("?" + _)

    def restore(): Unit = up = true

    private def daemon(body: Runnable): Unit = {
      val t = new Thread(body)
      t.setDaemon(true)
      t.start()
    }

    /** Accepting and hanging up at once, rather than not listening, keeps the port ours for the
      * whole test: a released port could be taken by something else before `restore`.
      */
    daemon { () =>
      try
        while (true) {
          val client = server.accept()
          open.add(client): Unit
          if (!up) client.close()
          else {
            val pg = new Socket(target.getHost, target.getPort)
            open.add(pg): Unit
            daemon(() => pipe(client, pg))
            daemon(() => pipe(pg, client))
          }
        }
      catch { case _: java.io.IOException => () }
    }

    private def pipe(from: Socket, to: Socket): Unit =
      try from.getInputStream.transferTo(to.getOutputStream): Unit
      catch { case _: java.io.IOException => () }
      finally { from.close(); to.close() }

    def close(): Unit = {
      server.close()
      open.forEach(_.close())
    }
  }

  /** Every test owns its pool, and a pool left open keeps its connections and HikariCP threads for
    * the rest of the forked test JVM, starving the suites that run after it.
    */
  private def installing[A](d: Database)(f: => A): A = {
    Installed.install(d)
    try f
    finally {
      Installed.uninstall()
      d.close()
    }
  }

  test("a second read reuses the connection the first one released") {
    installing(single()) {
      val first  = read { backend() }
      val second = read { backend() }
      assertEquals(second, first, "the same Postgres backend served both reads")
    }
  }

  test("init runs once per physical connection, not once per borrow") {
    exec("""create table "opened" (pid int not null)""")
    val schema                     = pgSchema
    val record: Connection -> Unit = c => {
      val st = c.createStatement()
      try st.execute(s"""insert into "$schema"."opened" values (pg_backend_pid())"""): Unit
      finally st.close()
    }
    val (first, second) = installing(single(record)) {
      (read { backend() }, read { backend() })
    }
    assertEquals(second, first, "both reads borrowed the same physical connection")
    val st = db.createStatement()
    try {
      val rs = st.executeQuery("""select count(*) from "opened"""")
      try {
        rs.next()
        assertEquals(rs.getInt(1), 1, "init ran exactly once across both borrows")
      } finally rs.close()
    } finally st.close()
  }

  test("credentials written in the url win over the ones given beside it") {
    val sep  = if (Pg.jdbcUrl.contains("?")) "&" else "?"
    val url  = s"${Pg.jdbcUrl}${sep}user=${Pg.username}&password=${Pg.password}"
    val pool = new Pool(url, "nobody", "nothing", _ => (), size = 1, Duration.ofSeconds(5))
    installing(new Database(pool)) {
      assert(read { backend() } > 0, "the read connected as the url's user")
    }
  }

  test("connect never waits on the network: a host that never answers still builds at once") {
    // Listening without ever accepting: the kernel completes the handshake and then nothing is said,
    // as with a wedged or firewalled database host. A pool that tried even one connection while
    // being built would block here until the login timeout, seconds rather than milliseconds, where
    // a refused port would fail fast and hide it.
    val silent    = new ServerSocket(0)
    val (d, took) =
      try {
        val start = System.nanoTime()
        val d     = Database.connect(
          s"jdbc:postgresql://127.0.0.1:${silent.getLocalPort}/none",
          "nobody",
          "nothing",
          init = _ => (),
          size = DbInit.DefaultPoolSize,
          acquireTimeout = DbInit.DefaultAcquireTimeout
        )
        (d, millisSince(start))
      } finally silent.close()
    d.close()
    assert(took < 1000, s"building the pool waited $took ms on a host that never answers")
  }

  test(
    "with the database down, the first read waits the acquire timeout and fails in eezo's words"
  ) {
    val timeout = Duration.ofMillis(500)
    val pool    = new Pool(Refusing.jdbcUrl(), "nobody", "hunter2", _ => (), size = 1, timeout)
    installing(new Database(pool)) {
      val start  = System.nanoTime()
      val e      = intercept[ConnectionUnavailable](read { backend() })
      val waited = millisSince(start)
      assert(waited >= timeout.toMillis - 50, s"the borrow gave up after $waited ms")
      assert(e.getMessage.contains("refused"), s"the driver's failure is missing: ${e.getMessage}")
      assert(!e.getMessage.contains("hunter2"), e.getMessage)
      assert(!e.getMessage.contains("detached"), s"no detached block is involved: ${e.getMessage}")
    }
  }

  test("a detached block that needs a second connection from a full pool fails, and says why") {
    // A pool of one, and the read around the detached block holds it: the second borrow can only
    // wait on the thread that is waiting, so the timeout is all that stands between this and a hang.
    val timeout = Duration.ofMillis(500)
    val pool    = new Pool(Pg.jdbcUrl, Pg.username, Pg.password, _ => (), size = 1, timeout)
    installing(new Database(pool)) {
      val start  = System.nanoTime()
      val e      = intercept[ConnectionUnavailable](read { detached { backend() } })
      val waited = millisSince(start)
      assert(waited >= timeout.toMillis - 50, s"the borrow gave up after $waited ms")
      assert(waited < 10000, s"the borrow waited $waited ms, which is a hang, not a timeout")
      assert(
        e.getMessage.contains("a `detached` block took a second connection from a full pool"),
        e.getMessage
      )
      assert(!e.getMessage.contains("\n"), e.getMessage)
    }
  }

  /** A pool of one whose only connection another thread holds for as long as `body` runs, so every
    * borrow in `body` finds the pool full while the database is up.
    */
  private def starved[A](timeout: Duration)(body: => A): A = {
    val pool = new Pool(Pg.jdbcUrl, Pg.username, Pg.password, _ => (), size = 1, timeout)
    installing(new Database(pool)) {
      val held    = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable]()
      // the holder's own first borrow opens a physical connection under the short timeout, so it
      // can fail; the latch opens either way, and the failure is what the test then reports
      val holder = Thread.ofVirtual().start { () =>
        try read { held.countDown(); release.await() }
        catch { case e: Throwable => failure.set(e) }
        finally held.countDown()
      }
      try {
        assert(
          held.await(10, TimeUnit.SECONDS),
          "the holder neither got the only connection nor failed"
        )
        Option(failure.get()).foreach(e => fail("the holder never got the only connection", e))
        body
      } finally {
        release.countDown()
        holder.join()
      }
    }
  }

  test("a full pool starved by another thread carries no detached hint, plain or detached") {
    // the pool is full, but this thread holds nothing of it: the hint would be a false lead
    starved(Duration.ofMillis(500)) {
      val plain = intercept[ConnectionUnavailable](read { backend() })
      assert(!plain.getMessage.contains("detached"), plain.getMessage)
      val alone = intercept[ConnectionUnavailable](detached { backend() })
      assert(!alone.getMessage.contains("detached"), alone.getMessage)
    }
  }

  test("a detached block on a thread forked inside a read carries no detached hint") {
    // the fork inherits the parent's marker, but the connection it names is the parent's: the fork
    // holds nothing, so telling it that it waited on itself would be a false lead
    val timeout = Duration.ofMillis(500)
    val pool    = new Pool(Pg.jdbcUrl, Pg.username, Pg.password, _ => (), size = 1, timeout)
    installing(new Database(pool)) {
      var caught: Option[ConnectionUnavailable] = None
      read {
        val t = Thread.ofVirtual().unstarted { () =>
          caught =
            try { detached { backend() }; None }
            catch { case e: ConnectionUnavailable => Some(e) }
        }
        t.start()
        t.join()
      }
      val e = caught.getOrElse(fail("the fork's borrow did not time out"))
      assert(!e.getMessage.contains("detached"), e.getMessage)
    }
  }

  test("a database that comes back is used again by the same pool, with no restart") {
    val outage  = new Outage
    val timeout = Duration.ofMillis(500)
    try {
      val pool = new Pool(outage.url, Pg.username, Pg.password, _ => (), size = 1, timeout)
      installing(new Database(pool)) {
        intercept[ConnectionUnavailable](read { backend() })
        outage.restore()
        // HikariCP retries on its own schedule, so the first borrow after the restore may still
        // time out; what matters is that this pool gets there without being rebuilt.
        val deadline      = System.nanoTime() + Duration.ofSeconds(15).toNanos
        def served(): Int =
          try read { backend() }
          catch {
            case _: ConnectionUnavailable if System.nanoTime() < deadline => served()
          }
        assert(served() > 0, "the same pool served a read once Postgres was back")
      }
    } finally outage.close()
  }

  test("after close a read fails at once, without waiting out the acquire timeout") {
    val d = single()
    Installed.install(d)
    try {
      read { backend() }: Unit
      d.close()
      val start = System.nanoTime()
      intercept[SQLException](read { backend() })
      val waited = millisSince(start)
      assert(waited < 1000, s"a closed pool made the read wait $waited ms")
    } finally Installed.uninstall()
  }

  test("a read after a transact on the same connection is back in auto commit") {
    val (wrote, after) = installing(single()) {
      val wrote = transact { backend() }
      val after = read { (backend(), summon[DB].connection.getAutoCommit) }
      (wrote, after)
    }
    assertEquals(after._1, wrote, "the read borrowed the connection the transaction released")
    assert(after._2, "the read would otherwise run inside a transaction nobody commits")
  }
}
