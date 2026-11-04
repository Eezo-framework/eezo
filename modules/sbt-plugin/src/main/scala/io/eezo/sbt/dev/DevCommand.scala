// Two package clauses, so that `DevProcess` and `EezoPlugin` are in scope by name.
package io.eezo.sbt
package dev

import sbt._
import sbt.Keys._
import sbt.internal.LogManager
import sbt.internal.eezo.TerminalAccess
import sbt.internal.util.{ConsoleOut, Terminal}

import java.io.{
  BufferedReader,
  File,
  FileDescriptor,
  FileOutputStream,
  InputStreamReader,
  OutputStream,
  PrintStream
}
import java.nio.file.Path
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/** `eezoDev`: the dev console.
  *
  * One sbt command that runs until `q`. It owns the terminal for that time: raw input, the
  * alternate screen, and sbt's own console logging silenced through the session it runs its tasks
  * under, so that nothing but the console's frames reaches the screen. Compile diagnostics come
  * back as values from `Builder`, the application's output arrives line by line through
  * `DevProcess` and `LineProtocol`, and the schema commands run as one-off children through
  * `SchemaCommands`. `Model`, `Update` and `View` decide everything; this file is the threads and
  * the terminal.
  *
  * `--plain` is the same loop printing its events as lines through `PlainRenderer` instead of
  * drawing, and `--json` as one JSON object per line through `JsonRenderer`: for a pipe, a log, or
  * an agent driving the build, none of which can read a screen or press a key. Without a terminal
  * and without either flag only `--once` runs: the plain loop cut short at the first serve or exit,
  * with a failed state when the application did not serve; a smoke test for an examples build. A
  * watch that was not asked for as lines is refused there, since it would outlive its reader.
  */
private[sbt] object DevCommand {

  val Name: String = "eezoDev"

  val command: Command =
    Command.args(Name, "[--once|--plain|--json]")((state, args) => console(state, args))

  // The refusals, as values, so that a suite reads them whole.

  def clientRefusal(channel: Option[String]): String =
    s"[eezo dev] $Name runs on sbt's own terminal, and this sbt is a client of a server " +
      s"(channel ${channel.getOrElse("?")}). Run `eezo dev`, or `sbt --server $Name` on an " +
      s"sbt 2 project; `$Name --once` builds, starts and stops from anywhere."

  def terminalRefusal: String =
    s"[eezo dev] $Name needs a terminal to draw on. `$Name --plain` runs the same loop " +
      s"printing lines and `$Name --json` one JSON object per line, for a pipe or an agent; " +
      s"`$Name --once` builds, starts and stops."

  /** At a project without the plugin, the aggregate root of a build of several for one. */
  def projectRefusal(candidates: Seq[String]): String =
    s"[eezo dev] $Name runs in a project that enables EezoPlugin, and the current one does not." +
      (candidates match {
        case Seq()    => ""
        case Seq(one) => s" `project $one` first."
        case many     => s" One of `project ${many.mkString("`, `project ")}` first."
      })

  // Not `run`: that name is `Keys.run`, which the keys below are scoped by.
  private def console(state: State, args: Seq[String]): State = {
    val once        = args.contains("--once")
    val json        = args.contains("--json")
    val plain       = args.contains("--plain") || json
    val terminal    = TerminalAccess.current
    val interactive = !once && !plain && terminal.getWidth > 0 && terminal.isAnsiSupported
    val extracted   = Project.extract(state)

    // The console writes to sbt's own terminal and must not run behind a thin client: the client
    // returns at the first failed task and leaves the loop, and the application, running in a
    // server nobody is looking at. The console channel is the one sbt itself reads; anything
    // else is a client. `--once` is fine either way, since it ends on its own.
    // Without a terminal the loop has nobody to stop it: a pipe or a CI job would leave it, and
    // the application, running after the reader is gone. So no terminal means `--once`, or
    // `--plain`, which says the lines are what is wanted and that the reader will stop the loop.
    val channel   = state.currentCommand.flatMap(_.source).map(_.channelName)
    val onConsole = TerminalAccess.isConsole && channel.forall(_.startsWith("console"))
    if (!once && !onConsole) {
      System.out.println(clientRefusal(channel))
      return state.fail
    }
    if (!once && !plain && !interactive) {
      System.out.println(terminalRefusal)
      return state.fail
    }
    // The command is global, the settings it reads are the plugin's: at a project without it, the
    // root of a build of several applications for one, say so rather than throw.
    val stylesheet = EezoPlugin.autoImport.eezoTailwindInput
    if (extracted.getOpt(stylesheet).isEmpty) {
      val candidates = extracted.structure.allProjectRefs
        .filter(ref => extracted.getOpt(ref / stylesheet).isDefined)
        .map(_.project)
      System.out.println(projectRefusal(candidates))
      return state.fail
    }

    // The session the console's tasks run under: console logging off, since the screen is the
    // console's. Errors still reach the streams appender, so `last compile` works after a failed
    // build as it always did. Set in every scope a task's streams could read it from, because
    // sbt's own default is not in `Global`: sbt 1 sets it per project, and sbt 2 reads it closer
    // to the compile than that. The super shell is a state attribute, not a setting, and goes
    // separately below.
    val silent               = new PrintStream(new OutputStream { def write(b: Int): Unit = () })
    val project              = extracted.currentRef
    def quietOut: ConsoleOut = ConsoleOut.printStreamOut(silent)
    val quiet                = extracted.appendWithSession(
      Seq(
        Global / logManager            := LogManager.defaults(extraAppenders.value, quietOut),
        ThisBuild / logManager         := LogManager.defaults(extraAppenders.value, quietOut),
        project / logManager           := LogManager.defaults(extraAppenders.value, quietOut),
        project / Compile / logManager := LogManager.defaults(extraAppenders.value, quietOut),
        project / Compile / compile / logManager :=
          LogManager.defaults(extraAppenders.value, quietOut),
        project / Compile / compileIncremental / logManager :=
          LogManager.defaults(extraAppenders.value, quietOut)
      ),
      state
    )
    val still = TerminalAccess.withoutProgress(quiet)

    val base    = extracted.get(baseDirectory)
    val watched = extracted.get(Compile / unmanagedSourceDirectories) ++
      extracted.get(Compile / unmanagedResourceDirectories)
    // The stylesheet is the one input outside those directories: `eezoTailwind` reads it as a
    // resource generator, and `~` learns of it through `watchTriggers`, which this watch does not
    // read. Watched whether or not it exists yet, so adding Tailwind to a running project counts.
    val files            = Seq(extracted.get(stylesheet))
    val (s1, jvmOptions) = extracted.runTask(Compile / run / javaOptions, still)
    val loop             = new Loop(
      state = s1,
      project = extracted.get(name),
      base = base,
      watched = watched,
      files = files,
      ignored = Seq(extracted.get(target)),
      javaHome = extracted.get(Compile / run / javaHome),
      jvmOptions = jvmOptions,
      terminal = terminal,
      interactive = interactive,
      json = json,
      once = once,
      direct = onConsole
    )
    val ok = loop.run()
    if (once && !ok) state.fail else state
  }
}

/** What the loop hands a fork, kept apart from the fork itself so that a suite can follow a value
  * from the prompt that took it to the environment of the child: the fork needs a real JVM.
  */
private[sbt] object Loop {

  /** The only place a typed value leaves its holder, since the child must read the text itself. */
  def supplied(model: Model): Map[String, String] =
    model.supplied.map { case (variable, typed) => variable -> typed.reveal }
}

/** The one door the events of the application's children pass to the queue. A child's pumps outlive
  * its process, since stopping waits for the exit and not for the pipes, so what the old child
  * printed can still be in flight when the next one starts. Its log lines still pass, since the log
  * shows everything the application prints, a stack trace from a shutdown hook included. Its state
  * events, the config, routes and drift markers and the serving line, are dropped: behind the next
  * child's start they would pass for that child's own. The check and the add share the lock with
  * the swap, so no event slips between them.
  */
private[sbt] final class Handover(events: java.util.Queue[Event]) {
  private var current: Option[AnyRef] = None

  /** `next` becomes the child whose events count, and `started` is queued ahead of any of them. */
  def swap(next: AnyRef, started: Event): Unit = synchronized {
    current = Some(next)
    events.add(started): Unit
  }

  /** Queues what `child` produced; once a later child has taken over, only its log lines. */
  def from(child: AnyRef)(produced: => Vector[Event]): Unit = synchronized {
    val counts = current.exists(_ eq child)
    produced.foreach {
      case e: Event.AppLine => events.add(e): Unit
      case e if counts      => events.add(e): Unit
      case _                => ()
    }
  }

  /** The doors of `child`'s two pumps and of its waiter's flush, read by one protocol of its own
    * and each passed through [[from]], so that no pump of a child reaches the queue another way.
    */
  def pipes(child: AnyRef): Handover.Pipes = {
    val protocol = new LineProtocol
    Handover.Pipes(
      stdout = line => from(child)(protocol.synchronized(protocol.stdout(line))),
      stderr = line => from(child)(protocol.synchronized(protocol.stderr(line))),
      flush = () => from(child)(protocol.synchronized(protocol.flush()))
    )
  }
}

private[sbt] object Handover {

  /** What one child's stdout pump, stderr pump and waiter hand each line, and the end, to. */
  final case class Pipes(stdout: String => Unit, stderr: String => Unit, flush: () => Unit)
}

/** The loop and its threads. One instance per `eezoDev`; nothing here outlives the command. */
private[sbt] final class Loop(
    state: State,
    project: String,
    base: File,
    watched: Seq[File],
    files: Seq[File],
    ignored: Seq[File],
    javaHome: Option[File],
    jvmOptions: Seq[String],
    terminal: Terminal,
    interactive: Boolean,
    json: Boolean,
    once: Boolean,
    direct: Boolean
) {

  private val events   = new LinkedBlockingQueue[Event]
  private val handover = new Handover(events)
  private val stopping = new AtomicBoolean(false)
  // Fed by the keys thread, expired by the tick thread; each holds its monitor while it does.
  private val decoder  = new KeyDecoder
  private val asJson   = new JsonRenderer
  private val debounce =
    new Debounce[String](quietMillis = 100, clock = () => System.currentTimeMillis)

  @volatile private var built: Option[Builder.Built] = None
  @volatile private var building: Boolean            = false
  @volatile private var buildThread: Option[Thread]  = None
  @volatile private var child: Option[Process]       = None
  @volatile private var replacing: Boolean           = false
  @volatile private var outcome: Boolean             = false

  private implicit val theme: Ansi.Theme =
    Ansi.Theme.detect(
      terminal.isColorEnabled,
      sys.env.get,
      Option(System.getProperty("stdout.encoding"))
        .getOrElse(java.nio.charset.Charset.defaultCharset.name)
    )

  // The screen is written straight to the terminal. The lines go to sbt's own standard output,
  // the descriptor itself, when this sbt is the one at the console: sbt's `System.out` is a proxy
  // that swallows the error of a pipe whose reader is gone, and the loop must see that error to
  // stop. Behind a thin client, `--once` only, `System.out` is what sbt routes to the client.
  private val out =
    if (interactive) new PrintStream(terminal.outputStream, false, "UTF-8")
    else if (direct) new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8")
    else System.out

  private var model: Model =
    Model.initial(
      project,
      math.max(terminal.getWidth, 1),
      math.max(terminal.getHeight, 1),
      System.currentTimeMillis
    )

  def run(): Boolean = {
    val watcher = new Watcher(watched, files, ignored, path => debounce.offer(relative(path)))
    val ticker  = thread("eezo-dev-tick")(tick())
    try {
      watcher.start()
      ticker.start()
      if (interactive) screen() else plain()
    } finally {
      stopping.set(true)
      watcher.close()
      // A compile in flight runs on the quiet session; the shell must not have the terminal back,
      // and the next command must not run, while it does.
      buildThread.foreach(_.join())
      DevProcess.stop(quiet = true)
    }
    outcome
  }

  // ---- the two fronts ------------------------------------------------------------------------

  private def screen(): Unit = {
    val before  = TerminalAccess.attributes(terminal)
    val screen  = new Screen(s => { out.print(s); out.flush() })
    val restore = new Thread(
      () => {
        out.print(Screen.ShowCursor + Screen.LeaveAlternate); out.flush();
        TerminalAccess.setAttributes(terminal, before)
      },
      "eezo-dev-restore"
    )
    java.lang.Runtime.getRuntime.addShutdownHook(restore)
    TerminalAccess.withRawInput(terminal) {
      TerminalAccess.withoutSignals(terminal) {
        val keys = thread("eezo-dev-keys")(readKeys())
        screen.enter()
        try {
          keys.start()
          events.add(Event.Changed(Set.empty)): Unit
          while (!stopping.get) {
            drain(events.poll(100, TimeUnit.MILLISECONDS))
            screen.draw(View.frame(model), model.width, model.height)
          }
        } finally {
          stopping.set(true)
          // The reader is blocked in sbt's input stream; interrupting it leaves sbt's own pump
          // with one read outstanding, which sbt buffers for the next reader, the shell.
          keys.interrupt()
          screen.leave()
          try java.lang.Runtime.getRuntime.removeShutdownHook(restore): Unit
          catch { case _: IllegalStateException => () }
        }
      }
    }
  }

  private def plain(): Unit = {
    events.add(Event.Changed(Set.empty)): Unit
    while (!stopping.get) drain(events.poll(200, TimeUnit.MILLISECONDS))
  }

  /** Applies `first` and everything queued behind it. */
  private def drain(first: Event): Unit = {
    var e = first
    while (e != null) {
      step(e)
      e = events.poll()
    }
  }

  private def step(e: Event): Unit = {
    if (!interactive && !json) PlainRenderer.lines(e).foreach(emit)
    val (next, effects) = Update(model, e)
    if (json) asJson.lines(e, next, effects).foreach(emit)
    model = next
    effects.foreach(perform)
    if (once) e match {
      case Event.Serving(_) => outcome = true; stopping.set(true)
      case Event.AppExited(_) | Event.BuildFailed(_, _) | Event.BuildError(_) =>
        outcome = false; stopping.set(true)
      case _ => ()
    }
  }

  /** One line of the plain or JSON front. A `PrintStream` swallows the error of a closed pipe, so
    * it is asked for it: when the reader is gone, `head` done with its lines for one, the loop
    * stops rather than run on with the application behind it.
    */
  private def emit(line: String): Unit = {
    out.println(line)
    out.flush()
    if (out.checkError()) stopping.set(true)
  }

  // ---- effects -------------------------------------------------------------------------------

  private def perform(effect: Effect): Unit = effect match {
    case Effect.Build        => build()
    case Effect.Restart      => restart()
    case Effect.Run(command) => runCommand(command)
    case Effect.Quit         => stopping.set(true)
  }

  private def build(): Unit =
    if (!building) {
      building = true
      val t = thread("eezo-dev-build") {
        val started = System.currentTimeMillis
        val event   =
          try
            Builder.build(state, EezoPlugin.eezoDevClasspath, base) match {
              case Builder.Succeeded(b) =>
                built = Some(b);
                Event.BuildSucceeded(b.warnings, System.currentTimeMillis - started)
              case Builder.Failed(ds) => Event.BuildFailed(ds, System.currentTimeMillis - started)
              case Builder.Broken(message) => Event.BuildError(message)
            }
          finally building = false
        events.add(event): Unit
      }
      buildThread = Some(t)
      t.start()
    }

  private def restart(): Unit = built match {
    case None =>
      events.add(Event.AppLine("[eezo dev] nothing built yet to start", err = true)): Unit
    case Some(b) =>
      if (child.exists(_.isAlive)) events.add(Event.AppStopped): Unit
      replacing = true
      val process = DevProcess.launch(
        javaHome,
        jvmOptions,
        b.classpath,
        b.mainClass,
        Seq("dev"),
        base,
        piped = true,
        supplied = Loop.supplied(model)
      )
      // The new child is in place before `replacing` clears, so the old one's waiter, which checks
      // both, never reads its exit as the application's.
      child = Some(process)
      replacing = false
      // Queued before the pumps start, so that the child's config marker, which starts the report
      // of this child afresh, never lands ahead of it; and from here the old child's pumps, still
      // draining, pass only their log lines.
      handover.swap(process, Event.AppStarted(b.mainClass))
      val pipes = handover.pipes(process)
      thread("eezo-dev-stdout")(pump(process.getInputStream, pipes.stdout)).start()
      thread("eezo-dev-stderr")(pump(process.getErrorStream, pipes.stderr)).start()
      thread("eezo-dev-wait") {
        val code = process.waitFor()
        pipes.flush()
        if (child.contains(process) && !replacing && !stopping.get)
          events.add(Event.AppExited(code)): Unit
      }.start()
  }

  private def runCommand(command: SchemaCommand): Unit = built match {
    case None =>
      events.add(Event.AppLine("[eezo dev] nothing built yet to run it on", err = true)): Unit
    case Some(b) =>
      events.add(Event.CommandStarted(command)): Unit
      thread("eezo-dev-command") {
        val result =
          try SchemaCommands.run(javaHome, jvmOptions, b.classpath, b.mainClass, base, command)
          catch {
            case e: Exception =>
              Outcome(
                command,
                -1,
                None,
                Vector.empty,
                Vector(s"${e.getClass.getSimpleName}: ${e.getMessage}")
              )
          }
        events.add(Event.CommandFinished(result)): Unit
      }.start()
  }

  // ---- threads -------------------------------------------------------------------------------

  private def tick(): Unit =
    while (!stopping.get) {
      Thread.sleep(100)
      val now = System.currentTimeMillis
      events.add(Event.Tick(now)): Unit
      debounce.due().foreach(paths => events.add(Event.Changed(paths)): Unit)
      decoder.synchronized(decoder.expire(now)).foreach(k => events.add(Event.Pressed(k)): Unit)
      if (interactive) {
        val (w, h) = (terminal.getWidth, terminal.getHeight)
        if (w > 0 && h > 0 && (w != model.width || h != model.height))
          events.add(Event.Resized(w, h)): Unit
      }
    }

  /** Reads the terminal's input by blocking on it: sbt's input stream pumps a byte from the
    * terminal only while a reader is waiting in `read`, so this thread must always be back in
    * `read`, and nothing else about the stream, `available` included, says whether more is coming.
    * A lone Escape is therefore the tick thread's finding, through [[KeyDecoder.expire]].
    */
  private def readKeys(): Unit = {
    val in = terminal.inputStream
    try
      while (!stopping.get) {
        val b = in.read()
        if (b < 0) stopping.set(true)
        else
          decoder
            .synchronized(decoder.feed(b, System.currentTimeMillis))
            .foreach(k => events.add(Event.Pressed(k)): Unit)
      }
    catch {
      case _: InterruptedException                     => ()
      case _: java.nio.channels.ClosedChannelException => ()
      case _: java.io.IOException                      => ()
    }
  }

  private def pump(in: java.io.InputStream, deliver: String => Unit): Unit = {
    val reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))
    try {
      var line = reader.readLine()
      while (line != null) {
        deliver(line)
        line = reader.readLine()
      }
    } catch { case _: java.io.IOException => () }
    finally reader.close()
  }

  private def thread(name: String)(body: => Unit): Thread = {
    val t = new Thread(() => body, name)
    t.setDaemon(true)
    t
  }

  private lazy val realBase: Path = Watcher.real(base.toPath)

  /** `path`, as the watcher reports it, relative to the project: the label of a build. */
  private def relative(path: Path): String =
    try realBase.relativize(path).toString.replace(File.separatorChar, '/')
    catch { case _: IllegalArgumentException => path.toString }
}
