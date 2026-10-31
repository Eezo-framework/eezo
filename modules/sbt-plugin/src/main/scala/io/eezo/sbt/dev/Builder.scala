package io.eezo.sbt.dev

import sbt._
import sbt.Keys._

import java.io.File

/** One build, asked of sbt from the console: the application's runtime classpath, which compiles
  * the project first, and its main class. A failed compile comes back as diagnostics rather than as
  * sbt's log, because the console's log is silenced and the errors pane wants values.
  */
private[sbt] object Builder {

  /** What a build produced: the classpath to fork, the main class, and the warnings the analysis
    * holds for every source, not only the ones this increment compiled.
    */
  final case class Built(classpath: Seq[File], mainClass: String, warnings: Vector[Diagnostic])

  sealed trait Result
  final case class Succeeded(built: Built)                 extends Result
  final case class Failed(diagnostics: Vector[Diagnostic]) extends Result
  final case class Broken(message: String)                 extends Result

  def build(state: State, classpathKey: TaskKey[Seq[File]], base: File): Result =
    try {
      val extracted       = Project.extract(state)
      val (s1, classpath) = extracted.runTask(classpathKey, state)
      val (s2, main)      = extracted.runTask(Compile / run / mainClass, s1)
      val (_, analysis)   = extracted.runTask(Compile / compile, s2)
      main match {
        case None =>
          Broken(
            "no main class. Define an `object Main` extending `EezoApp`, `HttpApp` or `DbApp`."
          )
        case Some(mainClass) =>
          Succeeded(Built(classpath, mainClass, warnings(analysis, base)))
      }
    } catch {
      case incomplete: Incomplete =>
        val failed =
          Incomplete.allExceptions(incomplete).collect { case cf: xsbti.CompileFailed => cf }
        if (failed.isEmpty)
          Broken(
            Incomplete
              .allExceptions(incomplete)
              .headOption
              .map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")
              .getOrElse("the build failed without saying why; `sbt compile` has the log")
          )
        else Failed(failed.toVector.flatMap(_.problems.toVector).map(diagnostic(_, base)))
      case e: Exception => Broken(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  private def warnings(analysis: xsbti.compile.CompileAnalysis, base: File): Vector[Diagnostic] = {
    val infos = analysis.readSourceInfos().getAllSourceInfos
    val out   = Vector.newBuilder[Diagnostic]
    val it    = infos.values().iterator()
    while (it.hasNext) {
      val problems = it.next().getReportedProblems
      var i        = 0
      while (i < problems.length) {
        val d = diagnostic(problems(i), base)
        if (!d.isError) out += d
        i += 1
      }
    }
    out.result().sortBy(d => (d.path, d.line))
  }

  private[sbt] def diagnostic(p: xsbti.Problem, base: File): Diagnostic = {
    val position = p.position
    val path = if (position.sourcePath.isPresent) relative(position.sourcePath.get, base) else "?"
    val severity = p.severity match {
      case xsbti.Severity.Error => Severity.Error
      case xsbti.Severity.Warn  => Severity.Warning
      case _                    => Severity.Info
    }
    Diagnostic(
      path = path,
      line = if (position.line.isPresent) position.line.get.intValue else 0,
      column = if (position.pointer.isPresent) position.pointer.get.intValue + 1 else 0,
      severity = severity,
      message = p.message,
      // The compiler's own rendering names the file by its absolute path on its second line; the
      // project's prefix is cut there too, so the excerpt reads the way the header does.
      rendered =
        if (p.rendered.isPresent)
          Some(p.rendered.get.replace(base.getAbsolutePath + File.separator, ""))
        else None
    )
  }

  /** sbt names a source as `${BASE}/src/...` in its virtual file system, and as an absolute path
    * before 1.4; the console shows it relative to the project either way.
    */
  private[sbt] def relative(path: String, base: File): String = {
    val prefix = base.getAbsolutePath + File.separator
    if (path.startsWith("${BASE}/")) path.substring("${BASE}/".length)
    else if (path.startsWith(prefix)) path.substring(prefix.length)
    else path
  }
}
