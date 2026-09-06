package io.eezo.db

import io.eezo.db.engine.Database

import java.sql.Connection

/** How an application names its database: the connection settings and the per-connection hook.
  *
  * Split out of [[EezoApp]] so that a front-end which is not the application's entry point — the
  * CLI dispatch in `modules/eezo`, a deploy runner — can build the same `Database` from the same
  * settings without inheriting `boot`. `EezoApp` extends this; users override members here and
  * never name the trait.
  *
  * Named for [[databaseInit]], its one member that is not a string, because it is the one people
  * get wrong — see its comment.
  */
trait DbInit {

  /** Overridable, so a test or a second environment can point elsewhere without touching `boot`. */
  def databaseUrl: String =
    sys.env.getOrElse("EEZO_DB_URL", "jdbc:postgresql://localhost:5442/eezo")

  def databaseUser: String = sys.env.getOrElse("EEZO_DB_USER", "postgres")

  def databasePassword: String = sys.env.getOrElse("EEZO_DB_PASS", "postgres")

  /** Run on **every** connection the pool creates, not once on a borrowed one — DESIGN §8.7.
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
