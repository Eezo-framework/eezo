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

  test("a config marker is one event with the variables and the offenders, and never a log line") {
    val p = new LineProtocol
    assertEquals(
      p.stdout(
        """eezo dev: config {"variables":[{"variable":"SHOP_PORT","path":"port","redacted":false,"need":"required","set":true},{"variable":"STRIPE_SECRET_KEY","path":"stripe.secretKey","redacted":true,"need":"required","set":false}],"offenders":[{"kind":"missing","path":"stripe.secretKey","variables":["STRIPE_SECRET_KEY"],"line":"stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it"},{"kind":"malformed","path":"port","variables":["SHOP_PORT"],"feeding":["SHOP_PORT"],"reason":"is not a whole number","line":"port is not a whole number"}]}"""
      ),
      Vector(
        Event.ConfigReported(
          ConfigReport(
            Vector(
              ConfigRow("SHOP_PORT", "port", redacted = false, "required", set = true),
              ConfigRow(
                "STRIPE_SECRET_KEY",
                "stripe.secretKey",
                redacted = true,
                "required",
                set = false
              )
            ),
            Vector(
              ConfigOffender(
                ConfigOffender.Missing,
                "stripe.secretKey",
                Vector("STRIPE_SECRET_KEY"),
                Vector.empty,
                "stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it"
              ),
              ConfigOffender(
                ConfigOffender.Malformed,
                "port",
                Vector("SHOP_PORT"),
                Vector("SHOP_PORT"),
                "port is not a whole number"
              )
            )
          )
        )
      )
    )
  }

  test(
    "a config marker that leaves a field out decodes to the safe reading: masked, never asked, nothing named"
  ) {
    val p = new LineProtocol
    assertEquals(
      p.stdout(
        """eezo dev: config {"variables":[{"variable":"STRIPE_SECRET_KEY","path":"stripe.secretKey","need":"required","set":false}],"offenders":[{"kind":"shadowed","path":"stripe.secretKey","variables":["STRIPE_SECRET_KEY"],"line":"stripe.secretKey is shadowed"},{"kind":"missing","path":"port"}]}"""
      ),
      Vector(
        Event.ConfigReported(
          ConfigReport(
            Vector(
              ConfigRow(
                "STRIPE_SECRET_KEY",
                "stripe.secretKey",
                redacted = true,
                "required",
                set = false
              )
            ),
            Vector(
              ConfigOffender(
                ConfigOffender.Other("shadowed"),
                "stripe.secretKey",
                Vector("STRIPE_SECRET_KEY"),
                Vector.empty,
                "stripe.secretKey is shadowed"
              ),
              ConfigOffender(ConfigOffender.Missing, "port", Vector.empty, Vector.empty, "")
            )
          )
        )
      )
    )
    p.stdout(
      """eezo dev: config {"variables":[],"offenders":[{"kind":"shadowed","path":"a","variables":["A"],"line":"a"},{"kind":"missing","path":"port"}]}"""
    ) match {
      case Vector(Event.ConfigReported(report)) => assertEquals(report.fixable, Vector.empty)
      case other                                => fail(other.toString)
    }
  }

  test("an unreadable config marker lands in the log, as routes and drift do") {
    val p = new LineProtocol
    p.stdout("eezo dev: config {not json") match {
      case Vector(Event.AppLine(text, true)) =>
        assert(text.contains("[eezo dev] unreadable config marker"), text)
      case other => fail(other.toString)
    }
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

  test("a stdout line between a record header and its level on stderr leaves the pair alone") {
    val p = new LineProtocol
    assertEquals(
      p.stderr("Oct 27, 2026 12:00:01 PM org.eclipse.jetty.server.Server doStart"),
      Vector.empty[Event]
    )
    assertEquals(p.stdout("3 routes:"), Vector(Event.AppLine("3 routes:", err = false)))
    assertEquals(p.stderr("INFO: jetty-12.1.11; built: 2026-01-01"), Vector.empty[Event])
    assertEquals(p.flush(), Vector.empty[Event])
  }

  test("the level is matched in the JVM's own language") {
    assert(LineProtocol.isNoiseLevel("INFO: x"))
    assert(LineProtocol.isNoiseLevel("FINER: x"))
    assert(!LineProtocol.isNoiseLevel("WARNING: x"))
    assert(!LineProtocol.isNoiseLevel("SEVERE: x"))
    assert(!LineProtocol.isNoiseLevel("no colon here"))
    val before = java.util.Locale.getDefault
    try {
      java.util.Locale.setDefault(java.util.Locale.ITALIAN)
      val localized = java.util.logging.Level.INFO.getLocalizedName
      assert(LineProtocol.isNoiseLevel(s"$localized: x"), localized)
      assert(!LineProtocol.isNoiseLevel(s"${java.util.logging.Level.WARNING.getLocalizedName}: x"))
    } finally java.util.Locale.setDefault(before)
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

  test("quiet drops the INFO records of a stream read whole and keeps the rest") {
    val lines = Vector(
      "Oct 29, 2026 11:45:04 AM com.zaxxer.hikari.HikariDataSource <init>",
      "INFO: eezo - Starting...",
      "Oct 29, 2026 11:45:04 AM org.eclipse.jetty.server.Server doStart",
      "WARNING: port in use",
      "plain line"
    )
    assertEquals(
      LineProtocol.quiet(lines),
      Vector(
        "Oct 29, 2026 11:45:04 AM org.eclipse.jetty.server.Server doStart",
        "WARNING: port in use",
        "plain line"
      )
    )
  }
}
