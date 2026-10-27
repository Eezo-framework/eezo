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
private[sbt] object DevActions {

  /** How long a schema command may take before the console gives up on it. */
  val Timeout: Long = 120L

  def run(
      javaHome: Option[File],
      jvmOptions: Seq[String],
      classpath: Seq[File],
      mainClass: String,
      workingDirectory: File,
      action: Action
  ): Outcome = {
    val builder = new ProcessBuilder(
      DevProcess.command(javaHome, jvmOptions, classpath, mainClass, action.args)
    )
    builder.directory(workingDirectory)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    val process = builder.start()
    process.getOutputStream.close()
    val err  = drain(process.getErrorStream)
    val out  = readAll(process.getInputStream)
    val code =
      if (process.waitFor(Timeout, TimeUnit.SECONDS)) process.exitValue()
      else {
        process.destroyForcibly()
        -1
      }
    err.join(5000)
    val errLines = err.lines
    Outcome(
      action,
      code,
      JsonReader.parse(out.mkString("\n")).toOption,
      out,
      if (code == -1) errLines :+ s"gave up after ${Timeout}s" else errLines
    )
  }

  private final class Drain(in: java.io.InputStream) extends Thread("eezo-dev-action-stderr") {
    @volatile var lines: Vector[String] = Vector.empty
    setDaemon(true)
    start()
    override def run(): Unit = lines = readAll(in)
  }

  private def drain(in: java.io.InputStream): Drain = new Drain(in)

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
