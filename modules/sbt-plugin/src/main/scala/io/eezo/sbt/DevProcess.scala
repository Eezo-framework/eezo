package io.eezo.sbt

import java.io.File
import java.security.SecureRandom
import java.util.Base64
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

  /** The variable the http edge reads its secret from. Spelled again here rather than read off
    * `Secret.EnvVar`, because the plugin must not depend on the http module.
    */
  private[sbt] val SecretVar: String = "EEZO_SECRET"

  /** This sbt session's secret: 32 bytes from `SecureRandom`, base64 encoded so that it is text an
    * environment variable can carry and 44 UTF-8 bytes when the child parses it, above the 32 the
    * http edge requires. Generated on first use, so a build that never starts the dev loop never
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
    // `environment()` starts as a copy of sbt's own, so an inherited secret is already there.
    val environment = builder.environment()
    secretFor(Option(environment.get(SecretVar)))
      .foreach(secret => environment.put(SecretVar, secret): Unit)

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
