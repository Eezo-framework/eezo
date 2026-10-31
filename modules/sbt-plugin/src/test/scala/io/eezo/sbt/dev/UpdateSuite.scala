package io.eezo.sbt.dev

/** The console's state machine, driven by events alone: no terminal, no sbt, no process. */
class UpdateSuite extends munit.FunSuite {

  private val start: Model = Model.initial("todo", 80, 24, now = 1000L)

  private def run(m: Model, events: Event*): (Model, Vector[Effect]) = runAll(m, events)

  // A `Seq` overload rather than a splice: `xs: _*` and `xs*` are each one axis's spelling.
  private def runAll(m: Model, events: Seq[Event]): (Model, Vector[Effect]) =
    events.foldLeft((m, Vector.empty[Effect])) { case ((model, effects), e) =>
      val (next, more) = Update(model, e)
      (next, effects ++ more)
    }

  private val error = Diagnostic(
    "src/main/scala/app/Index.scala",
    12,
    5,
    Severity.Error,
    "not found: value x",
    Some("rendered")
  )

  private val additive = Drift(
    Vector(Change("+ todo.priority integer null", destructive = false, risky = false, "")),
    blocking = false
  )
  private val blocking =
    Drift(Vector(Change("- todo.notes", destructive = true, risky = false, "")), blocking = true)

  test("a change starts a build; one during a build is remembered and runs after it") {
    val (compiling, effects) = run(start, Event.Changed(Set("src/main/scala/app/Index.scala")))
    assertEquals(effects, Vector(Effect.Build))
    assertEquals(compiling.build, BuildState.Compiling(Some("Index.scala"), 1000L))

    val (dirty, none) = run(compiling, Event.Changed(Set("Other.scala")))
    assertEquals(none, Vector.empty[Effect])
    assert(dirty.dirty)

    val (built, after) = run(dirty, Event.BuildSucceeded(Vector.empty, 300L))
    assertEquals(after, Vector(Effect.Restart, Effect.Build))
    assert(!built.dirty)
    assertEquals(built.build, BuildState.Built(300L, Vector.empty))
  }

  test("a failed build restarts nothing, opens the errors pane, and keeps the app") {
    val running           = start.copy(app = AppState.Running(Some("http://localhost:8090")))
    val (failed, effects) =
      run(running, Event.BuildStarted(None), Event.BuildFailed(Vector(error), 200L))
    assertEquals(effects, Vector.empty[Effect])
    assertEquals(failed.tab, Tab.Errors)
    assertEquals(failed.errors, Vector(error))
    assertEquals(failed.app, AppState.Running(Some("http://localhost:8090")))
    assert(failed.log.exists(_.text.contains("previous build keeps serving")))
  }

  test("the application's boot lines fill the panes") {
    val listing = RouteListing(
      Vector(RouteRow("GET", "/", "file", api = false)),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
    val (m, _) = run(
      start,
      Event.AppStarted("Main"),
      Event.RoutesReported(listing),
      Event.DriftReported(additive),
      Event.AppLine("3 routes:", err = false),
      Event.Serving("http://localhost:8090")
    )
    assertEquals(m.routes, Some(listing))
    assertEquals(m.schema, SchemaState.Known(additive))
    assertEquals(m.app, AppState.Running(Some("http://localhost:8090")))
    assertEquals(m.tab, Tab.Log)
    assert(m.log.exists(_.text == "3 routes:"))
  }

  test("blocking drift switches to the schema pane and says so") {
    val (m, _) = run(start, Event.DriftReported(blocking))
    assertEquals(m.tab, Tab.Schema)
    assert(m.log.exists(l => l.kind == LogLine.Fail && l.text.contains("drift page")))
  }

  test("s on dangerous drift asks before forcing; Enter runs sync --force; Esc cancels") {
    val (m, _)        = run(start, Event.DriftReported(blocking))
    val (asked, none) = run(m, Event.Pressed(Key.Char('s')))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(asked.prompt, Some(Prompt.ConfirmForce(1)))
    val (_, forced) = run(asked, Event.Pressed(Key.Enter))
    assertEquals(forced, Vector(Effect.Run(Action.Sync(force = true))))
    val (cancelled, nothing) = run(asked, Event.Pressed(Key.Escape))
    assertEquals(nothing, Vector.empty[Effect])
    assertEquals(cancelled.prompt, None)
  }

  test("s on additive drift syncs at once, and an applied sync restarts the app") {
    val (m, effects) = run(start, Event.DriftReported(additive), Event.Pressed(Key.Char('s')))
    assertEquals(effects, Vector(Effect.Run(Action.Sync(force = false))))
    val applied = JsonReader
      .parse(
        """{"command":"sync","applied":true,"refused":false,"changes":[{"describe":"+ x"}],"blocked":[]}"""
      )
      .toOption
    val (after, more) = run(
      m,
      Event.ActionStarted(Action.Sync(force = false)),
      Event.ActionFinished(
        Outcome(Action.Sync(force = false), 0, applied, Vector.empty, Vector.empty)
      )
    )
    assertEquals(more, Vector(Effect.Restart))
    assertEquals(after.busy, None)
  }

  test("a refused unforced sync becomes the force question") {
    val refused = JsonReader
      .parse(
        """{"command":"sync","applied":false,"refused":true,"changes":[{"describe":"- x","destructive":true}],"blocked":[{"describe":"- x","destructive":true}]}"""
      )
      .toOption
    val (m, effects) = run(
      start,
      Event.ActionFinished(
        Outcome(Action.Sync(force = false), 1, refused, Vector.empty, Vector.empty)
      )
    )
    assertEquals(effects, Vector.empty[Effect])
    assertEquals(m.prompt, Some(Prompt.ConfirmForce(1)))
  }

  test(
    "f asks for a name, then a policy when the drift is destructive, then freezes and migrates"
  ) {
    val (m, _) = run(start, Event.DriftReported(blocking), Event.Pressed(Key.Char('f')))
    assertEquals(m.prompt, Some(Prompt.FreezeName("")))
    val (typed, _) = runAll(m, "drop notes".map(c => Event.Pressed(Key.Char(c))))
    assertEquals(typed.prompt, Some(Prompt.FreezeName("drop notes")))
    val (fixed, _) = run(typed, Event.Pressed(Key.Backspace), Event.Pressed(Key.Char('s')))
    assertEquals(fixed.prompt, Some(Prompt.FreezeName("drop notes")))
    val (policy, none) = run(fixed, Event.Pressed(Key.Enter))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(policy.prompt, Some(Prompt.FreezePolicy("drop notes", 1)))
    val (_, keep) = run(policy, Event.Pressed(Key.Char('k')))
    assertEquals(keep, Vector(Effect.Run(Action.Freeze("drop notes", dropAll = false))))
    val (_, drop) = run(policy, Event.Pressed(Key.Char('d')))
    assertEquals(drop, Vector(Effect.Run(Action.Freeze("drop notes", dropAll = true))))

    val wrote = JsonReader
      .parse(
        """{"command":"freeze","migration":"db/migrations/0002_drop_notes.sql","resolutions":[]}"""
      )
      .toOption
    val (_, next) = run(
      policy.copy(prompt = None),
      Event.ActionFinished(
        Outcome(Action.Freeze("drop notes", dropAll = true), 0, wrote, Vector.empty, Vector.empty)
      )
    )
    assertEquals(next, Vector(Effect.Run(Action.Migrate(apply = true))))
    val applied = JsonReader
      .parse(
        """{"command":"migrate","outcome":"applied","applied":[{"number":2,"file":"0002_drop_notes.sql","statements":[]}],"drift":[]}"""
      )
      .toOption
    val (_, last) = run(
      policy.copy(prompt = None),
      Event.ActionFinished(
        Outcome(Action.Migrate(apply = true), 0, applied, Vector.empty, Vector.empty)
      )
    )
    assertEquals(last, Vector(Effect.Restart))
  }

  test("an empty freeze name is not submitted, and additive drift skips the policy question") {
    val (m, none) = run(
      start,
      Event.DriftReported(additive),
      Event.Pressed(Key.Char('f')),
      Event.Pressed(Key.Enter)
    )
    assertEquals(none, Vector.empty[Effect])
    assertEquals(m.prompt, Some(Prompt.FreezeName("")))
    val (_, effects) = run(m, Event.Pressed(Key.Char('x')), Event.Pressed(Key.Enter))
    assertEquals(effects, Vector(Effect.Run(Action.Freeze("x", dropAll = false))))
  }

  test("m lists pending migrations, Enter applies them") {
    val (_, effects) = run(start, Event.Pressed(Key.Char('m')))
    assertEquals(effects, Vector(Effect.Run(Action.Migrate(apply = false))))
    val pending = JsonReader
      .parse(
        """{"command":"migrate","outcome":"pending","pending":[{"number":2,"file":"0002_x.sql","statements":["x"]}]}"""
      )
      .toOption
    val (asked, _) = run(
      start,
      Event.ActionFinished(
        Outcome(Action.Migrate(apply = false), 0, pending, Vector.empty, Vector.empty)
      )
    )
    assertEquals(asked.prompt, Some(Prompt.ConfirmMigrate(1)))
    val (_, apply) = run(asked, Event.Pressed(Key.Enter))
    assertEquals(apply, Vector(Effect.Run(Action.Migrate(apply = true))))
  }

  test("an application without a database layer loses the schema keys after the first answer") {
    val (m, _) = run(
      start,
      Event.ActionFinished(
        Outcome(Action.Status, 2, None, Vector.empty, Vector("[eezo] unknown command: db"))
      )
    )
    assertEquals(m.schema, SchemaState.NoDatabase)
    assert(!m.hasDatabase)
    val (_, effects) = run(
      m,
      Event.Pressed(Key.Char('s')),
      Event.Pressed(Key.Char('f')),
      Event.Pressed(Key.Char('m'))
    )
    assertEquals(effects, Vector.empty[Effect])
  }

  test("S reads the status back into the schema pane, drift or not") {
    val drifted = JsonReader
      .parse(
        """{"command":"status","inSync":false,"changes":[{"describe":"- todo.notes","destructive":true,"risky":false,"sql":""}]}"""
      )
      .toOption
    val (m, _) = run(
      start,
      Event.ActionFinished(Outcome(Action.Status, 1, drifted, Vector.empty, Vector.empty))
    )
    assertEquals(m.schema, SchemaState.Known(blocking))
  }

  test("a change during an action waits for it") {
    val (busy, none) = run(start, Event.ActionStarted(Action.Status), Event.Changed(Set("A.scala")))
    assertEquals(none, Vector.empty[Effect])
    assert(busy.dirty)
    val (_, effects) = run(
      busy,
      Event.ActionFinished(
        Outcome(
          Action.Status,
          0,
          JsonReader.parse("""{"inSync":true,"changes":[]}""").toOption,
          Vector.empty,
          Vector.empty
        )
      )
    )
    assertEquals(effects, Vector(Effect.Build))
  }

  test("tabs by number and by Tab, help by ?, quit by q and Ctrl-C") {
    val (m, _) = run(start, Event.Pressed(Key.Char('2')))
    assertEquals(m.tab, Tab.Routes)
    val (cycled, _) = run(m, Event.Pressed(Key.Tab), Event.Pressed(Key.Tab))
    assertEquals(cycled.tab, Tab.Log)
    val (back, _) = run(cycled, Event.Pressed(Key.BackTab))
    assertEquals(back.tab, Tab.Schema)
    val (helped, _) = run(start, Event.Pressed(Key.Char('?')))
    assert(helped.help)
    val (closed, _) = run(helped, Event.Pressed(Key.Char('x')))
    assert(!closed.help)
    assertEquals(run(start, Event.Pressed(Key.Char('q')))._2, Vector(Effect.Quit))
    assertEquals(run(start, Event.Pressed(Key.CtrlC))._2, Vector(Effect.Quit))
  }

  test("the log follows its tail until scrolled up, and G follows again") {
    val lines  = (1 to 100).map(i => Event.AppLine(s"line $i", err = false))
    val (m, _) = runAll(start, lines)
    assert(m.follow)
    val pane = Layout(80, 24).paneHeight
    assertEquals(Update.effectiveOffset(m), 100 - pane)
    val (up, _) = run(m, Event.Pressed(Key.Up))
    assert(!up.follow)
    assertEquals(Update.effectiveOffset(up), 100 - pane - 1)
    val (paged, _) = runAll(up, Vector.fill(6)(Event.Pressed(Key.PageUp)))
    assertEquals(Update.effectiveOffset(paged), 0)
    val (down, _) = run(paged, Event.Pressed(Key.Char('G')))
    assert(down.follow)
    assertEquals(Update.effectiveOffset(down), 100 - pane)
  }

  test("a resize clamps the scroll offsets") {
    val lines       = (1 to 30).map(i => Event.AppLine(s"line $i", err = false))
    val (m, _)      = runAll(start, lines)
    val (up, _)     = run(m, Event.Pressed(Key.Up), Event.Pressed(Key.Up))
    val (taller, _) = run(up, Event.Resized(80, 60))
    assertEquals(Update.effectiveOffset(taller), 0)
    assertEquals(taller.width, 80)
    assertEquals(taller.height, 60)
  }

  test("the log keeps the last two thousand lines") {
    val lines  = (1 to 2500).map(i => Event.AppLine(s"line $i", err = false))
    val (m, _) = runAll(start, lines)
    assertEquals(m.log.size, LogLine.Keep)
    assertEquals(m.log.head.text, "line 501")
  }

  test("r rebuilds and restarts; c clears the log; the app's exit is noted") {
    val (_, effects) = run(start, Event.Pressed(Key.Char('r')))
    assertEquals(effects, Vector(Effect.Build))
    val (m, _) = run(start, Event.AppLine("x", err = false), Event.Pressed(Key.Char('c')))
    assertEquals(m.log, Vector.empty[LogLine])
    val (exited, _) = run(start, Event.AppExited(1))
    assertEquals(exited.app, AppState.Exited(1))
    assert(exited.log.exists(l => l.kind == LogLine.Fail && l.text.contains("exited with code 1")))
  }
}
