// Two package clauses, so that `DevProcess` and `EezoPlugin` are in scope by name, and so that
// this package's `Action` can be named `dev.Action` below: sbt 1 has an `sbt.Action` of its own,
// and the wildcard import of `sbt._` would shadow a sibling file's on one axis and not the other.
package io.eezo.sbt
package dev

import sbt._
import sbt.Keys._
import sbt.internal.LogManager
import sbt.internal.eezo.TerminalAccess
import sbt.internal.util.{ConsoleOut, Terminal}

import java.io.{BufferedReader, File, InputStreamReader, OutputStream, PrintStream}
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
  * `DevActions`. `Model`, `Update` and `View` decide everything; this file is the threads and the
  * terminal.
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
      System.out.println(
        s"[eezo dev] $Name runs on sbt's own terminal, and this sbt is a client of a server " +
          s"(channel ${channel.getOrElse("?")}). Run `eezo dev`, or `sbt --server $Name` on an " +
          s"sbt 2 project; `$Name --once` builds, starts and stops from anywhere."
      )
      return state.fail
    }
    if (!once && !plain && !interactive) {
      System.out.println(
        s"[eezo dev] $Name needs a terminal to draw on. `$Name --plain` runs the same loop " +
          s"printing lines and `$Name --json` one JSON object per line, for a pipe or an agent; " +
          s"`$Name --once` builds, starts and stops."
      )
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
    val files            = Seq(extracted.get(EezoPlugin.autoImport.eezoTailwindInput))
    val (s1, jvmOptions) = extracted.runTask(Compile / run / javaOptions, still)
    val loop             = new Loop(
      state = s1,
      project = extracted.get(name),
      base = base,
      watched = watched,
      files = files,
      javaHome = extracted.get(Compile / run / javaHome),
      jvmOptions = jvmOptions,
      terminal = terminal,
      interactive = interactive,
      json = json,
      once = once
    )
    val ok = loop.run()
    if (once && !ok) state.fail else state
  }
}

/** The loop and its threads. One instance per `eezoDev`; nothing here outlives the command. */
private[sbt] final class Loop(
    state: State,
    project: String,
    base: File,
    watched: Seq[File],
    files: Seq[File],
    javaHome: Option[File],
    jvmOptions: Seq[String],
    terminal: Terminal,
    interactive: Boolean,
    json: Boolean,
    once: Boolean
) {

  private val events   = new LinkedBlockingQueue[Event]
  private val stopping = new AtomicBoolean(false)
  // Fed by the keys thread, expired by the tick thread; each holds its monitor while it does.
  private val decoder  = new KeyDecoder
  private val asJson   = new JsonRenderer
  private val debounce =
    new Debounce[String](quietMillis = 100, clock = () => System.currentTimeMillis)

  @volatile private var built: Option[Builder.Built] = None
  @volatile private var building: Boolean            = false
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

  // The screen is written straight to the terminal; the plain lines go through `System.out`,
  // which sbt routes to whatever is reading its output, a pipe or a CI log included.
  private val out =
    if (interactive) new PrintStream(terminal.outputStream, false, "UTF-8") else System.out

  private var model: Model =
    Model.initial(
      project,
      math.max(terminal.getWidth, 1),
      math.max(terminal.getHeight, 1),
      System.currentTimeMillis
    )

  def run(): Boolean = {
    val watcher = new Watcher(watched, files, path => debounce.offer(relative(path)))
    val ticker  = thread("eezo-dev-tick")(tick())
    try {
      watcher.start()
      ticker.start()
      if (interactive) screen() else plain()
    } finally {
      stopping.set(true)
      watcher.close()
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
    if (!interactive && !json)
      PlainRenderer.lines(e).foreach(line => { out.println(line); out.flush() })
    val (next, effects) = Update(model, e)
    if (json)
      asJson.lines(e, next, effects).foreach(line => { out.println(line); out.flush() })
    model = next
    effects.foreach(perform)
    if (once) e match {
      case Event.Serving(_) => outcome = true; stopping.set(true)
      case Event.AppExited(_) | Event.BuildFailed(_, _) | Event.BuildError(_) =>
        outcome = false; stopping.set(true)
      case _ => ()
    }
  }

  // ---- effects -------------------------------------------------------------------------------

  private def perform(effect: Effect): Unit = effect match {
    case Effect.Build   => build()
    case Effect.Restart => restart()
    case Effect.Stop    =>
      replacing = true; DevProcess.stop(quiet = true); events.add(Event.AppStopped): Unit
    case Effect.Run(action) => action_(action)
    case Effect.Quit        => stopping.set(true)
  }

  private def build(): Unit =
    if (!building) {
      building = true
      thread("eezo-dev-build") {
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
      }.start()
    }

  private def restart(): Unit = built match {
    case None =>
      events.add(Event.AppLine("[eezo dev] nothing built yet to start", err = true)): Unit
    case Some(b) =>
      replacing = true
      val process = DevProcess.launch(
        javaHome,
        jvmOptions,
        b.classpath,
        b.mainClass,
        Seq("dev"),
        base,
        piped = true
      )
      replacing = false
      child = Some(process)
      val protocol = new LineProtocol
      thread("eezo-dev-stdout")(
        pump(process.getInputStream, line => protocol.synchronized(protocol.stdout(line)))
      ).start()
      thread("eezo-dev-stderr")(
        pump(process.getErrorStream, line => protocol.synchronized(protocol.stderr(line)))
      ).start()
      thread("eezo-dev-wait") {
        val code = process.waitFor()
        protocol.synchronized(protocol.flush()).foreach(e => events.add(e): Unit)
        if (child.contains(process) && !replacing && !stopping.get)
          events.add(Event.AppExited(code)): Unit
      }.start()
      events.add(Event.AppStarted(b.mainClass)): Unit
  }

  private def action_(action: dev.Action): Unit = built match {
    case None =>
      events.add(Event.AppLine("[eezo dev] nothing built yet to run it on", err = true)): Unit
    case Some(b) =>
      events.add(Event.ActionStarted(action)): Unit
      thread("eezo-dev-action") {
        val result =
          try DevActions.run(javaHome, jvmOptions, b.classpath, b.mainClass, base, action)
          catch {
            case e: Exception =>
              Outcome(
                action,
                -1,
                None,
                Vector.empty,
                Vector(s"${e.getClass.getSimpleName}: ${e.getMessage}")
              )
          }
        events.add(Event.ActionFinished(result)): Unit
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

  private def pump(in: java.io.InputStream, classify: String => Vector[Event]): Unit = {
    val reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))
    try {
      var line = reader.readLine()
      while (line != null) {
        classify(line).foreach(e => events.add(e): Unit)
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

  private def relative(path: Path): String =
    try base.toPath.relativize(path).toString.replace(File.separatorChar, '/')
    catch { case _: IllegalArgumentException => path.toString }
}
