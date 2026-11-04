package io.eezo.sbt.dev

import java.util.logging.Level

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
  * noise. Each stream holds its own header, since the two are read by two threads and a stdout line
  * can land between a header and its level. Their warnings and everything else pass through.
  */
private[sbt] final class LineProtocol {

  import LineProtocol._

  private var heldOut: Option[String] = None
  private var heldErr: Option[String] = None

  def stdout(line: String): Vector[Event] =
    if (line.startsWith(RoutesMarker))
      decode(line.substring(RoutesMarker.length), "routes")(j =>
        Event.RoutesReported(RouteListing.fromJson(j))
      )
    else if (line.startsWith(ConfigMarker))
      decode(line.substring(ConfigMarker.length), "config")(j =>
        Event.ConfigReported(ConfigReport.fromJson(j))
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

  /** The held record headers, when a stream ended on one. */
  def flush(): Vector[Event] = {
    val out = heldOut.toVector.map(h => Event.AppLine(h, err = false)) ++
      heldErr.toVector.map(h => Event.AppLine(h, err = true))
    heldOut = None
    heldErr = None
    out
  }

  private def decode(json: String, what: String)(event: JValue => Event): Vector[Event] =
    JsonReader.parse(json.trim) match {
      case Right(j)      => Vector(event(j))
      case Left(message) =>
        Vector(Event.AppLine(s"[eezo dev] unreadable $what marker: $message", err = true))
    }

  private def classify(line: String, err: Boolean): Vector[Event] = {
    val held                               = if (err) heldErr else heldOut
    def hold(header: Option[String]): Unit = if (err) heldErr = header else heldOut = header
    held match {
      case Some(header) =>
        hold(None)
        if (isNoiseLevel(line)) Vector.empty
        else Vector(Event.AppLine(header, err), Event.AppLine(line, err))
      case None =>
        if (isRecordHeader(line)) { hold(Some(line)); Vector.empty }
        else Vector(Event.AppLine(line, err))
    }
  }
}

private[sbt] object LineProtocol {

  val RoutesMarker: String  = "eezo dev: routes "
  val DriftMarker: String   = "eezo dev: drift "
  val ConfigMarker: String = "eezo dev: config "
  val ServingMarker: String = "serving on "

  /** A `java.util.logging` record header: `<date> <time> <logger class> <method>`, no parentheses,
    * so a stack frame (`at org.eclipse.jetty.X.y(File.java:1)`) never passes for one.
    */
  private val RecordHeader =
    "^[^(]* (org\\.eclipse\\.jetty|com\\.zaxxer\\.hikari)[A-Za-z0-9_.$]* [A-Za-z0-9_$<>]+$".r

  def isRecordHeader(line: String): Boolean = RecordHeader.findFirstIn(line).isDefined

  private val Routine: Vector[Level] =
    Vector(Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST)

  /** A level line of a routine record. `java.util.logging` spells the level in the JVM's own
    * language, `INFORMAZIONI:` on an Italian machine, and the child runs in the language this JVM
    * does, so the names are asked of the levels rather than spelled here.
    */
  def isNoiseLevel(line: String): Boolean = {
    val colon = line.indexOf(": ")
    colon > 0 && {
      val name = line.substring(0, colon)
      Routine.exists(level => level.getName == name || level.getLocalizedName == name)
    }
  }

  /** `lines` with the same records dropped, for a stream that was read whole rather than line by
    * line: the stderr of a schema command, which opens and closes a pool and logs four records
    * doing it.
    */
  def quiet(lines: Vector[String]): Vector[String] = {
    val kept = Vector.newBuilder[String]
    var i    = 0
    while (i < lines.size) {
      if (isRecordHeader(lines(i)) && i + 1 < lines.size && isNoiseLevel(lines(i + 1))) i += 2
      else {
        kept += lines(i)
        i += 1
      }
    }
    kept.result()
  }
}
