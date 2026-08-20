import sbt._

/** Every external dependency eezo takes, and its version, in one place.
  *
  * Modules refer to the vals below rather than writing coordinates inline, so that a version bump
  * is a one-line change and two modules cannot disagree about a version.
  */
object Dependencies {

  object V {
    val munit          = "1.3.4"
    val postgresql     = "42.7.1"
    val testcontainers = "1.21.3"
  }

  /** The test framework. Every module gets it; nothing else is shared by default. */
  val munit = "org.scalameta" %% "munit" % V.munit % Test

  /** The JDBC driver. `db` is the only module that speaks to a database. */
  val postgresql = "org.postgresql" % "postgresql" % V.postgresql

  /** A real Postgres for the `db` suite.
    *
    * The database tests assert against Postgres's own catalog and its own constraint
    * enforcement, so an in-memory substitute would test something other than the thing that
    * ships. The Java library is used directly rather than a Scala wrapper: the suite needs
    * one container shared across suites with a schema per suite, and that is a dozen lines
    * either way.
    */
  val testcontainersPg = "org.testcontainers" % "postgresql" % V.testcontainers % Test
}
