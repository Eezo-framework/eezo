package io.eezo.db

import io.eezo.db.engine.Database

import java.net.{URI, URLDecoder}
import java.sql.Connection

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
  * `EEZO_DB_PASS` are eezo's own and always win. When they are absent and `DATABASE_URL` is
  * present (the `postgres://user:pass@host/db` form every PaaS injects: Fly, Heroku, Render), it
  * is parsed into the three, so a deployed application configures itself from the one secret the
  * platform already set. This is a fallback, not a second configuration system: the members below
  * are still the only way the settings are read.
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

  /** Run on **every** connection the pool creates, not once on a borrowed one (DESIGN §8.7).
    *
    * This is where `search_path`, `application_name` and statement timeouts belong. Without it an
    * application that works in a non-`public` schema silently splits in two: its own connections
    * see one schema and eezo's see another, which shows up as a migration that appears not to have
    * run.
    */
  def databaseInit: Connection -> Unit = _ => ()

  protected def database: Database =
    Database.connect(databaseUrl, databaseUser, databasePassword, databaseInit)
}

object DbInit {

  private[eezo] final case class Parsed(jdbcUrl: String, user: String, password: String)

  /** `postgres://user:pass@host:5432/db?sslmode=require` → the JDBC url and credentials JDBC wants
    * them as. `None` for anything that is not a postgres URL with a host and a database — a
    * malformed value should fall through to the defaults and fail at connect time with the defaults
    * in the message, not half-apply.
    *
    * User and password are percent-decoded: the platform encodes them (a password with `@` or `/`
    * must be), and JDBC takes them decoded, as separate properties. The query string travels
    * unchanged onto the JDBC url, where the Postgres driver reads the same parameter names.
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
        val query = Option(uri.getRawQuery).map("?" + _).getOrElse("")
        Some(Parsed(s"jdbc:postgresql://${uri.getHost}$port${uri.getPath}$query", user, password))
      }
    } catch {
      case _: java.net.URISyntaxException => None
    }
}
