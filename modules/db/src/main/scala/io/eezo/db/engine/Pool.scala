package io.eezo.db.engine

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.ds.PGSimpleDataSource

import java.sql.Connection
import java.time.Duration

/** Connections for one database, owned by `Database` and by nothing else. DESIGN §8.7.
  *
  * A real pool, because opening a Postgres connection costs a TCP handshake, authentication and a
  * backend process, and a request that paid that on every `read` would spend more time connecting
  * than querying. The pool is HikariCP rather than one written here: a pool's bugs are stalls under
  * load, and HikariCP's are already found.
  *
  * The size is fixed, `minimumIdle` equal to `maximumPoolSize`, because a pool that shrinks when idle
  * pays the connect cost again on the first request after a quiet spell, which is exactly the request
  * somebody is watching. The acquire timeout is short because `detached` borrows a second connection
  * while the caller's transaction still holds its first: one thread can hold two connections, so a
  * full pool can deadlock, and a timeout is what turns that into an error instead of a hang.
  *
  * The pool starts empty (`initializationFailTimeout` negative) so that building one never waits on
  * the network: an application whose database is down still boots, and fails the first request that
  * needs Postgres, after the acquire timeout, rather than failing in `main`. HikariCP keeps retrying
  * in the background, so the database coming back needs no restart.
  *
  * `init` runs on every physical connection the pool opens, not once on a borrowed one. That
  * distinction is what makes per suite `search_path` isolation work: `search_path` is per
  * connection, so a suite that set it on one borrowed connection would silently read the wrong
  * schema through the next. Production wants the same hook for `application_name` and statement
  * timeouts.
  */
final class Pool private[eezo] (
    url: String,
    user: String,
    password: String,
    init: Connection -> Unit,
    size: Int,
    acquireTimeout: Duration
) {

  private val ds: HikariDataSource = {
    val config = new HikariConfig()
    config.setDataSource(new InitialisingDataSource(url, user, password, init))
    config.setMaximumPoolSize(size)
    config.setMinimumIdle(size)
    config.setConnectionTimeout(acquireTimeout.toMillis)
    config.setInitializationFailTimeout(-1)
    config.setPoolName("eezo")
    new HikariDataSource(config)
  }

  private[eezo] def acquire(): Connection = ds.getConnection()

  /** Closing HikariCP's proxy returns the connection to the pool, and resets on the way what a scope
    * may have changed, auto commit among it, so the next borrower never inherits an open
    * transaction.
    */
  private[eezo] def release(c: Connection): Unit = c.close()

  private[eezo] def close(): Unit = ds.close()
}

/** Where `init` meets HikariCP: the one point every physical connection passes through exactly once,
  * on the pool's adder thread, before HikariCP sees it.
  *
  * Both overloads are overridden because HikariCP calls the one without credentials when its own
  * config has none, which is the case here, and `PGSimpleDataSource`'s version of that one calls the
  * other through virtual dispatch. So the no argument one must not reach `super`: it would run `init`
  * a second time on the same connection.
  *
  * The url is set last, and the no argument overload asks the data source for its credentials rather
  * than taking the constructor's, so that a user and password written in the url win over the ones
  * given beside it, as they did with `DriverManager`: hosts that hand out a single JDBC url with the
  * credentials inside it must keep connecting as the url says.
  */
private[engine] final class InitialisingDataSource(
    url: String,
    user: String,
    password: String,
    init: Connection -> Unit
) extends PGSimpleDataSource {

  setUser(user)
  setPassword(password)
  setUrl(url)

  override def getConnection(): Connection = getConnection(getUser, getPassword)

  override def getConnection(username: String, pass: String): Connection = {
    val c = super.getConnection(username, pass)
    try init(c)
    catch {
      case e: Throwable =>
        // The close failure rides along on init's, so HikariCP reports why the connection was bad
        // rather than why cleaning it up also went wrong.
        try c.close()
        catch { case r: Throwable => e.addSuppressed(r) }
        throw e
    }
    c
  }
}
