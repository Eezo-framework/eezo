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
      Event.BuildStarted(Some("Index.scala")),
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
      ) && rows(1).endsWith("4 log "),
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

  test("the footer's right end follows the build: spinner and trigger, then the verdict") {
    val (compiling, _) = Update(model(80, 24), Event.BuildStarted(Some("Index.scala")))
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
    assert(rows(0).contains("4 log"), rows(0))
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
      Action.Sync(force = false),
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
      Event.ActionStarted(outcome.action),
      Event.ActionFinished(outcome)
    ).foldLeft((model(80, 24), Vector.empty[Effect])) { case ((acc, _), e) => Update(acc, e) }
    val rows = frameOf(m)
    assert(rows(9).contains("✓ eezo db sync --apply: applied to the dev database"), rows(9))
    assert(rows(9).contains("output in 4 log"), rows(9))
    assert(!rows.exists(_.contains("HikariDataSource")), rows.mkString("\n"))
    assert(m.log.exists(_.text == "$ eezo db sync --apply"))
    assert(m.log.exists(_.text == "  + todo.priority"))
    assert(m.log.exists(l => l.text == "WARNING: something real" && l.kind == LogLine.Err))
    assert(!m.log.exists(_.text.contains("HikariDataSource")))
    val (running, _) = Update(m, Event.ActionStarted(Action.Status))
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
