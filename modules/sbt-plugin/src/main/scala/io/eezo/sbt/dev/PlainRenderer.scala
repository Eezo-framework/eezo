package io.eezo.sbt.dev

/** The console when there is no terminal: the same events, printed as lines, which is what the bash
  * console printed. For a pipe, a CI log, or `eezoDev --once`.
  */
private[sbt] object PlainRenderer {

  def lines(e: Event)(implicit theme: Ansi.Theme): Vector[String] = e match {
    case Event.Changed(paths) =>
      val trigger = paths.toVector.sorted.headOption.map(p => p.substring(p.lastIndexOf('/') + 1))
      Vector(s"compiling${trigger.map(t => s" ($t changed)").getOrElse("")}")
    case Event.BuildStarted(_)                  => Vector.empty
    case Event.BuildSucceeded(warnings, millis) =>
      s"${theme.ok} built in ${Update.seconds(millis)}" +: warnings.flatMap(diagnostic)
    case Event.BuildFailed(diagnostics, _) =>
      diagnostics.flatMap(
        diagnostic
      ) :+ s"${theme.fail} build failed; the previous build keeps serving. Fix it and save."
    case Event.BuildError(message)   => Vector(s"${theme.fail} $message")
    case Event.AppStarted(mainClass) => Vector(s"started $mainClass")
    case Event.AppExited(code)       => Vector(s"the application exited with code $code")
    case Event.AppLine(text, _)      => Vector(text)
    // The application prints its own listing and its own drift banner beside the markers, so the
    // values are not printed again.
    case Event.RoutesReported(_) | Event.DriftReported(_) => Vector.empty
    case Event.ActionStarted(action)                      => Vector(s"$$ eezo ${action.label}")
    case Event.ActionFinished(outcome)                    =>
      (Describe.outcome(outcome) ++ LineProtocol.quiet(outcome.err)) :+
        s"${if (outcome.ok) theme.ok else theme.fail} eezo ${outcome.action.label}: ${Describe.summary(outcome)}"
    case Event.Serving(_) | Event.AppStopped | Event.Pressed(_) | Event.Resized(_, _) |
        Event.Tick(_) =>
      Vector.empty
  }

  private def diagnostic(d: Diagnostic): Vector[String] =
    d.rendered.map(_.split('\n').toVector).getOrElse(Vector(s"${d.where}: ${d.message}"))
}
