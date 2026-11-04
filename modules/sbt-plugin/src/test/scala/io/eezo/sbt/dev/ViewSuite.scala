package io.eezo.sbt.dev

import Ansi._

/** The frame at the sizes the layout rules name, with colour off so the rows read as text. */
class ViewSuite extends munit.FunSuite {

  implicit val theme: Theme = Theme(color = false, unicode = true)

  private val listing = RouteListing(
    Vector(
      RouteRow("GET", "/", "file", api = false),
      RouteRow("POST", "/todos", "derived", api = false),
      RouteRow("GET", "/api/todos", "file", api = true),
      RouteRow("GET", "/eezo/live.js", "handwritten", api = false)
    ),
    Vector.empty,
    Vector.empty,
    Vector(Orphan("new", "/books/new", "create", "/books"))
  )

  private def model(w: Int, h: Int): Model = {
    val base   = Model.initial("todo", w, h, now = 5000L)
    val (m, _) = Seq[Event](
      Event.Changed(Set("Index.scala")),
      Event.BuildSucceeded(Vector.empty, 420L),
      Event.AppStarted("Main"),
      Event.RoutesReported(listing),
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
      ),
      Event.AppLine("3 routes:", err = false),
      Event.Serving("http://localhost:8090"),
      Event.AppLine("serving on http://localhost:8090", err = false)
    ).foldLeft((base, Vector.empty[Effect])) { case ((acc, _), e) => Update(acc, e) }
    m
  }

  private val port = ConfigRow("SHOP_PORT", "port", redacted = false, "required", set = true)
  private val key  = ConfigRow("STRIPE_KEY", "stripe.key", redacted = true, "required", set = false)
  private val currency =
    ConfigRow("SHOP_CURRENCY", "stripe.currency", redacted = false, "defaulted", set = false)

  private val refused = ConfigReport(
    Vector(port, key, currency),
    Vector(
      ConfigOffender(
        ConfigOffender.Missing,
        "stripe.key",
        Vector("STRIPE_KEY"),
        Vector.empty,
        "stripe.key is missing: set STRIPE_KEY, which application.conf names for it"
      ),
      ConfigOffender(
        ConfigOffender.Literal,
        "stripe.webhook",
        Vector.empty,
        Vector.empty,
        "stripe.webhook is stated as a literal in application.conf, and it is a Redacted"
      )
    )
  )

  private val clean = ConfigReport(Vector(port, key.copy(set = true), currency), Vector.empty)

  /** [[model]], then `events` after it. */
  private def after(m: Model, events: Event*): Model = afterAll(m, events)

  // A `Seq` overload rather than a splice: `xs: _*` and `xs*` are each one axis's spelling.
  private def afterAll(m: Model, events: Seq[Event]): Model =
    events.foldLeft(m)((acc, e) => Update(acc, e)._1)

  private def frameOf(m: Model): Vector[String] = View.frame(m)

  private def assertSized(rows: Vector[String], w: Int, h: Int): Unit = {
    assertEquals(rows.size, h, "row count")
    rows.zipWithIndex.foreach { case (r, i) =>
      assert(width(r) <= w, s"row $i is ${width(r)} wide: '$r'")
    }
  }

  test("80x24: title and address in the header, tabs with counts, rules, pane, footer") {
    val rows = frameOf(model(80, 24))
    assertSized(rows, 80, 24)
    assert(rows(0).startsWith(" eezo dev · todo"), rows(0))
    assert(rows(0).contains("http://localhost:8090"), rows(0))
    assert(!rows(0).contains("built"), rows(0))
    assert(
      rows(1).contains("1 errors") && rows(1).contains("2 routes 4") && rows(1).contains(
        "3 schema ⚠1"
      ) && rows(1).endsWith("5 log "),
      rows(1)
    )
    assert(rows(2).forall(_ == '─'), rows(2))
    assert(rows(22).forall(_ == '─'), rows(22))
    assert(
      rows(23).contains("r restart") && rows(23).contains("s sync") && rows(23).contains("q quit"),
      rows(23)
    )
    assert(rows(23).endsWith("✓ built in 0.4s "), rows(23))
    assert(rows.exists(_.contains("serving on http://localhost:8090")))
  }

  test("the config tab counts the offenders, and is green only for a report without one") {
    val failing = frameOf(after(model(80, 24), Event.ConfigReported(refused)))(1)
    assert(failing.contains("4 config ✗2") && failing.contains("5 log"), failing)
    val passing = frameOf(after(model(80, 24), Event.ConfigReported(clean)))(1)
    assert(passing.contains("4 config ✓"), passing)
    val exited = frameOf(after(model(80, 24), Event.ConfigReported(refused), Event.AppExited(1)))(1)
    assert(exited.contains("4 config ✗2") && !exited.contains("4 config ✓"), exited)
    val narrow = frameOf(after(model(59, 12), Event.ConfigReported(refused)))(1)
    assert(narrow.contains("4 cfg ✗2"), narrow)
  }

  /** The pane's row naming `text`, from `rows`. */
  private def row(rows: Vector[String], text: String): String =
    rows.find(_.contains(text)).getOrElse(fail(s"no row with $text in\n${rows.mkString("\n")}"))

  test("the config pane is one row per variable, offending ones red, file offenders with reasons") {
    val m    = after(model(80, 24), Event.ConfigReported(refused))
    val rows = frameOf(m)
    assert(
      rows(3).contains("✗ 2 required value(s) missing; the application did not start"),
      rows(3)
    )
    List(
      ("SHOP_PORT", List("port", "required", "plain", "set")),
      ("STRIPE_KEY", List("stripe.key", "required", "redacted", "unset")),
      ("SHOP_CURRENCY", List("stripe.currency", "defaulted", "plain", "unset"))
    ).foreach { case (variable, words) =>
      val r = row(rows, variable)
      words.foreach(w => assert(r.contains(w), s"$w in $r"))
    }
    assert(!row(rows, "SHOP_PORT").contains("unset"), row(rows, "SHOP_PORT"))
    assert(row(rows, "stripe.webhook").contains("✗ stripe.webhook is stated as a literal"))
    val advice =
      rows.map(_.trim).dropWhile(_ != "e  type each missing value for this session, then restart")
    assertEquals(
      advice.take(2),
      Vector(
        "e  type each missing value for this session, then restart",
        "or quit, export the variables, and start again"
      ),
      rows.mkString("\n")
    )

    val coloured = View.render(m, Theme(color = true, unicode = true))
    val red      = "\u001b[31m"
    assert(row(coloured, "STRIPE_KEY").contains(red), row(coloured, "STRIPE_KEY"))
    assert(row(coloured, "stripe.webhook is stated").contains(red))
    assert(!row(coloured, "SHOP_PORT").contains(red), row(coloured, "SHOP_PORT"))
  }

  test("a narrow config pane keeps the variable and its state, and drops the other columns") {
    val rows = frameOf(after(model(59, 24), Event.ConfigReported(refused)))
    val r    = row(rows, "STRIPE_KEY")
    assert(r.contains("unset") && !r.contains("stripe.key") && !r.contains("redacted"), r)
  }

  test("a clean config pane says every required value is set") {
    val rows =
      frameOf(after(model(80, 24), Event.ConfigReported(clean), Event.Pressed(Key.Char('4'))))
    assert(rows(3).contains("✓ every required value is set"), rows(3))
  }

  test("a config pane for a file that names no variable says so") {
    val rows = frameOf(
      after(
        model(80, 24),
        Event.ConfigReported(ConfigReport(Vector.empty, Vector.empty)),
        Event.Pressed(Key.Char('4'))
      )
    )
    assertEquals(rows(3).trim, "application.conf names no variable")
  }

  private val two = refused.copy(offenders =
    refused.offenders :+ ConfigOffender(
      ConfigOffender.Missing,
      "stripe.currency",
      Vector("SHOP_CURRENCY", "CURRENCY"),
      Vector.empty,
      "stripe.currency is missing"
    )
  )

  private def typing(text: String): Seq[Event] = text.map(c => Event.Pressed(Key.Char(c)))

  test(
    "the prompt names the path, the variable, the other one and how many follow; masks a Redacted"
  ) {
    val asked    = after(model(80, 24), Event.ConfigReported(two), Event.Pressed(Key.Char('e')))
    val redacted = afterAll(asked, typing("sk_test"))
    val first    = frameOf(redacted)(23)
    assert(first.contains("stripe.key") && first.contains("STRIPE_KEY"), first)
    assert(first.contains("1 more follows"), first)
    assert(first.contains("•••••••") && !first.contains("sk_test"), first)
    val plain = afterAll(redacted, Event.Pressed(Key.Enter) +: typing("eur"))
    val last  = frameOf(plain)(23)
    assert(last.contains("stripe.currency") && last.contains("SHOP_CURRENCY"), last)
    assert(last.contains("or CURRENCY") && last.contains("eur"), last)
    assert(last.contains("the last one"), last)
  }

  test("the prompt row counts the paths that follow, down to the last one") {
    val three = two.copy(offenders =
      two.offenders :+ ConfigOffender(
        ConfigOffender.Missing,
        "port",
        Vector("SHOP_PORT"),
        Vector.empty,
        "port is missing"
      )
    )
    val asked  = after(model(100, 24), Event.ConfigReported(three), Event.Pressed(Key.Char('e')))
    val second = afterAll(asked, typing("sk") :+ Event.Pressed(Key.Enter))
    val last   = afterAll(second, typing("eur") :+ Event.Pressed(Key.Enter))
    assertEquals(
      frameOf(afterAll(asked, typing("sk")))(23).trim,
      "stripe.key: STRIPE_KEY ••    2 more follow; Enter sets it, Esc cancels"
    )
    assertEquals(
      frameOf(second)(23).trim,
      "stripe.currency: SHOP_CURRENCY (or CURRENCY)     1 more follows; Enter sets it, Esc cancels"
    )
    assertEquals(
      frameOf(afterAll(last, typing("80")))(23).trim,
      "port: SHOP_PORT 80    the last one; Enter sets it, Esc cancels"
    )
  }

  test("a row the console supplied reads set (session), and no row shows a value") {
    val asked = after(model(80, 24), Event.ConfigReported(two), Event.Pressed(Key.Char('e')))
    val m     = afterAll(
      asked,
      typing("sk_sentinel") ++ Seq(Event.Pressed(Key.Enter)) ++ typing("eur_sentinel") :+
        Event.Pressed(Key.Enter)
    )
    val rows = frameOf(after(m, Event.Pressed(Key.Char('4'))))
    assert(row(rows, "STRIPE_KEY").contains("set (session)"), row(rows, "STRIPE_KEY"))
    assert(row(rows, "SHOP_CURRENCY").contains("set (session)"), row(rows, "SHOP_CURRENCY"))
    assert(!row(rows, "SHOP_PORT").contains("session"), row(rows, "SHOP_PORT"))
    assert(!rows.exists(_.contains("sentinel")), rows.mkString("\n"))
  }

  test("a row the console supplied is cyan while the last report still refuses its path") {
    val asked = after(model(80, 24), Event.ConfigReported(two), Event.Pressed(Key.Char('e')))
    val first = afterAll(asked, typing("sk_sentinel") :+ Event.Pressed(Key.Enter))
    val rows  = View.render(first, Theme(color = true, unicode = true))
    val red   = "\u001b[31m"
    val cyan  = "\u001b[36m"
    val key   = row(rows, "STRIPE_KEY")
    assert(key.contains(cyan) && !key.contains(red), key)
    assert(key.contains("set (session)"), key)
    assert(row(rows, "SHOP_CURRENCY").contains(red), row(rows, "SHOP_CURRENCY"))
    assert(row(rows, "required value(s) missing").contains(red), row(rows, "required"))
  }

  test("e is in the footer while an offender takes a typed value, and in the help with the pane") {
    val refusing = after(model(80, 24), Event.ConfigReported(refused))
    assert(frameOf(refusing)(23).contains("e set"), frameOf(refusing)(23))
    val passing = after(model(80, 24), Event.ConfigReported(clean))
    assert(!frameOf(passing)(23).contains("e set"), frameOf(passing)(23))
    val helped = frameOf(after(passing, Event.Pressed(Key.Char('?'))))
    assert(helped.exists(_.contains("1-5")), helped.mkString("\n"))
    assert(helped.exists(_.contains("config")), helped.mkString("\n"))
    assertEquals(
      helped.find(_.startsWith("   e ")).map(_.trim.drop(1).trim),
      Some("type the values the config pane asks for, then restart"),
      helped.mkString("\n")
    )
    val without = frameOf(after(model(80, 24), Event.Pressed(Key.Char('?'))))
    assert(!without.exists(_.startsWith("   e ")), without.mkString("\n"))
  }

  test("the footer's right end follows the build: spinner and trigger, then the verdict") {
    val (compiling, _) = Update(model(80, 24), Event.Changed(Set("Index.scala")))
    val busy           = frameOf(compiling)(23)
    assert(busy.contains("compiling (Index.scala)"), busy)
    val (failed, _) = Update(compiling, Event.BuildFailed(Vector(error), 300L))
    assert(frameOf(failed)(23).endsWith("✗ 1 error "), frameOf(failed)(23))
  }

  test("200x50 is the same layout, wider and taller") {
    val rows = frameOf(model(200, 50))
    assertSized(rows, 200, 50)
    assertEquals(width(rows(0)), 200)
    assert(rows(49).contains("f freeze"))
  }

  test("59x12: narrow tabs with short names, footer keeps only the edges") {
    val rows = frameOf(model(59, 12))
    assertSized(rows, 59, 12)
    assert(
      rows(1).contains("1 err") && rows(1).contains("2 rt 4") && rows(1).contains("3 db"),
      rows(1)
    )
    assert(
      rows(11).contains("r restart") && rows(11).contains("q quit") && !rows(11).contains("s sync"),
      rows(11)
    )
  }

  test("40x11 is compact: header and tabs on one row, no rules, no footer") {
    val rows = frameOf(model(40, 11))
    assertSized(rows, 40, 11)
    assert(rows(0).contains("5 log"), rows(0))
    assert(!rows.exists(r => r.nonEmpty && r.forall(_ == '─')), "no rule rows")
    assert(!rows(10).contains("quit"), rows(10))
  }

  test("80x5 is tiny: one status row and nothing else") {
    val rows = frameOf(model(80, 5))
    assertSized(rows, 80, 5)
    assert(rows(0).contains("eezo dev") && rows(0).contains("built"), rows(0))
    assert(rows.drop(1).forall(_.isEmpty))
  }

  private val error = Diagnostic(
    "src/main/scala/app/Index.scala",
    12,
    5,
    Severity.Error,
    "not found: value x",
    Some("12 |  x\n   |  ^\n   |  not found: value x")
  )

  test("the errors pane shows the diagnostic header and the compiler's own rendering") {
    val (m, _) = Update(model(80, 24), Event.BuildFailed(Vector(error), 300L))
    val rows   = frameOf(m)
    assert(rows(23).contains("✗ 1 error"), rows(23))
    assert(rows(1).contains("1 errors ✗1"), rows(1))
    assert(rows(3).contains("✗ src/main/scala/app/Index.scala:12:5"), rows(3))
    assert(rows(4).contains("12 |  x"), rows(4))
    assert(rows(6).contains("not found: value x"), rows(6))
  }

  test("the routes pane is an aligned table, framework routes marked, the orphan warning after") {
    val (m, _) = Update(model(80, 24), Event.Pressed(Key.Char('2')))
    val rows   = frameOf(m)
    assert(rows(3).contains("4 routes"), rows(3))
    assert(rows(4).startsWith(" GET   /  "), rows(4))
    assert(rows(5).startsWith(" POST  /todos"), rows(5))
    assert(rows(6).contains("/api/todos") && rows(6).contains("api"), rows(6))
    assert(rows(7).contains("/eezo/live.js") && rows(7).endsWith("eezo"), rows(7))
    assert(!rows(7).contains("handwritten"), rows(7))
    assert(rows(9).contains("⚠ new at /books/new submits to create at /books"), rows(9))
  }

  test("the schema pane lists the drift with its marks and the keys that act on it") {
    val (m, _) = Update(model(80, 24), Event.Pressed(Key.Char('3')))
    val rows   = frameOf(m)
    assert(rows(3).contains("⚠ 1 additive difference(s); serving anyway"), rows(3))
    assert(rows(4).contains("+ todo.priority integer null"), rows(4))
    assert(rows.exists(_.contains("s  apply")), rows.mkString("\n"))
  }

  test("a command's answer is one line in the schema pane; its output is in the log") {
    val applied = JsonReader
      .parse("""{"command":"sync","applied":true,"changes":[{"describe":"+ todo.priority"}]}""")
      .toOption
    val outcome = Outcome(
      SchemaCommand.Sync(force = false),
      0,
      applied,
      Vector("{...}"),
      Vector(
        "Oct 29, 2026 11:45:04 AM com.zaxxer.hikari.HikariDataSource <init>",
        "INFO: eezo - Starting...",
        "WARNING: something real"
      )
    )
    val (m, _) = Seq[Event](
      Event.Pressed(Key.Char('3')),
      Event.CommandStarted(outcome.command),
      Event.CommandFinished(outcome)
    ).foldLeft((model(80, 24), Vector.empty[Effect])) { case ((acc, _), e) => Update(acc, e) }
    val rows = frameOf(m)
    assert(rows(9).contains("✓ eezo db sync --apply: applied to the dev database"), rows(9))
    assert(rows(9).contains("output in 5 log"), rows(9))
    assert(!rows.exists(_.contains("HikariDataSource")), rows.mkString("\n"))
    assert(m.log.exists(_.text == "$ eezo db sync --apply"))
    assert(m.log.exists(_.text == "  + todo.priority"))
    assert(m.log.exists(l => l.text == "WARNING: something real" && l.kind == LogLine.Err))
    assert(!m.log.exists(_.text.contains("HikariDataSource")))
    val (running, _) = Update(m, Event.CommandStarted(SchemaCommand.Status))
    assert(frameOf(running)(23).contains("⠋ db status"), frameOf(running)(23))
  }

  test("a prompt takes the last row, in every layout") {
    val (asked, _) = Update(
      model(80, 24).copy(schema =
        SchemaState.Known(
          Drift(Vector(Change("- x", destructive = true, risky = false, "")), blocking = true)
        )
      ),
      Event.Pressed(Key.Char('s'))
    )
    assert(frameOf(asked)(23).contains("Enter applies with --force"), frameOf(asked)(23))
    val (compact, _) = Update(asked.copy(width = 40, height = 11), Event.Resized(40, 11))
    assert(frameOf(compact)(10).contains("--force"), frameOf(compact)(10))
  }

  test("the help overlay replaces the pane") {
    val (m, _) = Update(model(80, 24), Event.Pressed(Key.Char('?')))
    val rows   = frameOf(m)
    assert(rows(3).contains("keys"), rows(3))
    assert(rows.exists(_.contains("sync: apply the drift")))
  }

  test("colour on changes no row's width") {
    val plain    = frameOf(model(80, 24))
    val coloured = View.render(model(80, 24), Theme(color = true, unicode = true))
    assertEquals(coloured.map(width), plain.map(width))
    assert(coloured.exists(_.contains("\u001b[")))
  }

  test("ascii theme draws without box glyphs") {
    val rows = View.render(model(80, 24), Theme(color = false, unicode = false))
    assert(rows(2).forall(_ == '-'), rows(2))
    assert(rows(23).contains("ok built"), rows(23))
  }
}
