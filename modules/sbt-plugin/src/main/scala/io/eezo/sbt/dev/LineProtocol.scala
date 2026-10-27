package io.eezo.sbt.dev

/** What the application's two output streams mean to the console, one line at a time.
  *
  * This is the dev loop's contract with the application, moved out of the bash console where
  * `dev_console` matched the same lines. Three lines on stdout are markers: `serving on <url>`,
  * which the server prints after its bind, and the two the application prints only when
  * `EEZO_DEV_MARKERS` is set, `eezo dev: routes <json>` and `eezo dev: drift <json>`, each one line
  * so that one line is one event. Everything else is the application's own output and lands in the
  * log as it is.
  *
  * On stderr the `java.util.logging` records Jetty and HikariCP write at INFO on every boot are
  * dropped, as the bash console dropped them: a record is two lines, a header naming the logger
  * class and a level line, and the header is held until the level line says whether the pair is
  * noise. Their warnings and everything else pass through.
  */
private[sbt] final class LineProtocol {

  import LineProtocol._

  private var held: Option[String] = None

  def stdout(line: String): Vector[Event] =
    if (line.startsWith(RoutesMarker))
      decode(line.substring(RoutesMarker.length), "routes")(j =>
        Event.RoutesReported(RouteListing.fromJson(j))
      )
    else if (line.startsWith(DriftMarker))
      decode(line.substring(DriftMarker.length), "drift")(j =>
        Event.DriftReported(Drift.fromJson(j))
      )
    else if (line.startsWith(ServingMarker))
      Vector(
        Event.Serving(line.substring(ServingMarker.length).trim),
        Event.AppLine(line, err = false)
      )
    else classify(line, err = false)

  def stderr(line: String): Vector[Event] = classify(line, err = true)

  /** The held record header, when the stream ended on one. */
  def flush(): Vector[Event] = {
    val out = held.toVector.map(h => Event.AppLine(h, err = true))
    held = None
    out
  }

  private def decode(json: String, what: String)(event: JValue => Event): Vector[Event] =
    JsonReader.parse(json.trim) match {
      case Right(j)      => Vector(event(j))
      case Left(message) =>
        Vector(Event.AppLine(s"[eezo dev] unreadable $what marker: $message", err = true))
    }

  private def classify(line: String, err: Boolean): Vector[Event] =
    held match {
      case Some(header) =>
        held = None
        if (isNoiseLevel(line)) Vector.empty
        else Vector(Event.AppLine(header, err = true), Event.AppLine(line, err))
      case None =>
        if (isRecordHeader(line)) { held = Some(line); Vector.empty }
        else Vector(Event.AppLine(line, err))
    }
}

private[sbt] object LineProtocol {

  val RoutesMarker: String  = "eezo dev: routes "
  val DriftMarker: String   = "eezo dev: drift "
  val ServingMarker: String = "serving on "

  /** A `java.util.logging` record header: `<date> <time> <logger class> <method>`, no parentheses,
    * so a stack frame (`at org.eclipse.jetty.X.y(File.java:1)`) never passes for one.
    */
  private val RecordHeader =
    "^[^(]* (org\\.eclipse\\.jetty|com\\.zaxxer\\.hikari)[A-Za-z0-9_.$]* [A-Za-z0-9_$<>]+$".r

  def isRecordHeader(line: String): Boolean = RecordHeader.findFirstIn(line).isDefined

  def isNoiseLevel(line: String): Boolean =
    line.startsWith("INFO: ") || line.startsWith("CONFIG: ") || line.startsWith("FINE: ")
}
