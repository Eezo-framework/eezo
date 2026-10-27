package io.eezo.sbt.dev

class LineProtocolSuite extends munit.FunSuite {

  test("a routes marker is one event and never a log line") {
    val p      = new LineProtocol
    val events = p.stdout(
      """eezo dev: routes {"command":"routes","routes":[{"method":"GET","path":"/","provenance":"file","api":false}],"overridden":[],"shadowed":[],"orphans":[]}"""
    )
    events match {
      case Vector(Event.RoutesReported(listing)) =>
        assertEquals(listing.routes.map(_.path), Vector("/"))
      case other => fail(other.toString)
    }
  }

  test("a drift marker carries the server's blocking decision") {
    val p = new LineProtocol
    assertEquals(
      p.stdout(
        """eezo dev: drift {"command":"status","inSync":false,"blocking":false,"changes":[{"describe":"+ todo.priority integer null","destructive":false,"risky":false,"sql":"alter table \"todo\" add column \"priority\" integer"}]}"""
      ),
      Vector(
        Event.DriftReported(
          Drift(
            Vector(
              Change(
                "+ todo.priority integer null",
                destructive = false,
                risky = false,
                "alter table \"todo\" add column \"priority\" integer"
              )
            ),
            blocking = false
          )
        )
      )
    )
  }

  test("the serving line is both the address event and a log line, as the bash console showed it") {
    val p = new LineProtocol
    assertEquals(
      p.stdout("serving on http://localhost:8090"),
      Vector(
        Event.Serving("http://localhost:8090"),
        Event.AppLine("serving on http://localhost:8090", err = false)
      )
    )
  }

  test("an unreadable marker lands in the log instead of crashing the loop") {
    val p = new LineProtocol
    p.stdout("eezo dev: drift {not json") match {
      case Vector(Event.AppLine(text, true)) =>
        assert(text.contains("unreadable drift marker"), text)
      case other => fail(other.toString)
    }
  }

  test("Jetty and HikariCP INFO records are dropped, two lines at a time") {
    val p = new LineProtocol
    assertEquals(
      p.stderr("Oct 27, 2026 12:00:01 PM org.eclipse.jetty.server.Server doStart"),
      Vector.empty[Event]
    )
    assertEquals(p.stderr("INFO: jetty-12.1.11; built: 2026-01-01"), Vector.empty[Event])
    assertEquals(
      p.stderr("Oct 27, 2026 12:00:01 PM com.zaxxer.hikari.HikariDataSource getConnection"),
      Vector.empty[Event]
    )
    assertEquals(p.stderr("CONFIG: pool stats"), Vector.empty[Event])
  }

  test("their warnings pass through with the held header") {
    val p = new LineProtocol
    assertEquals(
      p.stderr("Oct 27, 2026 12:00:01 PM org.eclipse.jetty.server.Server doStart"),
      Vector.empty[Event]
    )
    assertEquals(
      p.stderr("WARNING: something is off"),
      Vector(
        Event
          .AppLine("Oct 27, 2026 12:00:01 PM org.eclipse.jetty.server.Server doStart", err = true),
        Event.AppLine("WARNING: something is off", err = true)
      )
    )
  }

  test("a stack frame naming jetty is not a record header") {
    val p = new LineProtocol
    assertEquals(
      p.stderr("\tat org.eclipse.jetty.server.Server.doStart(Server.java:1)"),
      Vector(
        Event.AppLine("\tat org.eclipse.jetty.server.Server.doStart(Server.java:1)", err = true)
      )
    )
  }

  test("a header the stream ends on is flushed, not lost") {
    val p = new LineProtocol
    assertEquals(
      p.stderr("Oct 27, 2026 12:00:01 PM org.eclipse.jetty.server.Server doStart"),
      Vector.empty[Event]
    )
    assertEquals(p.flush().size, 1)
    assertEquals(p.flush().size, 0)
  }

  test("the application's own lines pass through, stdout and stderr marked apart") {
    val p = new LineProtocol
    assertEquals(p.stdout("3 routes:"), Vector(Event.AppLine("3 routes:", err = false)))
    assertEquals(
      p.stderr("[eezo] ⚠ drift check skipped"),
      Vector(Event.AppLine("[eezo] ⚠ drift check skipped", err = true))
    )
  }
}
