package io.eezo.sbt.dev

/** The console's whole state, immutable, and [[Update]], the one function that moves it.
  *
  * Nothing here knows about sbt, a terminal, a process or a thread: the model is a value, an event
  * is a value, and `update` answers a new model and the effects the command should run. That is
  * what makes the console's behaviour testable in a plain suite on both axes, and what keeps the
  * threads honest: they enqueue events, and one loop applies them.
  */

private[sbt] sealed trait BuildState

private[sbt] object BuildState {
  case object Idle                                                       extends BuildState
  final case class Compiling(trigger: Option[String], since: Long)       extends BuildState
  final case class Built(millis: Long, warnings: Vector[Diagnostic])     extends BuildState
  final case class Failed(diagnostics: Vector[Diagnostic], millis: Long) extends BuildState
}

private[sbt] sealed trait AppState

private[sbt] object AppState {
  case object Stopped                           extends AppState
  final case class Starting(mainClass: String)  extends AppState
  final case class Running(url: Option[String]) extends AppState
  final case class Exited(code: Int)            extends AppState
}

private[sbt] sealed trait SchemaState

private[sbt] object SchemaState {

  /** No report yet: the application has not booted, or booted without printing one. */
  case object Unknown extends SchemaState

  /** `db` is an unknown command to this application: the http layer alone. */
  case object NoDatabase extends SchemaState

  final case class Known(drift: Drift) extends SchemaState
}

private[sbt] sealed abstract class Tab(val number: Int, val name: String, val short: String)

private[sbt] object Tab {
  case object Log    extends Tab(1, "log", "log")
  case object Errors extends Tab(2, "errors", "err")
  case object Routes extends Tab(3, "routes", "rt")
  case object Schema extends Tab(4, "schema", "db")

  val all: Vector[Tab] = Vector(Log, Errors, Routes, Schema)

  def numbered(n: Int): Option[Tab] = all.find(_.number == n)
}

/** A question in the footer. Data, not a callback, so a suite can look at it. */
private[sbt] sealed trait Prompt

private[sbt] object Prompt {

  /** `sync` would refuse: `count` destructive or risky changes; Enter forces, Esc cancels. */
  final case class ConfirmForce(count: Int) extends Prompt

  final case class FreezeName(buffer: String) extends Prompt

  /** The migration named `name` has `destructive` changes to decide: keep or drop, all of them. */
  final case class FreezePolicy(name: String, destructive: Int) extends Prompt

  final case class ConfirmMigrate(pending: Int) extends Prompt
}

private[sbt] final case class LogLine(text: String, kind: LogLine.Kind)

private[sbt] object LogLine {
  sealed trait Kind
  case object App  extends Kind
  case object Err  extends Kind
  case object Note extends Kind
  case object Ok   extends Kind
  case object Fail extends Kind

  val Keep: Int = 2000
}

private[sbt] final case class Model(
    project: String,
    width: Int,
    height: Int,
    now: Long,
    build: BuildState,
    app: AppState,
    log: Vector[LogLine],
    routes: Option[RouteListing],
    schema: SchemaState,
    outcome: Option[Outcome],
    busy: Option[Action],
    tab: Tab,
    offsets: Map[Tab, Int],
    follow: Boolean,
    prompt: Option[Prompt],
    help: Boolean,
    dirty: Boolean,
    spinner: Int
) {

  def compiling: Boolean = build match {
    case BuildState.Compiling(_, _) => true
    case _                          => false
  }

  def errors: Vector[Diagnostic] = build match {
    case BuildState.Failed(ds, _) => ds.filter(_.isError)
    case _                        => Vector.empty
  }

  def warnings: Vector[Diagnostic] = build match {
    case BuildState.Failed(ds, _) => ds.filterNot(_.isError)
    case BuildState.Built(_, ws)  => ws
    case _                        => Vector.empty
  }

  def hasDatabase: Boolean = schema != SchemaState.NoDatabase

  def drift: Option[Drift] = schema match {
    case SchemaState.Known(d) => Some(d)
    case _                    => None
  }

  def offset(tab: Tab): Int = offsets.getOrElse(tab, 0)

  def note(text: String, kind: LogLine.Kind = LogLine.Note): Model = append(LogLine(text, kind))

  def append(line: LogLine): Model = {
    val next = log :+ line
    copy(log = if (next.size > LogLine.Keep) next.drop(next.size - LogLine.Keep) else next)
  }
}

private[sbt] object Model {

  def initial(project: String, width: Int, height: Int, now: Long): Model =
    Model(
      project = project,
      width = width,
      height = height,
      now = now,
      build = BuildState.Idle,
      app = AppState.Stopped,
      log = Vector.empty,
      routes = None,
      schema = SchemaState.Unknown,
      outcome = None,
      busy = None,
      tab = Tab.Log,
      offsets = Map.empty,
      follow = true,
      prompt = None,
      help = false,
      dirty = false,
      spinner = 0
    )
}

private[sbt] object Update {

  private val none: Vector[Effect] = Vector.empty

  def apply(m: Model, e: Event): (Model, Vector[Effect]) = e match {

    case Event.Tick(now) => (m.copy(now = now, spinner = m.spinner + 1), none)

    case Event.Resized(w, h) => (clampAll(m.copy(width = w, height = h)), none)

    case Event.Changed(paths) =>
      val trigger = paths.toVector.sorted.headOption.map(p => p.substring(p.lastIndexOf('/') + 1))
      startBuild(m, trigger)

    case Event.BuildStarted(trigger) =>
      (m.copy(build = BuildState.Compiling(trigger, m.now)), none)

    case Event.BuildSucceeded(warnings, millis) =>
      val built = m
        .copy(
          build = BuildState.Built(millis, warnings),
          app = AppState.Starting("..."),
          dirty = false
        )
        .note(s"built in ${seconds(millis)}", LogLine.Ok)
      val next =
        if (built.tab == Tab.Errors && warnings.isEmpty) built.copy(tab = Tab.Log) else built
      (clampAll(next), Vector(Effect.Restart) ++ (if (m.dirty) Vector(Effect.Build) else none))

    case Event.BuildFailed(diagnostics, millis) =>
      val failed = m
        .copy(build = BuildState.Failed(diagnostics, millis), dirty = false, tab = Tab.Errors)
        .note("build failed; the previous build keeps serving. Fix it and save.", LogLine.Fail)
      (
        clampAll(failed.copy(offsets = failed.offsets.updated(Tab.Errors, 0))),
        if (m.dirty) Vector(Effect.Build) else none
      )

    case Event.BuildError(message) =>
      (m.copy(build = BuildState.Idle, dirty = false).note(message, LogLine.Fail), none)

    case Event.AppStarted(mainClass) =>
      (m.copy(app = AppState.Starting(mainClass)).note(s"started $mainClass"), none)

    case Event.AppStopped => (m.copy(app = AppState.Stopped), none)

    case Event.AppExited(code) =>
      val kind = if (code == 0) LogLine.Note else LogLine.Fail
      (
        m.copy(app = AppState.Exited(code)).note(s"the application exited with code $code", kind),
        none
      )

    case Event.AppLine(text, err) =>
      (m.append(LogLine(text, if (err) LogLine.Err else LogLine.App)), none)

    case Event.Serving(url) => (m.copy(app = AppState.Running(Some(url))), none)

    case Event.RoutesReported(listing) =>
      (clampAll(m.copy(routes = Some(listing))), none)

    case Event.DriftReported(drift) =>
      val known = m.copy(schema = SchemaState.Known(drift))
      val next  =
        if (drift.blocking)
          known
            .copy(tab = Tab.Schema)
            .note(
              s"${drift.dangerous.size} destructive or risky change(s): the drift page is served " +
                "instead of the app. s applies, f freezes a migration.",
              LogLine.Fail
            )
        else if (!drift.inSync)
          known.note(s"${drift.changes.size} additive change(s); serving anyway")
        else known
      (clampAll(next), none)

    case Event.ActionStarted(action) =>
      (m.copy(busy = Some(action), outcome = None).note(s"$$ eezo ${action.label}"), none)

    case Event.ActionFinished(outcome) =>
      finished(m.copy(busy = None, outcome = Some(outcome)), outcome)

    case Event.Pressed(key) => pressed(m, key)
  }

  private def startBuild(m: Model, trigger: Option[String]): (Model, Vector[Effect]) =
    if (m.compiling || m.busy.isDefined) (m.copy(dirty = true), none)
    else (m.copy(build = BuildState.Compiling(trigger, m.now), dirty = false), Vector(Effect.Build))

  // ---- actions -------------------------------------------------------------------------------

  private def finished(m: Model, o: Outcome): (Model, Vector[Effect]) = {
    val after: (Model, Vector[Effect]) =
      if (o.unknownCommand)
        (
          m.copy(schema = SchemaState.NoDatabase).note("this application has no database layer"),
          none
        )
      else
        o.action match {
          case Action.Status =>
            o.json match {
              case Some(j) if o.code == 0 || o.code == 1 =>
                val drift = Drift.fromJson(j)
                (m.copy(schema = SchemaState.Known(drift)).note(Describe.status(drift)), none)
              case _ => (failed(m, o), none)
            }
          case Action.Sync(force) =>
            o.json match {
              case Some(j) if j("applied").boolOr(false) =>
                (m.note("applied to the dev database", LogLine.Ok), Vector(Effect.Restart))
              case Some(j) if j("refused").boolOr(false) && !force =>
                val blocked = j("blocked").arr.size
                (m.copy(prompt = Some(Prompt.ConfirmForce(blocked))), none)
              case Some(j) if j("changes").arr.isEmpty => (m.note("in sync", LogLine.Ok), none)
              case _                                   => (failed(m, o), none)
            }
          case Action.Freeze(_, _) =>
            o.json match {
              case Some(j) if o.ok && j("migration").str.isDefined =>
                (
                  m.note(s"wrote ${j("migration").strOr("")}", LogLine.Ok),
                  Vector(Effect.Run(Action.Migrate(apply = true)))
                )
              case Some(_) if o.ok =>
                (m.note("nothing to freeze: the snapshot already matches the model"), none)
              case _ => (failed(m, o), none)
            }
          case Action.Migrate(apply) =>
            o.json.map(_("outcome").strOr("")) match {
              case Some("pending") =>
                val n = o.json.map(_("pending").arr.size).getOrElse(0)
                if (apply) (failed(m, o), none)
                else (m.copy(prompt = Some(Prompt.ConfirmMigrate(n))), none)
              case Some("upToDate") => (m.note("no pending migrations", LogLine.Ok), none)
              case Some("applied")  =>
                (m.note("migrations applied", LogLine.Ok), Vector(Effect.Restart))
              case Some("tampered") => (failed(m, o), none)
              case _                => (failed(m, o), none)
            }
        }
    val (next, effects) = after
    val rebuild         = if (next.dirty && next.busy.isEmpty) Vector(Effect.Build) else none
    (clampAll(next), effects ++ rebuild)
  }

  private def failed(m: Model, o: Outcome): Model = {
    val lines = Describe.outcome(o)
    lines.foldLeft(m.note(s"${o.action.label} exited ${o.code}", LogLine.Fail)) { (acc, l) =>
      acc.note(l, LogLine.Err)
    }
  }

  // ---- keys ----------------------------------------------------------------------------------

  private def pressed(m: Model, key: Key): (Model, Vector[Effect]) =
    m.prompt match {
      case Some(p)        => prompt(m, p, key)
      case None if m.help => (m.copy(help = false), none)
      case None           => plain(m, key)
    }

  private def plain(m: Model, key: Key): (Model, Vector[Effect]) = key match {
    case Key.Char('q') | Key.CtrlC | Key.CtrlD => (m, Vector(Effect.Quit))
    case Key.Char('?')                         => (m.copy(help = true), none)
    case Key.Char(c) if c >= '1' && c <= '4'   =>
      (m.copy(tab = Tab.numbered(c - '0').getOrElse(m.tab)), none)
    case Key.Tab     => (m.copy(tab = Tab.all((Tab.all.indexOf(m.tab) + 1) % Tab.all.size)), none)
    case Key.BackTab =>
      (m.copy(tab = Tab.all((Tab.all.indexOf(m.tab) + Tab.all.size - 1) % Tab.all.size)), none)
    case Key.Char('r') => startBuild(m, None)
    case Key.Char('c') =>
      (m.copy(log = Vector.empty, offsets = m.offsets.updated(Tab.Log, 0), follow = true), none)
    case Key.Char('s') if m.hasDatabase && m.busy.isEmpty =>
      m.drift match {
        case Some(d) if d.inSync             => (m.note("in sync", LogLine.Ok), none)
        case Some(d) if d.dangerous.nonEmpty =>
          (m.copy(prompt = Some(Prompt.ConfirmForce(d.dangerous.size))), none)
        case _ => (m, Vector(Effect.Run(Action.Sync(force = false))))
      }
    case Key.Char('f') if m.hasDatabase && m.busy.isEmpty =>
      (m.copy(prompt = Some(Prompt.FreezeName(""))), none)
    case Key.Char('m') if m.hasDatabase && m.busy.isEmpty =>
      (m, Vector(Effect.Run(Action.Migrate(apply = false))))
    case Key.Char('S') if m.hasDatabase && m.busy.isEmpty =>
      (m, Vector(Effect.Run(Action.Status)))
    case Key.Up | Key.Char('k')       => (scroll(m, -1), none)
    case Key.Down | Key.Char('j')     => (scroll(m, 1), none)
    case Key.PageUp                   => (scroll(m, -page(m)), none)
    case Key.PageDown | Key.Char(' ') => (scroll(m, page(m)), none)
    case Key.Home | Key.Char('g')     => (scrollTo(m, 0), none)
    case Key.End | Key.Char('G')      => (scrollTo(m, Int.MaxValue), none)
    case _                            => (m, none)
  }

  private def prompt(m: Model, p: Prompt, key: Key): (Model, Vector[Effect]) = {
    val cancel = (m.copy(prompt = None), none)
    (p, key) match {
      case (_, Key.Escape | Key.CtrlC)                         => cancel
      case (Prompt.ConfirmForce(_), Key.Enter | Key.Char('y')) =>
        (m.copy(prompt = None), Vector(Effect.Run(Action.Sync(force = true))))
      case (Prompt.ConfirmMigrate(_), Key.Enter | Key.Char('y')) =>
        (m.copy(prompt = None), Vector(Effect.Run(Action.Migrate(apply = true))))
      case (Prompt.FreezeName(buffer), Key.Enter) =>
        val name = buffer.trim
        if (name.isEmpty) (m, none)
        else
          m.drift.map(_.destructive.size).filter(_ > 0) match {
            case Some(n) => (m.copy(prompt = Some(Prompt.FreezePolicy(name, n))), none)
            case None    =>
              (m.copy(prompt = None), Vector(Effect.Run(Action.Freeze(name, dropAll = false))))
          }
      case (Prompt.FreezeName(buffer), Key.Backspace) =>
        (m.copy(prompt = Some(Prompt.FreezeName(buffer.dropRight(1)))), none)
      case (Prompt.FreezeName(buffer), Key.Char(c)) =>
        (m.copy(prompt = Some(Prompt.FreezeName(buffer + c))), none)
      case (Prompt.FreezePolicy(name, _), Key.Char('k') | Key.Enter) =>
        (m.copy(prompt = None), Vector(Effect.Run(Action.Freeze(name, dropAll = false))))
      case (Prompt.FreezePolicy(name, _), Key.Char('d')) =>
        (m.copy(prompt = None), Vector(Effect.Run(Action.Freeze(name, dropAll = true))))
      case (_, Key.Char('n')) => cancel
      case _                  => (m, none)
    }
  }

  // ---- scrolling -----------------------------------------------------------------------------

  private def page(m: Model): Int = math.max(1, Layout(m.width, m.height).paneHeight - 1)

  private def scroll(m: Model, by: Int): Model = scrollTo(m, effectiveOffset(m) + by)

  private def scrollTo(m: Model, to: Int): Model = {
    val max     = maxOffset(m, m.tab)
    val clamped = math.max(0, math.min(max, to))
    val next    = m.copy(offsets = m.offsets.updated(m.tab, clamped))
    if (m.tab == Tab.Log) next.copy(follow = clamped >= max) else next
  }

  /** Where the pane is scrolled to now: the stored offset, or the tail for a following log. */
  def effectiveOffset(m: Model): Int =
    if (m.tab == Tab.Log && m.follow) maxOffset(m, Tab.Log)
    else math.min(m.offset(m.tab), maxOffset(m, m.tab))

  def maxOffset(m: Model, tab: Tab): Int = {
    // Any theme measures the same number of lines; colour adds no rows and the glyphs are one
    // column each. Bound as an implicit rather than passed, which Scala 3 reserves for `using`.
    implicit val measuring: Ansi.Theme = Ansi.Theme(color = false, unicode = true)
    val lines                          = Panes.lines(m, tab, Panes.width(m)).size
    math.max(0, lines - Layout(m.width, m.height).paneHeight)
  }

  private def clampAll(m: Model): Model =
    m.copy(offsets = m.offsets.map { case (t, o) =>
      t -> math.max(0, math.min(o, maxOffset(m, t)))
    })

  def seconds(millis: Long): String = f"${millis / 1000.0}%.1fs"
}
