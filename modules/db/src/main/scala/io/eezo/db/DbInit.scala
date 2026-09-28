package io.eezo.db

import io.eezo.db.engine.Database

import java.net.{URI, URLDecoder}
import java.sql.Connection
import java.time.Duration

/** How an application names its database: the connection settings and the per connection hook.
  *
  * Split out of [[DbApp]] so that a front end which is not the application's entry point (a deploy
  * runner, the testkit) can build the same `Database` from the same settings without inheriting
  * `boot`. `DbApp` extends this, and the umbrella's `EezoApp` through it; users override members
  * here and never name the trait.
  *
  * Named for [[databaseInit]], its one member that is not a string, because it is the one people
  * get wrong; see its comment.
  *
  * **Two spellings of the environment, one mechanism.** `EEZO_DB_URL`/`EEZO_DB_USER`/
  * `EEZO_DB_PASS` are eezo's own and always win. When they are absent and `DATABASE_URL` is present
  * (the `postgres://user:pass@host/db` form every PaaS injects: Fly, Heroku, Render), it is parsed
  * into the three, so a deployed application configures itself from the one secret the platform
  * already set. This is a fallback, not a second configuration system: the members below are still
  * the only way the settings are read.
  *
  * `EEZO_DB_POOL_SIZE` (default 10) and `EEZO_DB_ACQUIRE_TIMEOUT` (milliseconds, default 5000) size
  * the pool; `DATABASE_URL` has no say in either.
  */
trait DbInit {

  private lazy val fromPlatform: Option[DbInit.Parsed] =
    sys.env.get("DATABASE_URL").flatMap(DbInit.parseDatabaseUrl)

  /** Overridable, so a test or a second environment can point elsewhere without touching `boot`. */
  def databaseUrl: String =
    sys.env
      .get("EEZO_DB_URL")
      .orElse(fromPlatform.map(_.jdbcUrl))
      .getOrElse("jdbc:postgresql://localhost:5442/eezo")

  def databaseUser: String =
    sys.env.get("EEZO_DB_USER").orElse(fromPlatform.map(_.user)).getOrElse("postgres")

  def databasePassword: String =
    sys.env.get("EEZO_DB_PASS").orElse(fromPlatform.map(_.password)).getOrElse("postgres")

  /** Small on purpose. HikariCP's sizing rule is connections = cores * 2 + spindles, because
    * Postgres does its work on CPU and disk, and connections beyond what those can serve only queue
    * inside the database instead of in the pool, where queueing is cheaper. Virtual threads make
    * requests cheap, not connections: thousands of requests waiting briefly on a small saturated
    * pool is the intended shape, not a sign the pool is too small.
    */
  def databasePoolSize: Int = DbInit.poolSize(sys.env.get("EEZO_DB_POOL_SIZE"))

  /** Short on purpose. `detached` borrows a second connection while the caller still holds its
    * first, so one thread can hold two, and a full pool can deadlock on itself; the timeout is the
    * only thing that turns that into an error. HikariCP's own 30 seconds reads as a hang to whoever
    * is waiting on the request, and a request that cannot get a connection in 5 seconds is better
    * answered with a 500 than left open.
    */
  def databaseAcquireTimeout: Duration =
    DbInit.acquireTimeout(sys.env.get("EEZO_DB_ACQUIRE_TIMEOUT"))

  /** Run on **every** connection the pool creates, not once on a borrowed one (DESIGN §8.7).
    *
    * This is where `search_path`, `application_name` and statement timeouts belong. Without it an
    * application that works in a non-`public` schema silently splits in two: its own connections
    * see one schema and eezo's see another, which shows up as a migration that appears not to have
    * run.
    */
  def databaseInit: Connection -> Unit = _ => ()

  protected def database: Database =
    Database.connect(
      databaseUrl,
      databaseUser,
      databasePassword,
      databaseInit,
      databasePoolSize,
      databaseAcquireTimeout
    )
}

object DbInit {

  private[eezo] val DefaultPoolSize: Int = 10

  /** A value HikariCP would refuse, below one, is as malformed as one that is not a number: either
    * way it falls through to the default rather than failing `main`.
    */
  private[eezo] def poolSize(env: Option[String]): Int =
    env.flatMap(_.trim.toIntOption).filter(_ >= 1).getOrElse(DefaultPoolSize)

  private[eezo] val DefaultAcquireTimeout: Duration = Duration.ofSeconds(5)

  /** HikariCP refuses a timeout under 250 ms and reads 0 as waiting forever, which is the hang this
    * setting exists to prevent, so both count as malformed and fall through to the default.
    */
  private[eezo] def acquireTimeout(env: Option[String]): Duration =
    env
      .flatMap(_.trim.toLongOption)
      .filter(_ >= 250)
      .map(Duration.ofMillis)
      .getOrElse(DefaultAcquireTimeout)

  private[eezo] final case class Parsed(jdbcUrl: String, user: String, password: String)

  /** `None` for anything that is not a postgres URL with a host and a database, because a malformed
    * platform value should fall through to the defaults and fail at connect time with the defaults
    * in the message, not half apply.
    *
    * User and password come out of the URL and percent decoded, because JDBC takes them as separate
    * properties, decoded, while the platform folds them into `postgres://user:pass@host/db` and
    * encodes them (a password with `@` or `/` must be).
    *
    * TLS is required when the query names no `sslmode`, because the driver's own fallback is
    * `prefer`, which quietly settles for clear text when the server refuses TLS, and Heroku and
    * Render inject a URL with no `sslmode` while expecting the client to insist. An explicit
    * `sslmode` wins with any value, and the query then travels byte for byte, because whoever wrote
    * it meant it: Fly's attach writes `sslmode=disable` on purpose for its private network. Only
    * the exact key the driver reads counts, so `foosslmode` does not, and a bare `sslmode` stays
    * for the driver to refuse rather than being quietly overridden. `EEZO_DB_URL` is never edited,
    * so it stays the way to hand the driver anything this does not allow.
    */
  private[eezo] def parseDatabaseUrl(url: String): Option[Parsed] =
    try {
      val uri = new URI(url)
      val ok  = (uri.getScheme == "postgres" || uri.getScheme == "postgresql") &&
        uri.getHost != null && uri.getPath != null && uri.getPath.length > 1
      if (!ok) None
      else {
        val (user, password) = uri.getRawUserInfo match {
          case null => ("postgres", "")
          case info =>
            def decoded(s: String): String = URLDecoder.decode(s, "UTF-8")
            info.indexOf(':') match {
              case -1 => (decoded(info), "")
              case at => (decoded(info.take(at)), decoded(info.drop(at + 1)))
            }
        }
        val port  = if (uri.getPort == -1) "" else s":${uri.getPort}"
        val query = Option(uri.getRawQuery).filter(_.nonEmpty) match {
          case None                       => "?sslmode=require"
          case Some(q) if namesSslmode(q) => s"?$q"
          case Some(q)                    => s"?$q&sslmode=require"
        }
        Some(Parsed(s"jdbc:postgresql://${uri.getHost}$port${uri.getPath}$query", user, password))
      }
    } catch {
      case _: java.net.URISyntaxException => None
    }

  private def namesSslmode(rawQuery: String): Boolean =
    rawQuery.split('&').exists(_.takeWhile(_ != '=') == "sslmode")
}
