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

    /** Jetty. 12.1.11 is what `research/http-server.md` measured and recommends, and it is the
      * first 12.1 clear of both 2026 advisories that section 9 lists: CVE-2026-6790 (patched in
      * 12.1.9) and CVE-2026-10051, the cross-request trailer leak on keep-alive connections that
      * eezo would be exposed to behind Caddy (patched in 12.1.10).
      */
    val jetty = "12.1.11"
  }

  /** The test framework. Every module gets it; nothing else is shared by default. */
  val munit = "org.scalameta" %% "munit" % V.munit % Test

  /** The HTTP server, `modules/http` only. `jetty-websocket-jetty-server` pulls the server core
    * with it, so these two coordinates are the whole of eezo's server dependency: ten jars, of
    * which the only non-Jetty one is `slf4j-api`.
    */
  val jettyServer   = "org.eclipse.jetty"           % "jetty-server"                 % V.jetty
  val jettyWsServer = "org.eclipse.jetty.websocket" % "jetty-websocket-jetty-server" % V.jetty

  /** Jetty's WebSocket client, used by `modules/http`'s own tests to drive a real upgrade against a
    * booted server. Test scope: nothing eezo publishes depends on it.
    */
  val jettyWsClient =
    "org.eclipse.jetty.websocket" % "jetty-websocket-jetty-client" % V.jetty % Test

  /** The JDBC driver. `db` is the only module that speaks to a database. */
  val postgresql = "org.postgresql" % "postgresql" % V.postgresql

  /** A real Postgres for the `db` suite.
    *
    * The database tests assert against Postgres's own catalog and its own constraint enforcement,
    * so an in-memory substitute would test something other than the thing that ships. The Java
    * library is used directly rather than a Scala wrapper: the suite needs one container shared
    * across suites with a schema per suite, and that is a dozen lines either way.
    */
  val testcontainersPg = "org.testcontainers" % "postgresql" % V.testcontainers % Test
}
