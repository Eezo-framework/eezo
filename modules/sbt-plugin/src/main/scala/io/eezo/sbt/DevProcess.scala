package io.eezo.sbt

import java.io.File
import java.util.concurrent.TimeUnit

/** The dev loop's process kernel: at most one forked application JVM, replaced on each restart.
  *
  * This is the ~100 lines `research/build-reload.md` §3.2 said eezo should write instead of
  * depending on sbt-revolver (sbt 1 only, dormant since 2023); revolver's process handling is the
  * reference for the corner cases here — kill before rebind, a JVM shutdown hook so an exiting sbt
  * never strands the app, `destroyForcibly` for a child that ignores the polite signal.
  *
  * State lives in this object rather than in sbt's state because the process must survive task
  * evaluations and die with the JVM; the shutdown hook is registered once, on first use. A `reload`
  * of the build makes a fresh classloader and a fresh object, which strands a running child — the
  * hook still reaps it at sbt exit, and a restart from the new loader binds a new one. Plain JDK
  * process APIs throughout, because this source compiles for sbt 1 on Scala 2.12 and sbt 2 on Scala 3.
  */
private[sbt] object DevProcess {

  @volatile private var running: Option[Process] = None

  private lazy val hook: Unit = {
    val reaper = new Thread(() => stop(quiet = true), "eezo-dev-reaper")
    java.lang.Runtime.getRuntime.addShutdownHook(reaper)
  }

  /** Kills the previous application, if any, then forks the next one.
    *
    * The kill completes before the fork starts, so the new process never races the old one for the
    * port: `research/http-server.md` measured the rebind itself at 0.7 ms, but only once the socket
    * is actually released.
    */
  def restart(
      javaHome: Option[File],
      jvmOptions: Seq[String],
      classpath: Seq[File],
      mainClass: String,
      args: Seq[String],
      workingDirectory: File,
      log: String => Unit
  ): Unit = {
    hook
    stop(quiet = true)

    val javaBin = javaHome
      .map(home => new File(new File(home, "bin"), "java"))
      .getOrElse(new File(new File(new File(sys.props("java.home")), "bin"), "java"))

    val command = new java.util.ArrayList[String]
    command.add(javaBin.getAbsolutePath)
    jvmOptions.foreach(option => command.add(option): Unit)
    command.add("-cp")
    command.add(classpath.map(_.getAbsolutePath).mkString(File.pathSeparator))
    command.add(mainClass)
    args.foreach(arg => command.add(arg): Unit)

    val builder = new ProcessBuilder(command)
    builder.directory(workingDirectory)
    // The app's output belongs in the same console as the compile that produced it; this is the
    // whole display of the dev loop.
    builder.inheritIO()

    val process = builder.start()
    running = Some(process)
    // `Process.pid` is Java 9+, and the sbt 1 axis compiles with `-release:8`, so no pid here.
    log(s"eezo dev: started $mainClass")
  }

  /** Stops the running application, politely and then not. */
  def stop(quiet: Boolean = false, log: String => Unit = _ => ()): Unit = {
    running.foreach { process =>
      if (process.isAlive) {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          process.waitFor(10, TimeUnit.SECONDS): Unit
        }
        if (!quiet) log("eezo dev: stopped")
      }
    }
    running = None
  }
}
