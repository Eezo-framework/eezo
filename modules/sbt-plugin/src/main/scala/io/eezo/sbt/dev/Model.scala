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

private[sbt] sealed trait ConfigState

private[sbt] object ConfigState {

  /** No report: no child has booted, or the last one served without printing one. */
  case object Unknown extends ConfigState

  /** From an earlier child, kept on screen while the new one starts: the pane goes if the new child
    * serves without a report of its own, since its build may no longer name a Config.
    */
  final case class Stale(report: ConfigReport) extends ConfigState

  /** Printed by the running child. */
  final case class Fresh(report: ConfigReport) extends ConfigState
}

private[sbt] sealed abstract class Tab(val number: Int, val name: String, val short: String)

private[sbt] object Tab {
  case object Errors extends Tab(1, "errors", "err")
  case object Routes extends Tab(2, "routes", "rt")
  case object Schema extends Tab(3, "schema", "db")
  case object Config extends Tab(4, "config", "cfg")

  /** Last, since it is the one pane that is never short: the panes before it are the answers, the
    * log is where the output went.
    */
  case object Log extends Tab(5, "log", "log")

  val all: Vector[Tab] = Vector(Errors, Routes, Schema, Config, Log)

  def numbered(n: Int): Option[Tab] = all.find(_.number == n)
}

/** What the developer types into the console. Not a case class, so that no `toString`, the model's,
  * a prompt's or a suite's failure message, can print it: only [[reveal]] hands it out, to the view
  * for a plain variable and to the child's environment.
  */
private[sbt] final class Typed private (private val text: String) {
  def +(c: Char): Typed         = new Typed(text + c)
  def dropLast: Typed           = new Typed(text.dropRight(1))
  def isEmpty: Boolean          = text.isEmpty
  def length: Int               = text.length
  def reveal: String            = text
  override def toString: String = "Typed(hidden)"

  override def equals(other: Any): Boolean = other match {
    case t: Typed => t.text == text
    case _        => false
  }

  override def hashCode: Int = text.hashCode
}

private[sbt] object Typed {
  val empty: Typed               = new Typed("")
  def apply(text: String): Typed = new Typed(text)
}

/** One path the config pane asks a value for: the variable to set, the other one the file names for
  * the path when there are two, and whether what is typed is masked.
  */
private[sbt] final case class Ask(
    path: String,
    variable: String,
    alternative: Option[String],
    redacted: Boolean
)

/** A question in the footer. Data, not a callback, so a suite can look at it. */
private[sbt] sealed trait Prompt

private[sbt] object Prompt {

  /** `sync` would refuse: `count` destructive or risky changes; Enter forces, Esc cancels. */
  final case class ConfirmForce(count: Int) extends Prompt

  final case class FreezeName(buffer: String) extends Prompt

  /** The migration named `name` has `destructive` changes to decide: keep or drop, all of them. */
  final case class FreezePolicy(name: String, destructive: Int) extends Prompt

  final case class ConfirmMigrate(pending: Int) extends Prompt

  /** A value for the first of `asks`, typed so far into `typed`; the rest follow it. */
  final case class Supply(asks: Vector[Ask], typed: Typed) extends Prompt
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
    configState: ConfigState,
    supplied: Map[String, Typed],
    outcome: Option[Outcome],
    busy: Option[SchemaCommand],
    tab: Tab,
    offsets: Map[Tab, Int],
    follow: Boolean,
    prompt: Option[Prompt],
    help: Boolean,
    queued: Option[Set[String]],
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

  def config: Option[ConfigReport] = configState match {
    case ConfigState.Unknown       => None
    case ConfigState.Stale(report) => Some(report)
    case ConfigState.Fresh(report) => Some(report)
  }

  /** The panes this application has: the schema pane goes with the database layer, and the config
    * pane comes with a config report, which only an application whose build names a Config prints.
    */
  def tabs: Vector[Tab] =
    Tab.all.filter {
      case Tab.Schema => hasDatabase
      case Tab.Config => config.isDefined
      case _          => true
    }

  /** The paths `e` asks a value for, from the last report, every one of them each time: a value
    * typed before is asked again, since the report still names the path. A malformed value is asked
    * of the variable that supplied it, the last one named that is set, because HOCON lets a later
    * substitution win and a value typed for an earlier one would change nothing.
    */
  def asks: Vector[Ask] =
    config.toVector.flatMap { report =>
      def set(variable: String): Boolean = report.rows.exists(r => r.variable == variable && r.set)
      report.fixable.map { o =>
        val variable =
          if (o.kind == ConfigOffender.Malformed) o.variables.filter(set).lastOption
          else None
        val asked = variable.getOrElse(o.variables.head)
        // Masked unless the report says the variable is plain.
        val redacted = report.rows
          .find(r => r.path == o.path && r.variable == asked)
          .orElse(report.rows.find(_.path == o.path))
          .forall(_.redacted)
        Ask(o.path, asked, o.variables.find(_ != asked), redacted)
      }
    }

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
      configState = ConfigState.Unknown,
      supplied = Map.empty,
      outcome = None,
      busy = None,
      tab = Tab.Log,
      offsets = Map.empty,
      follow = true,
      prompt = None,
      help = false,
      queued = None,
      spinner = 0
    )
}

private[sbt] object Update {

  private val none: Vector[Effect] = Vector.empty

  def apply(m: Model, e: Event): (Model, Vector[Effect]) = e match {

    case Event.Tick(now) => (m.copy(now = now, spinner = m.spinner + 1), none)

    case Event.Resized(w, h) => (clampAll(m.copy(width = w, height = h)), none)

    case Event.Changed(paths) => startBuild(m, paths)

    case Event.BuildSucceeded(warnings, millis) =>
      val built = m
        .copy(build = BuildState.Built(millis, warnings), app = AppState.Starting("..."))
        .note(s"built in ${seconds(millis)}", LogLine.Ok)
      val next =
        if (built.tab == Tab.Errors && warnings.isEmpty) built.copy(tab = Tab.Log) else built
      val (resumed, rebuild) = queuedBuild(clampAll(next))
      (resumed, Effect.Restart +: rebuild)

    case Event.BuildFailed(diagnostics, millis) =>
      val failed = m
        .copy(build = BuildState.Failed(diagnostics, millis), tab = Tab.Errors)
        .note("build failed; the previous build keeps serving. Fix it and save.", LogLine.Fail)
      queuedBuild(clampAll(failed.copy(offsets = failed.offsets.updated(Tab.Errors, 0))))

    case Event.BuildError(message) =>
      queuedBuild(m.copy(build = BuildState.Idle).note(message, LogLine.Fail))

    case Event.AppStarted(mainClass) =>
      (
        m.copy(
          app = AppState.Starting(mainClass),
          configState = m.configState match {
            case ConfigState.Fresh(report) => ConfigState.Stale(report)
            case other                     => other
          }
        ).note(s"started $mainClass"),
        none
      )

    case Event.AppStopped => (m.copy(app = AppState.Stopped), none)

    case Event.AppExited(code) =>
      val kind = if (code == 0) LogLine.Note else LogLine.Fail
      (
        m.copy(app = AppState.Exited(code)).note(s"the application exited with code $code", kind),
        none
      )

    case Event.AppLine(text, err) =>
      (m.append(LogLine(text, if (err) LogLine.Err else LogLine.App)), none)

    case Event.Serving(url) =>
      // An application with a database layer reports its drift before it binds, so one that serves
      // without a word has none: the schema pane goes, and so do its keys.
      // So does a build that names no Config: its report goes with the child that printed it, and
      // one that serves without a word has none.
      val schema = if (m.schema == SchemaState.Unknown) SchemaState.NoDatabase else m.schema
      val config = m.configState match {
        case fresh: ConfigState.Fresh => fresh
        case _                        => ConfigState.Unknown
      }
      val tab = m.tab match {
        case Tab.Schema if schema == SchemaState.NoDatabase => Tab.Log
        case Tab.Config if config == ConfigState.Unknown    => Tab.Log
        case other                                          => other
      }
      (
        m.copy(app = AppState.Running(Some(url)), schema = schema, configState = config, tab = tab),
        none
      )

    case Event.RoutesReported(listing) =>
      (clampAll(m.copy(routes = Some(listing))), none)

    case Event.ConfigReported(report) =>
      val known = m.copy(configState = ConfigState.Fresh(report))
      (clampAll(if (report.offenders.nonEmpty) known.copy(tab = Tab.Config) else known), none)

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

    case Event.CommandStarted(command) =>
      (m.copy(busy = Some(command), outcome = None).note(s"$$ eezo ${command.label}"), none)

    case Event.CommandFinished(outcome) =>
      finished(m.copy(busy = None, outcome = Some(outcome)), outcome)

    case Event.Pressed(key) => pressed(m, key)
  }

  /** A build for `paths`, or, while one runs or a schema command does, the paths remembered for the
    * build that follows it.
    */
  private def startBuild(m: Model, paths: Set[String]): (Model, Vector[Effect]) =
    if (m.compiling || m.busy.isDefined)
      (m.copy(queued = Some(m.queued.getOrElse(Set.empty) ++ paths)), none)
    else {
      val trigger = paths.toVector.sorted.headOption.map(p => p.substring(p.lastIndexOf('/') + 1))
      (m.copy(build = BuildState.Compiling(trigger, m.now), queued = None), Vector(Effect.Build))
    }

  /** The remembered build, started now that nothing is in its way: the model says it is compiling,
    * with its own trigger, so a save during it is remembered in turn rather than dropped.
    */
  private def queuedBuild(m: Model): (Model, Vector[Effect]) = m.queued match {
    case Some(paths) if !m.compiling && m.busy.isEmpty => startBuild(m.copy(queued = None), paths)
    case _                                             => (m, none)
  }

  // ---- actions -------------------------------------------------------------------------------

  /** The outcome's lines go to the log, every one of them: what the command said, then what it
    * wrote on stderr less the pool's own records. The schema pane keeps one line, the summary, so
    * the pane answers the question and the log holds the evidence.
    */
  private def finished(told: Model, o: Outcome): (Model, Vector[Effect]) = {
    val m = LineProtocol
      .quiet(o.err)
      .foldLeft(
        Describe.outcome(o).foldLeft(told)((acc, l) => acc.append(LogLine(l, LogLine.App)))
      )((acc, l) => acc.append(LogLine(l, LogLine.Err)))
    val after: (Model, Vector[Effect]) =
      if (o.unknownCommand)
        (
          m.copy(schema = SchemaState.NoDatabase).note("this application has no database layer"),
          none
        )
      else
        o.command match {
          case SchemaCommand.Status =>
            o.json match {
              case Some(j) if o.code == 0 || o.code == 1 =>
                val drift = Drift.fromJson(j)
                (m.copy(schema = SchemaState.Known(drift)).note(Describe.status(drift)), none)
              case _ => (failed(m, o), none)
            }
          case SchemaCommand.Sync(force) =>
            o.json match {
              case Some(j) if j("applied").boolOr(false) =>
                (m.note("applied to the dev database", LogLine.Ok), Vector(Effect.Restart))
              case Some(j) if j("refused").boolOr(false) && !force =>
                val blocked = j("blocked").arr.size
                (m.copy(prompt = Some(Prompt.ConfirmForce(blocked))), none)
              case Some(j) if j("changes").arr.isEmpty => (m.note("in sync", LogLine.Ok), none)
              case _                                   => (failed(m, o), none)
            }
          case SchemaCommand.Freeze(_, _) =>
            o.json match {
              case Some(j) if o.ok && j("migration").str.isDefined =>
                (
                  m.note(s"wrote ${j("migration").strOr("")}", LogLine.Ok),
                  Vector(Effect.Run(SchemaCommand.Migrate(apply = true)))
                )
              case Some(_) if o.ok =>
                (m.note("nothing to freeze: the snapshot already matches the model"), none)
              case _ => (failed(m, o), none)
            }
          case SchemaCommand.Migrate(apply) =>
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
    val (next, effects)    = after
    val (resumed, rebuild) = queuedBuild(clampAll(next))
    (resumed, effects ++ rebuild)
  }

  private def failed(m: Model, o: Outcome): Model =
    m.note(s"${o.command.label} exited ${o.code}", LogLine.Fail)

  // ---- keys ----------------------------------------------------------------------------------

  private def pressed(m: Model, key: Key): (Model, Vector[Effect]) =
    m.prompt match {
      case Some(p)        => prompt(m, p, key)
      case None if m.help => (m.copy(help = false), none)
      case None           => plain(m, key)
    }

  private def plain(m: Model, key: Key): (Model, Vector[Effect]) = key match {
    case Key.Char('q') | Key.CtrlC | Key.CtrlD          => (m, Vector(Effect.Quit))
    case Key.Char('?')                                  => (m.copy(help = true), none)
    case Key.Char(c) if Tab.numbered(c - '0').isDefined =>
      (m.copy(tab = Tab.numbered(c - '0').filter(m.tabs.contains).getOrElse(m.tab)), none)
    case Key.Tab     => (m.copy(tab = m.tabs((m.tabs.indexOf(m.tab) + 1) % m.tabs.size)), none)
    case Key.BackTab =>
      (m.copy(tab = m.tabs((m.tabs.indexOf(m.tab) + m.tabs.size - 1) % m.tabs.size)), none)
    case Key.Char('r')                    => startBuild(m, Set.empty)
    case Key.Char('e') if m.asks.nonEmpty =>
      (m.copy(prompt = Some(Prompt.Supply(m.asks, Typed.empty))), none)
    case Key.Char('c') =>
      (m.copy(log = Vector.empty, offsets = m.offsets.updated(Tab.Log, 0), follow = true), none)
    case Key.Char('s') if m.hasDatabase && m.busy.isEmpty =>
      m.drift match {
        case Some(d) if d.inSync             => (m.note("in sync", LogLine.Ok), none)
        case Some(d) if d.dangerous.nonEmpty =>
          (m.copy(prompt = Some(Prompt.ConfirmForce(d.dangerous.size))), none)
        case _ => (m, Vector(Effect.Run(SchemaCommand.Sync(force = false))))
      }
    case Key.Char('f') if m.hasDatabase && m.busy.isEmpty =>
      (m.copy(prompt = Some(Prompt.FreezeName(""))), none)
    case Key.Char('m') if m.hasDatabase && m.busy.isEmpty =>
      (m, Vector(Effect.Run(SchemaCommand.Migrate(apply = false))))
    case Key.Char('S') if m.hasDatabase && m.busy.isEmpty =>
      (m, Vector(Effect.Run(SchemaCommand.Status)))
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
        (m.copy(prompt = None), Vector(Effect.Run(SchemaCommand.Sync(force = true))))
      case (Prompt.ConfirmMigrate(_), Key.Enter | Key.Char('y')) =>
        (m.copy(prompt = None), Vector(Effect.Run(SchemaCommand.Migrate(apply = true))))
      case (Prompt.FreezeName(buffer), Key.Enter) =>
        val name = buffer.trim
        if (name.isEmpty) (m, none)
        else
          m.drift.map(_.destructive.size).filter(_ > 0) match {
            case Some(n) => (m.copy(prompt = Some(Prompt.FreezePolicy(name, n))), none)
            case None    =>
              (
                m.copy(prompt = None),
                Vector(Effect.Run(SchemaCommand.Freeze(name, dropAll = false)))
              )
          }
      case (Prompt.Supply(ask +: rest, typed), Key.Enter) =>
        if (typed.isEmpty) (m, none)
        else {
          val held = m.copy(supplied = m.supplied.updated(ask.variable, typed))
          if (rest.isEmpty)
            (
              held
                .copy(prompt = None)
                .note("the values typed here last for this session only; restarting"),
              Vector(Effect.Restart)
            )
          else (held.copy(prompt = Some(Prompt.Supply(rest, Typed.empty))), none)
        }
      case (Prompt.Supply(asks, typed), Key.Backspace) =>
        (m.copy(prompt = Some(Prompt.Supply(asks, typed.dropLast))), none)
      case (Prompt.Supply(asks, typed), Key.Char(c)) =>
        (m.copy(prompt = Some(Prompt.Supply(asks, typed + c))), none)
      case (Prompt.FreezeName(buffer), Key.Backspace) =>
        (m.copy(prompt = Some(Prompt.FreezeName(buffer.dropRight(1)))), none)
      case (Prompt.FreezeName(buffer), Key.Char(c)) =>
        (m.copy(prompt = Some(Prompt.FreezeName(buffer + c))), none)
      case (Prompt.FreezePolicy(name, _), Key.Char('k') | Key.Enter) =>
        (m.copy(prompt = None), Vector(Effect.Run(SchemaCommand.Freeze(name, dropAll = false))))
      case (Prompt.FreezePolicy(name, _), Key.Char('d')) =>
        (m.copy(prompt = None), Vector(Effect.Run(SchemaCommand.Freeze(name, dropAll = true))))
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

  /** `0.4s`, with the point whatever the machine's language: a suite and a tool read it. */
  def seconds(millis: Long): String =
    String.format(java.util.Locale.ROOT, "%.1fs", Double.box(millis / 1000.0))
}
