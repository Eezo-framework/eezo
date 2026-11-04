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

  private val port = ConfigRow("SHOP_PORT", "port", redacted = false, "required", set = false)
  private val key  = ConfigRow("STRIPE_KEY", "stripe.key", redacted = true, "required", set = false)
  private val other =
    ConfigRow("STRIPE_SECRET", "stripe.key", redacted = true, "required", set = false)

  private def missing(path: String, variables: String*): ConfigOffender =
    ConfigOffender(
      ConfigOffender.Missing,
      path,
      variables.toVector,
      Vector.empty,
      s"$path is missing"
    )

  private val clean   = ConfigReport(Vector(port, key), Vector.empty)
  private val refused = ConfigReport(
    Vector(port, key, other),
    Vector(missing("port", "SHOP_PORT"), missing("stripe.key", "STRIPE_KEY", "STRIPE_SECRET"))
  )

  test("a config report with an offender opens the config pane; a clean one does not") {
    val (opened, effects) = run(start, Event.ConfigReported(refused))
    assertEquals(opened.tab, Tab.Config)
    assertEquals(opened.config, Some(refused))
    assertEquals(effects, Vector.empty[Effect])
    val (stayed, _) = run(start, Event.ConfigReported(clean))
    assertEquals(stayed.tab, Tab.Log)
    assertEquals(stayed.config, Some(clean))
  }

  test("a refusal's report and its exit give one model in either order, from two threads") {
    val (starting, _) = run(start, Event.AppStarted("Main"), Event.Pressed(Key.Char('2')))
    val (first, _)    = run(starting, Event.ConfigReported(refused), Event.AppExited(1))
    val (second, _)   = run(starting, Event.AppExited(1), Event.ConfigReported(refused))
    assertEquals(first, second)
    assertEquals(first.tab, Tab.Config)
    assertEquals(first.app, AppState.Exited(1))
  }

  test("e walks the paths a typed value fixes: the path, the variable, the other one, the rest") {
    val (reported, _) = run(start, Event.ConfigReported(refused))
    val (asked, none) = run(reported, Event.Pressed(Key.Char('e')))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(
      asked.prompt,
      Some(
        Prompt.Supply(
          Vector(
            Ask("port", "SHOP_PORT", None, redacted = false),
            Ask("stripe.key", "STRIPE_KEY", Some("STRIPE_SECRET"), redacted = true)
          ),
          Typed.empty
        )
      )
    )
  }

  private def typing(text: String): Seq[Event] = text.map(c => Event.Pressed(Key.Char(c)))

  test("Enter holds what was typed and asks the next path; Enter on the last one restarts") {
    val (asked, _)   = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val (next, none) =
      runAll(asked, typing("80800") ++ Seq(Event.Pressed(Key.Backspace), Event.Pressed(Key.Enter)))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(next.supplied, Map("SHOP_PORT" -> Typed("8080")))
    assertEquals(
      next.prompt.collect { case p: Prompt.Supply => p.asks.map(_.path) },
      Some(Vector("stripe.key"))
    )
    val (done, restart) = runAll(next, typing("sk_test") :+ Event.Pressed(Key.Enter))
    assertEquals(restart, Vector[Effect](Effect.Restart))
    assertEquals(done.prompt, None)
    assertEquals(done.supplied, Map("SHOP_PORT" -> Typed("8080"), "STRIPE_KEY" -> Typed("sk_test")))
    assert(
      done.log.exists(_.text == "the values typed here last for this session only; restarting"),
      done.log.toString
    )
  }

  test("what the prompt took reaches the environment of the next fork, over an inherited value") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val (done, _)  = runAll(
      asked,
      typing("8080") ++ Seq(Event.Pressed(Key.Enter)) ++ typing("sk_test") :+ Event.Pressed(
        Key.Enter
      )
    )
    val env = new java.util.HashMap[String, String]
    env.put("SHOP_PORT", "eighty")
    io.eezo.sbt.DevProcess.environment(env, piped = true, Loop.supplied(done))
    assertEquals(env.get("SHOP_PORT"), "8080")
    assertEquals(env.get("STRIPE_KEY"), "sk_test")
  }

  test("Esc closes the prompt, keeps what Enter held, and restarts nothing") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val (held, _)  = runAll(asked, typing("8080") ++ Seq(Event.Pressed(Key.Enter)) ++ typing("sk"))
    val (closed, effects) = run(held, Event.Pressed(Key.Escape))
    assertEquals(effects, Vector.empty[Effect])
    assertEquals(closed.prompt, None)
    assertEquals(closed.supplied, Map("SHOP_PORT" -> Typed("8080")))
  }

  /** A malformed path: `variables` are the ones the file names for it, `feeding` the ones the
    * report says fed the value boot refused.
    */
  private def malformed(
      path: String,
      variables: Seq[String],
      feeding: Seq[String]
  ): ConfigOffender =
    ConfigOffender(
      ConfigOffender.Malformed,
      path,
      variables.toVector,
      feeding.toVector,
      s"$path is not a number"
    )

  private val shopPort  = port.copy(set = true)
  private val plainPort = ConfigRow("PORT", "port", redacted = false, "required", set = true)

  test("a malformed value is asked of the variable the report says fed it, naming the other one") {
    val bad = ConfigReport(
      Vector(shopPort, plainPort),
      Vector(malformed("port", Seq("SHOP_PORT", "PORT"), Seq("PORT")))
    )
    val (asked, _) = run(start, Event.ConfigReported(bad), Event.Pressed(Key.Char('e')))
    assertEquals(
      asked.prompt,
      Some(
        Prompt.Supply(Vector(Ask("port", "PORT", Some("SHOP_PORT"), redacted = false)), Typed.empty)
      )
    )
    val (retyped, restart) = runAll(asked, typing("8080") :+ Event.Pressed(Key.Enter))
    assertEquals(restart, Vector[Effect](Effect.Restart))
    assertEquals(retyped.supplied, Map("PORT" -> Typed("8080")))
  }

  test("a malformed value whose report names nothing feeding it is asked of the first variable") {
    val older = ConfigReport(
      Vector(shopPort, plainPort),
      Vector(malformed("port", Seq("SHOP_PORT", "PORT"), Nil))
    )
    val (asked, _) = run(start, Event.ConfigReported(older), Event.Pressed(Key.Char('e')))
    assertEquals(
      asked.prompt.collect { case p: Prompt.Supply => p.asks },
      Some(Vector(Ask("port", "SHOP_PORT", Some("PORT"), redacted = false)))
    )
  }

  test("bytes of a UTF-8 value typed in the prompt reach the child as the text typed") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val decoder    = new KeyDecoder
    val keys = Seq(0x63, 0x61, 0x66, 0xc3, 0xa9).flatMap(decoder.feed(_)).map(Event.Pressed(_))
    val (typed, _) = runAll(asked, keys :+ Event.Pressed(Key.Enter))
    assertEquals(
      typed.supplied.map { case (k, v) => k -> v.reveal },
      Map("SHOP_PORT" -> "caf\u00e9")
    )
  }

  test("Backspace drops a whole character, a surrogate pair included") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val decoder    = new KeyDecoder
    val keys       = (Seq('a'.toInt, 0xf0, 0x9f, 0x98, 0x80)
      .flatMap(decoder.feed(_)))
      .map(Event.Pressed(_))
    val (typed, _) = runAll(asked, keys :+ Event.Pressed(Key.Backspace))
    val shown      = typed.prompt.collect { case Prompt.Supply(_, t) => t.reveal }
    assertEquals(shown, Some("a"))
  }

  private val joined = ConfigReport(
    Vector(ConfigRow("PREFIX", "port", redacted = false, "required", set = true), plainPort),
    Vector(malformed("port", Seq("PREFIX", "PORT"), Seq("PREFIX", "PORT")))
  )

  test("a malformed concatenation asks every variable the report says feeds it, in its order") {
    val (asked, _) = run(start, Event.ConfigReported(joined), Event.Pressed(Key.Char('e')))
    assertEquals(
      asked.prompt,
      Some(
        Prompt.Supply(
          Vector(
            Ask("port", "PREFIX", None, redacted = false),
            Ask("port", "PORT", None, redacted = false)
          ),
          Typed.empty
        )
      )
    )
    val keys = typing("80") ++ Seq(Event.Pressed(Key.Enter)) ++ typing("81") ++
      Seq(Event.Pressed(Key.Enter))
    val (typed, restart) = runAll(asked, keys)
    assertEquals(restart, Vector[Effect](Effect.Restart))
    assertEquals(typed.supplied, Map("PREFIX" -> Typed("80"), "PORT" -> Typed("81")))
  }

  test("Enter on an empty value does nothing, a path asked of several variables included") {
    for (report <- Seq(refused, joined)) {
      val (asked, _)      = run(start, Event.ConfigReported(report), Event.Pressed(Key.Char('e')))
      val (same, effects) = run(asked, Event.Pressed(Key.Enter))
      assertEquals(same, asked)
      assertEquals(effects, Vector.empty[Effect])
    }
  }

  test("a malformed value the console supplied is asked again, and the retyped one replaces it") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val (wrong, _) =
      runAll(asked, typing("eighty") ++ Seq(Event.Pressed(Key.Enter), Event.Pressed(Key.Escape)))
    assertEquals(wrong.supplied, Map("SHOP_PORT" -> Typed("eighty")))
    val bad =
      ConfigReport(
        Vector(port.copy(set = true), key),
        Vector(malformed("port", Seq("SHOP_PORT"), Seq("SHOP_PORT")))
      )
    val (again, _) = run(wrong, Event.ConfigReported(bad), Event.Pressed(Key.Char('e')))
    assertEquals(
      again.prompt,
      Some(Prompt.Supply(Vector(Ask("port", "SHOP_PORT", None, redacted = false)), Typed.empty))
    )
    val (fixed, restart) = runAll(again, typing("8080") :+ Event.Pressed(Key.Enter))
    assertEquals(restart, Vector[Effect](Effect.Restart))
    assertEquals(fixed.supplied, Map("SHOP_PORT" -> Typed("8080")))
  }

  test("no toString prints a typed value: not the holder's, the prompt's or the model's") {
    val (asked, _) = run(start, Event.ConfigReported(refused), Event.Pressed(Key.Char('e')))
    val (m, _)     =
      runAll(asked, typing("sentinel1") ++ Seq(Event.Pressed(Key.Enter)) ++ typing("sentinel2"))
    assertEquals(m.supplied.keySet, Set("SHOP_PORT"))
    val prompt = m.prompt.getOrElse(fail("no prompt"))
    List(Typed("sentinel0").toString, prompt.toString, m.toString).foreach { text =>
      assert(!text.contains("sentinel"), text)
    }
  }

  test("no config report, no config pane and no e") {
    assert(!start.tabs.contains(Tab.Config), start.tabs.toString)
    val (pressed, effects) = run(start, Event.Pressed(Key.Char('e')), Event.Pressed(Key.Char('4')))
    assertEquals(effects, Vector.empty[Effect])
    assertEquals(pressed.prompt, None)
    assertEquals(pressed.tab, Tab.Log)
    val (clean, _) = run(start, Event.ConfigReported(ConfigReport(Vector(port), Vector.empty)))
    assertEquals(run(clean, Event.Pressed(Key.Char('e')))._1.prompt, None)
  }

  test("a child that serves without a config report retires the pane, as the schema pane goes") {
    val (opened, _) = run(start, Event.ConfigReported(refused))
    assertEquals(opened.tab, Tab.Config)
    val (served, _) =
      run(opened, Event.AppStarted("Main"), Event.Serving("http://localhost:8080"))
    assertEquals(served.config, None)
    assertEquals(served.tab, Tab.Log)
    assert(!served.tabs.contains(Tab.Config), served.tabs.toString)

    val (kept, _) = run(
      opened,
      Event.AppStarted("Main"),
      Event.ConfigReported(clean),
      Event.Serving("http://localhost:8080")
    )
    assertEquals(kept.config, Some(clean))
    assertEquals(kept.tab, Tab.Config)
  }

  test("the number keys reach every pane, the config pane fourth and the log last") {
    val (reported, _) = run(start, Event.ConfigReported(clean))
    assertEquals(reported.tabs, Vector(Tab.Errors, Tab.Routes, Tab.Schema, Tab.Config, Tab.Log))
    assertEquals(
      "12345".map(c => run(reported, Event.Pressed(Key.Char(c)))._1.tab).toVector,
      Vector[Tab](Tab.Errors, Tab.Routes, Tab.Schema, Tab.Config, Tab.Log)
    )
    val (cycled, _) = run(reported, Event.Pressed(Key.Char('3')), Event.Pressed(Key.Tab))
    assertEquals(cycled.tab, Tab.Config)
    assertEquals(run(reported, Event.Pressed(Key.Char('6')))._1.tab, Tab.Log)
  }

  test("a change starts a build; one during a build is remembered and runs after it") {
    val (compiling, effects) = run(start, Event.Changed(Set("src/main/scala/app/Index.scala")))
    assertEquals(effects, Vector(Effect.Build))
    assertEquals(compiling.build, BuildState.Compiling(Some("Index.scala"), 1000L))

    val (queued, none) = run(compiling, Event.Changed(Set("Other.scala")))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(queued.queued, Some(Set("Other.scala")))

    val (built, after) = run(queued, Event.BuildSucceeded(Vector.empty, 300L))
    assertEquals(after, Vector(Effect.Restart, Effect.Build))
    assertEquals(built.queued, None)
    assertEquals(built.build, BuildState.Compiling(Some("Other.scala"), 1000L))
    assert(built.log.exists(_.text == "built in 0.3s"))
  }

  test("a save during the remembered build is remembered in turn, and runs third") {
    val (first, _)  = run(start, Event.Changed(Set("A.scala")))
    val (second, _) =
      run(first, Event.Changed(Set("B.scala")), Event.BuildSucceeded(Vector.empty, 1L))
    assert(second.compiling)
    val (third, none) = run(second, Event.Changed(Set("C.scala")))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(third.queued, Some(Set("C.scala")))
    val (last, effects) = run(third, Event.BuildSucceeded(Vector.empty, 1L))
    assertEquals(effects, Vector(Effect.Restart, Effect.Build))
    assertEquals(last.build, BuildState.Compiling(Some("C.scala"), 1000L))
    val (done, nothing) = run(last, Event.BuildSucceeded(Vector.empty, 1L))
    assertEquals(nothing, Vector(Effect.Restart))
    assertEquals(done.build, BuildState.Built(1L, Vector.empty))
  }

  test("a save during a build that fails, or that could not run, is still compiled") {
    val (failed, afterFailure) = run(
      start,
      Event.Changed(Set("A.scala")),
      Event.Changed(Set("B.scala")),
      Event.BuildFailed(Vector(error), 1L)
    )
    assertEquals(afterFailure, Vector(Effect.Build, Effect.Build))
    assertEquals(failed.build, BuildState.Compiling(Some("B.scala"), 1000L))
    val (broken, afterError) = run(
      start,
      Event.Changed(Set("A.scala")),
      Event.Changed(Set("B.scala")),
      Event.BuildError("no main class")
    )
    assertEquals(afterError, Vector(Effect.Build, Effect.Build))
    assertEquals(broken.build, BuildState.Compiling(Some("B.scala"), 1000L))
  }

  test("seconds are spelled with a point whatever the machine's language") {
    val before = java.util.Locale.getDefault
    try {
      java.util.Locale.setDefault(java.util.Locale.ITALIAN)
      assertEquals(Update.seconds(400L), "0.4s")
    } finally java.util.Locale.setDefault(before)
  }

  test("a failed build restarts nothing, opens the errors pane, and keeps the app") {
    val running           = start.copy(app = AppState.Running(Some("http://localhost:8090")))
    val (compiling, _)    = run(running, Event.Changed(Set.empty))
    val (failed, effects) = run(compiling, Event.BuildFailed(Vector(error), 200L))
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
    assertEquals(forced, Vector(Effect.Run(SchemaCommand.Sync(force = true))))
    val (cancelled, nothing) = run(asked, Event.Pressed(Key.Escape))
    assertEquals(nothing, Vector.empty[Effect])
    assertEquals(cancelled.prompt, None)
  }

  test("s on additive drift syncs at once, and an applied sync restarts the app") {
    val (m, effects) = run(start, Event.DriftReported(additive), Event.Pressed(Key.Char('s')))
    assertEquals(effects, Vector(Effect.Run(SchemaCommand.Sync(force = false))))
    val applied = JsonReader
      .parse(
        """{"command":"sync","applied":true,"refused":false,"changes":[{"describe":"+ x"}],"blocked":[]}"""
      )
      .toOption
    val (after, more) = run(
      m,
      Event.CommandStarted(SchemaCommand.Sync(force = false)),
      Event.CommandFinished(
        Outcome(SchemaCommand.Sync(force = false), 0, applied, Vector.empty, Vector.empty)
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
      Event.CommandFinished(
        Outcome(SchemaCommand.Sync(force = false), 1, refused, Vector.empty, Vector.empty)
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
    assertEquals(keep, Vector(Effect.Run(SchemaCommand.Freeze("drop notes", dropAll = false))))
    val (_, drop) = run(policy, Event.Pressed(Key.Char('d')))
    assertEquals(drop, Vector(Effect.Run(SchemaCommand.Freeze("drop notes", dropAll = true))))

    val wrote = JsonReader
      .parse(
        """{"command":"freeze","migration":"db/migrations/0002_drop_notes.sql","resolutions":[]}"""
      )
      .toOption
    val (_, next) = run(
      policy.copy(prompt = None),
      Event.CommandFinished(
        Outcome(
          SchemaCommand.Freeze("drop notes", dropAll = true),
          0,
          wrote,
          Vector.empty,
          Vector.empty
        )
      )
    )
    assertEquals(next, Vector(Effect.Run(SchemaCommand.Migrate(apply = true))))
    val applied = JsonReader
      .parse(
        """{"command":"migrate","outcome":"applied","applied":[{"number":2,"file":"0002_drop_notes.sql","statements":[]}],"drift":[]}"""
      )
      .toOption
    val (_, last) = run(
      policy.copy(prompt = None),
      Event.CommandFinished(
        Outcome(SchemaCommand.Migrate(apply = true), 0, applied, Vector.empty, Vector.empty)
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
    assertEquals(effects, Vector(Effect.Run(SchemaCommand.Freeze("x", dropAll = false))))
  }

  test("m lists pending migrations, Enter applies them") {
    val (_, effects) = run(start, Event.Pressed(Key.Char('m')))
    assertEquals(effects, Vector(Effect.Run(SchemaCommand.Migrate(apply = false))))
    val pending = JsonReader
      .parse(
        """{"command":"migrate","outcome":"pending","pending":[{"number":2,"file":"0002_x.sql","statements":["x"]}]}"""
      )
      .toOption
    val (asked, _) = run(
      start,
      Event.CommandFinished(
        Outcome(SchemaCommand.Migrate(apply = false), 0, pending, Vector.empty, Vector.empty)
      )
    )
    assertEquals(asked.prompt, Some(Prompt.ConfirmMigrate(1)))
    val (_, apply) = run(asked, Event.Pressed(Key.Enter))
    assertEquals(apply, Vector(Effect.Run(SchemaCommand.Migrate(apply = true))))
  }

  test("serving without a drift report means no database layer: the pane and its keys go") {
    val (onSchema, _) = run(start, Event.Pressed(Key.Char('3')))
    assertEquals(onSchema.tab, Tab.Schema)
    val (served, _) =
      run(onSchema, Event.AppStarted("Main"), Event.Serving("http://localhost:8080"))
    assertEquals(served.schema, SchemaState.NoDatabase)
    assertEquals(served.tab, Tab.Log)
    assertEquals(served.tabs, Vector(Tab.Errors, Tab.Routes, Tab.Log))
    val (still, effects) = run(served, Event.Pressed(Key.Char('3')), Event.Pressed(Key.Char('s')))
    assertEquals(still.tab, Tab.Log)
    assertEquals(effects, Vector.empty[Effect])
    val (cycled, _) = run(served, Event.Pressed(Key.Char('2')), Event.Pressed(Key.Tab))
    assertEquals(cycled.tab, Tab.Log)

    val reported   = Drift(Vector.empty, blocking = false, unreachable = Some("connection refused"))
    val (known, _) =
      run(start, Event.DriftReported(reported), Event.Serving("http://localhost:8080"))
    assertEquals(known.schema, SchemaState.Known(reported))
    assert(known.hasDatabase)
    assert(!reported.inSync)
  }

  test("an application without a database layer loses the schema keys after the first answer") {
    val (m, _) = run(
      start,
      Event.CommandFinished(
        Outcome(SchemaCommand.Status, 2, None, Vector.empty, Vector("[eezo] unknown command: db"))
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
      Event.CommandFinished(Outcome(SchemaCommand.Status, 1, drifted, Vector.empty, Vector.empty))
    )
    assertEquals(m.schema, SchemaState.Known(blocking))
  }

  test("a change during a schema command waits for it") {
    val (busy, none) =
      run(start, Event.CommandStarted(SchemaCommand.Status), Event.Changed(Set("A.scala")))
    assertEquals(none, Vector.empty[Effect])
    assertEquals(busy.queued, Some(Set("A.scala")))
    val (_, effects) = run(
      busy,
      Event.CommandFinished(
        Outcome(
          SchemaCommand.Status,
          0,
          JsonReader.parse("""{"inSync":true,"changes":[]}""").toOption,
          Vector.empty,
          Vector.empty
        )
      )
    )
    assertEquals(effects, Vector(Effect.Build))
  }

  test("a stopped application is stopped until it starts again") {
    val running      = start.copy(app = AppState.Running(Some("http://localhost:8090")))
    val (stopped, _) = run(running, Event.AppStopped)
    assertEquals(stopped.app, AppState.Stopped)
    val (started, _) = run(stopped, Event.AppStarted("Main"))
    assertEquals(started.app, AppState.Starting("Main"))
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
