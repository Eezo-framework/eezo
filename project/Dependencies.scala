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

    /** spring-security-crypto. Picked because it is the one bcrypt implementation on the JVM that
      * ships as a jar with no transitive dependency of its own, so `eezo-auth` costs an application
      * one jar rather than a framework.
      */
    val springCrypto = "7.1.1"

    /** Jetty. 12.1.11 is what `research/http-server.md` measured and recommends, and it is the
      * first 12.1 clear of both 2026 advisories that section 9 lists: CVE-2026-6790 (patched in
      * 12.1.9) and CVE-2026-10051, the cross-request trailer leak on keep-alive connections that
      * eezo would be exposed to behind Caddy (patched in 12.1.10).
      */
    val jetty = "12.1.11"

    /** HikariCP. 7.1.0 is a floor, not a preference: the releases before it spin in
      * `ConcurrentBag.requite` with `Thread.yield` when a connection returns to a pool that has
      * waiters, which saturates every carrier thread under virtual thread load (HikariCP issue
      * 2398, fixed by PR 2402). eezo serves each request on a virtual thread, so that is the load
      * it always runs under.
      */
    val hikari = "7.1.0"

    /** The SLF4J binding. It follows the `slf4j-api` that Jetty and HikariCP both pin in their POMs
      * rather than leading it: a provider at another version either evicts that API or runs against
      * one it was not built for, and the next Jetty or HikariCP bump that moves the API moves this
      * with it.
      */
    val slf4j = "2.0.17"
  }

  /** The test framework. Every module gets it; nothing else is shared by default. */
  val munit = "org.scalameta" %% "munit" % V.munit % Test

  /** The HTTP server, `modules/http` only. `jetty-websocket-jetty-server` pulls the server core
    * with it, so these two coordinates are the whole of eezo's server code: ten jars, of which the
    * only non Jetty one is `slf4j-api`. The eleventh jar an application gets is `slf4jJdk14`, which
    * carries no server code and exists only to give `slf4j-api` somewhere to write.
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

  /** The connection pool, `db` only, behind `engine.Pool`.
    *
    * A pool is the kind of code whose bugs show up as a stall under load on somebody else's
    * machine, and HikariCP is the one on the JVM whose failure modes are already known. It brings
    * `slf4j-api` with it and no binding of its own; the binding comes from `slf4jJdk14`, which `db`
    * ships beside it.
    */
  val hikari = "com.zaxxer" % "HikariCP" % V.hikari

  /** One logging backend for Jetty, HikariCP and eezo, chosen by the framework.
    *
    * eezo writes its own lines through the JDK's `System.Logger`, and Jetty and HikariCP write
    * theirs through `slf4j-api`. Left to the application, a binding is something only the examples
    * with a server remembered, so a database only application printed SLF4J's no providers banner
    * on its first run and lost every line HikariCP wrote about its pool. Shipping this binding with
    * the two modules that bring `slf4j-api` puts all three on one backend by default. It is on both
    * rather than on the umbrella so that an application on one edge alone gets it too. An
    * application that wants Logback excludes it and adds `slf4j-jdk-platform-logging` instead.
    */
  val slf4jJdk14 = "org.slf4j" % "slf4j-jdk14" % V.slf4j % Runtime

  /** A real Postgres for the `db` suite.
    *
    * The database tests assert against Postgres's own catalog and its own constraint enforcement,
    * so an in-memory substitute would test something other than the thing that ships. The Java
    * library is used directly rather than a Scala wrapper: the suite needs one container shared
    * across suites with a schema per suite, and that is a dozen lines either way.
    */
  val testcontainersPg = "org.testcontainers" % "postgresql" % V.testcontainers % Test

  /** bcrypt, `modules/auth` only.
    *
    * Hashing a password is the one thing in eezo that must not be written here: the cost of getting
    * it subtly wrong is silent and permanent, and every review of a hand rolled implementation
    * starts by asking why it exists. This artifact is the crypto half of Spring Security on its
    * own, with no Spring context, no servlet and no transitive dependency behind it.
    */
  val springCrypto = "org.springframework.security" % "spring-security-crypto" % V.springCrypto
}
