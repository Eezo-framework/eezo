package io.eezo.sbt.dev

class JsonRendererSuite extends munit.FunSuite {

  /** Runs the events through `Update` the way the loop does and keeps what the renderer printed. */
  private def render(events: Event*): Vector[JValue] = {
    val renderer = new JsonRenderer
    var model    = Model.initial("todo", 80, 24, 0L)
    events.toVector.flatMap { e =>
      val (next, effects) = Update(model, e)
      val lines           = renderer.lines(e, next, effects)
      model = next
      lines.map(l => JsonReader.parse(l).fold(err => fail(s"$err in $l"), identity))
    }
  }

  private val error = Diagnostic(
    "src/main/scala/app/Index.scala",
    7,
    3,
    Severity.Error,
    "Not found: foo",
    Some("-- [E006] Not Found Error\n7 |  foo\n  |  ^^^")
  )

  test("a save is compiling, then built, under one build number; a second save is the next") {
    val out = render(
      Event.Changed(Set("src/main/scala/app/Index.scala")),
      Event.BuildSucceeded(Vector.empty, 400L),
      Event.AppStarted("Main"),
      Event.Serving("http://localhost:8080"),
      Event.Changed(Set("src/main/scala/app/Health.scala")),
      Event.BuildFailed(Vector(error), 120L)
    )
    assertEquals(
      out.map(_("event").strOr("?")),
      Vector("compiling", "built", "started", "serving", "compiling", "failed")
    )
    assertEquals(out(0)("build").num, Some(1L))
    assertEquals(out(0)("trigger").str, Some("Index.scala"))
    assertEquals(out(1)("build").num, Some(1L))
    assertEquals(out(1)("millis").num, Some(400L))
    assertEquals(out(4)("build").num, Some(2L))
    assertEquals(out(5)("build").num, Some(2L))
    val d = out(5)("diagnostics").arr.head
    assertEquals(d("path").str, Some("src/main/scala/app/Index.scala"))
    assertEquals(d("line").num, Some(7L))
    assertEquals(d("severity").str, Some("error"))
    assert(d("rendered").strOr("").contains("^^^"))
  }

  test("the application's lines, its exit and the markers are events with the values inside") {
    val listing = RouteListing(
      Vector(
        RouteRow("GET", "/", "handwritten", api = false),
        RouteRow("GET", "/eezo/live.js", "handwritten", api = false)
      ),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
    val drift = Drift(
      Vector(
        Change("drop column todo.title", destructive = true, risky = false, "ALTER TABLE ...")
      ),
      blocking = true
    )
    val out = render(
      Event.AppLine("hello \"quoted\" \t tab", err = false),
      Event.AppLine("WARNING: something", err = true),
      Event.RoutesReported(listing),
      Event.DriftReported(drift),
      Event.AppExited(1)
    )
    assertEquals(
      out.map(_("event").strOr("?")),
      Vector("line", "line", "routes", "drift", "exited")
    )
    assertEquals(out(0)("text").str, Some("hello \"quoted\" \t tab"))
    assertEquals(out(0)("stream").str, Some("stdout"))
    assertEquals(out(1)("stream").str, Some("stderr"))
    assertEquals(
      out(2)("routes").arr.map(_("provenance").strOr("?")),
      Vector("handwritten", "eezo")
    )
    assertEquals(out(3)("blocking").bool, Some(true))
    assertEquals(out(3)("changes").arr.head("destructive").bool, Some(true))
    assertEquals(out(4)("code").num, Some(1L))
  }

  test("a schema command is two events, the second carrying the command's own answer") {
    val answer =
      JsonReader.parse("""{"command":"sync","outcome":"applied"}""").fold(fail(_), identity)
    val out = render(
      Event.ActionStarted(Action.Sync(force = false)),
      Event.ActionFinished(
        Outcome(Action.Sync(force = false), 0, Some(answer), Vector.empty, Vector.empty)
      )
    )
    assertEquals(out.map(_("status").strOr("?")), Vector("started", "finished"))
    assertEquals(out(1)("command").str, Some("db sync --apply"))
    assertEquals(out(1)("ok").bool, Some(true))
    assertEquals(out(1)("result")("outcome").str, Some("applied"))
  }

  test("a diagnostic's colour codes are stripped; the compiler paints, a program reads") {
    val painted = error.copy(
      message = "Found:    (\u001b[32m\"no\"\u001b[0m : String)",
      rendered = Some("\u001b[31m-- Error\u001b[0m\n  |  ^^^")
    )
    val out = render(Event.Changed(Set("a.scala")), Event.BuildFailed(Vector(painted), 1L))
    val d   = out(1)("diagnostics").arr.head
    assertEquals(d("message").str, Some("Found:    (\"no\" : String)"))
    assertEquals(d("rendered").str, Some("-- Error\n  |  ^^^"))
  }

  test("keys, ticks and resizes render nothing") {
    assertEquals(
      render(Event.Tick(5L), Event.Pressed(Key.Char('j')), Event.Resized(100, 30)),
      Vector.empty
    )
  }

  test("the writer round trips through the reader, control characters included") {
    val value = JObj(
      Vector(
        "s" -> JStr("a\"b\\c\nd\u0001e"),
        "n" -> JNum(-42L),
        "b" -> JBool(false),
        "z" -> JNull,
        "a" -> JArr(Vector(JNum(1L), JStr("")))
      )
    )
    assertEquals(JsonReader.parse(JsonWriter.write(value)), Right(value))
  }
}
