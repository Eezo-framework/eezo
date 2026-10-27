package io.eezo.sbt.dev

/** Everything that can happen to the console, from every thread it has, as one value each. The
  * command's threads (the watcher, the child's two readers, the key reader, the tick, the action
  * runner) only ever enqueue one of these; the loop thread alone applies them to the model.
  */
private[sbt] sealed trait Event

private[sbt] object Event {

  /** The debounced set of files that changed, as paths relative to the project. */
  final case class Changed(paths: Set[String]) extends Event

  /** A build began, for `trigger` when a file set it off. */
  final case class BuildStarted(trigger: Option[String])                      extends Event
  final case class BuildSucceeded(warnings: Vector[Diagnostic], millis: Long) extends Event
  final case class BuildFailed(diagnostics: Vector[Diagnostic], millis: Long) extends Event

  /** The build could not even be asked for: no main class, an exception out of sbt. */
  final case class BuildError(message: String) extends Event

  final case class AppStarted(mainClass: String) extends Event
  final case class AppExited(code: Int)          extends Event
  case object AppStopped                         extends Event

  /** One line of the application's output; `err` for its stderr. */
  final case class AppLine(text: String, err: Boolean)   extends Event
  final case class Serving(url: String)                  extends Event
  final case class RoutesReported(listing: RouteListing) extends Event
  final case class DriftReported(drift: Drift)           extends Event

  final case class ActionStarted(action: Action)    extends Event
  final case class ActionFinished(outcome: Outcome) extends Event

  final case class Pressed(key: Key)                extends Event
  final case class Resized(width: Int, height: Int) extends Event

  /** Every 100 ms: the spinner's frame, the clock for elapsed times, the resize poll. */
  final case class Tick(now: Long) extends Event
}

/** What the model asks the command to do, in return for an event. The loop runs them in order. */
private[sbt] sealed trait Effect

private[sbt] object Effect {
  case object Build                    extends Effect
  case object Restart                  extends Effect
  case object Stop                     extends Effect
  final case class Run(action: Action) extends Effect
  case object Quit                     extends Effect
}
