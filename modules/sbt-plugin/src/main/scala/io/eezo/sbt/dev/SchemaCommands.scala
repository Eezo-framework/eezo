package io.eezo.sbt.dev

import io.eezo.sbt.DevProcess

import java.io.{BufferedReader, File, InputStreamReader}
import java.util.concurrent.TimeUnit

/** The schema commands, run as the console runs them: a one-off child JVM on the classpath the last
  * build produced, `Main db <command> --json`, stdin closed, both streams read whole, the stdout
  * parsed when it is JSON.
  *
  * This is the same `Dispatch` the launcher and the deploy's release command go through, with the
  * documented exit codes: 0 done, 1 the command's answer (drift, a refusal), 2 an unknown command,
  * which is how the console learns an application has no database layer. `--json` on `freeze`
  * already implies `--skip-destructive` unless `--accept-all`, so the interactive prompt in the
  * child is never reached and a closed stdin is right.
  */
private[sbt] object SchemaCommands {

  /** How long a schema command may take before the console gives up on it. */
  val Timeout: Long = 120L

  def run(
      javaHome: Option[File],
      jvmOptions: Seq[String],
      classpath: Seq[File],
      mainClass: String,
      workingDirectory: File,
      command: SchemaCommand
  ): Outcome =
    execute(
      DevProcess.command(javaHome, jvmOptions, classpath, mainClass, command.args),
      workingDirectory,
      command,
      Timeout
    )

  /** Runs `argv` and waits at most `timeoutSeconds` for it. Both streams are drained on threads of
    * their own while the process runs, so the wait is on the process and not on its stdout: a child
    * that hangs with its pipe open is killed at the timeout and answers `-1`, with what it had
    * printed by then.
    */
  private[dev] def execute(
      argv: java.util.List[String],
      workingDirectory: File,
      command: SchemaCommand,
      timeoutSeconds: Long
  ): Outcome = {
    val builder = new ProcessBuilder(argv)
    builder.directory(workingDirectory)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    val process = builder.start()
    process.getOutputStream.close()
    val out  = new Drain(process.getInputStream, "eezo-dev-command-stdout")
    val err  = new Drain(process.getErrorStream, "eezo-dev-command-stderr")
    val code =
      if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.exitValue()
      else {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS): Unit
        -1
      }
    out.join(5000)
    err.join(5000)
    Outcome(
      command,
      code,
      JsonReader.parse(out.lines.mkString("\n")).toOption,
      out.lines,
      if (code == -1) err.lines :+ s"gave up after ${timeoutSeconds}s" else err.lines
    )
  }

  private final class Drain(in: java.io.InputStream, name: String) extends Thread(name) {
    @volatile var lines: Vector[String] = Vector.empty
    setDaemon(true)
    start()
    override def run(): Unit = lines = readAll(in)
  }

  private def readAll(in: java.io.InputStream): Vector[String] = {
    val reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))
    val lines  = Vector.newBuilder[String]
    try {
      var line = reader.readLine()
      while (line != null) {
        lines += line
        line = reader.readLine()
      }
    } catch { case _: java.io.IOException => () }
    finally reader.close()
    lines.result()
  }
}
