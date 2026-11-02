package io.eezo.sbt

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The dev loop's process kernel: at most one forked application JVM, replaced on each restart.
  *
  * This is the ~100 lines `research/build-reload.md` §3.2 said eezo should write instead of
  * depending on sbt-revolver (sbt 1 only, dormant since 2023); revolver's process handling is the
  * reference for the corner cases here: kill before rebind, a JVM shutdown hook so an exiting sbt
  * never strands the app, `destroyForcibly` for a child that ignores the polite signal.
  *
  * State lives in this object rather than in sbt's state because the process must survive task
  * evaluations and die with the JVM; the shutdown hook is registered once, on first use. A `reload`
  * of the build makes a fresh classloader and a fresh object, which strands a running child; the
  * hook still reaps it at sbt exit, and a restart from the new loader binds a new one. Plain JDK
  * process APIs throughout, because this source compiles for sbt 1 on Scala 2.12 and sbt 2 on Scala 3.
  *
  * The object also holds the dev loop's application secret. The session cookie is signed with it
  * and read on every request, and a child left to generate its own would sign the developer out on
  * every edit, because every edit is a new JVM. So the secret is generated here, once per sbt
  * session, and handed to each child as `EEZO_SECRET`, unless sbt's own environment already sets
  * one, which the child then inherits. A `reload` makes a fresh object and so a fresh secret, which
  * signs the developer out once; that is accepted. The value is never logged.
  */
private[sbt] object DevProcess {

  @volatile private var running: Option[Process] = None

  /** The variable the http layer reads its secret from. Spelled again here rather than read off
    * `Secret.EnvVar`, because the plugin must not depend on the http module.
    */
  private[sbt] val SecretVar: String = "EEZO_SECRET"

  /** This sbt session's secret: 32 bytes from `SecureRandom`, base64 encoded so that it is text an
    * environment variable can carry and 44 UTF-8 bytes when the child parses it, above the 32 the
    * http layer requires. Generated on first use, so a build that never starts the dev loop never
    * makes one.
    */
  private lazy val sessionSecret: String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)
  }

  /** What the child's `EEZO_SECRET` should be set to: nothing when sbt's environment already has
    * one, since the child inherits it, and this session's secret otherwise.
    */
  private[sbt] def secretFor(inherited: Option[String]): Option[String] =
    if (inherited.isDefined) None else Some(sessionSecret)

  private lazy val hook: Unit = {
    val reaper = new Thread(() => stop(quiet = true), "eezo-dev-reaper")
    java.lang.Runtime.getRuntime.addShutdownHook(reaper)
  }

  /** The variable that turns the application's marker lines on (`DevMarkers` in the http module,
    * spelled again here for the reason `SecretVar` is): set on every child the console forks.
    */
  private[sbt] val MarkersVar: String = "EEZO_DEV_MARKERS"

  /** Kills the previous application, if any, then forks the next one with its output inherited into
    * sbt's console: the `eezoRestart` task's kernel, for `sbt eezoRestart` by hand.
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
    launch(javaHome, jvmOptions, classpath, mainClass, args, workingDirectory, piped = false): Unit
    log(s"eezo dev: started $mainClass")
  }

  /** The dev console's fork: the previous application killed, the next one started with its stdout
    * and stderr piped to the caller, each its own stream so a marker line on stdout can never be
    * split by a log record on stderr, its stdin closed, since the console owns the terminal, and
    * the marker lines turned on. The caller drains both pipes.
    */
  def launch(
      javaHome: Option[File],
      jvmOptions: Seq[String],
      classpath: Seq[File],
      mainClass: String,
      args: Seq[String],
      workingDirectory: File,
      piped: Boolean
  ): Process = {
    hook
    stop(quiet = true)

    val builder = new ProcessBuilder(command(javaHome, jvmOptions, classpath, mainClass, args))
    builder.directory(workingDirectory)
    if (piped) {
      builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
      builder.redirectError(ProcessBuilder.Redirect.PIPE)
      builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    } else
      // The app's output belongs in the same console as the compile that produced it: inherited,
      // so that a reader of sbt's output reads the app's with it.
      builder.inheritIO()
    // `environment()` starts as a copy of sbt's own, so an inherited secret is already there.
    val environment = builder.environment()
    secretFor(Option(environment.get(SecretVar)))
      .foreach(secret => environment.put(SecretVar, secret): Unit)
    if (piped) environment.put(MarkersVar, "1"): Unit

    val process = builder.start()
    if (piped) process.getOutputStream.close()
    running = Some(process)
    process
  }

  /** `java <options> -cp <classpath> <main> <args>`, the one spelling every fork and every one-off
    * command of the console uses.
    */
  private[sbt] def command(
      javaHome: Option[File],
      jvmOptions: Seq[String],
      classpath: Seq[File],
      mainClass: String,
      args: Seq[String]
  ): java.util.List[String] = {
    val command = new java.util.ArrayList[String]
    command.add(javaBin(javaHome))
    jvmOptions.foreach(option => command.add(option): Unit)
    command.add("-cp")
    command.add(classpath.map(_.getAbsolutePath).mkString(File.pathSeparator))
    command.add(mainClass)
    args.foreach(arg => command.add(arg): Unit)
    command
  }

  private def javaBin(javaHome: Option[File]): String =
    javaHome
      .map(home => new File(new File(home, "bin"), "java"))
      .getOrElse(new File(new File(new File(sys.props("java.home")), "bin"), "java"))
      .getAbsolutePath

  /** Whether the application the console started is still alive. */
  def alive: Boolean = running.exists(_.isAlive)

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
