package io.eezo.sbt.dev

/** The console as a stream of JSON objects, one per line, for `eezoDev --json`: the front an agent
  * or a tool reads. Every object has an `event` field. The build events share a `build` number,
  * counted from one per `eezoDev`, so a reader that saw `compiling` after its save knows which
  * `built` or `failed` answers it. Nothing is rendered for a key, a tick or a resize.
  *
  * The shapes mirror the lines the application prints under `EEZO_DEV_MARKERS`: `routes` is the
  * `routes --json` body, `drift` the `db status --json` body plus `blocking`, and a finished
  * command carries its own JSON answer as `result`.
  */
private[sbt] final class JsonRenderer {

  private var builds: Int = 0

  def lines(e: Event, after: Model, effects: Vector[Effect]): Vector[String] =
    objects(e, after, effects).map(JsonWriter.write)

  private def objects(e: Event, after: Model, effects: Vector[Effect]): Vector[JValue] = {
    val compiling =
      if (effects.contains(Effect.Build)) {
        builds += 1
        val trigger = after.build match {
          case BuildState.Compiling(t, _) => t
          case _                          => None
        }
        Vector(
          obj(
            "event"   -> JStr("compiling"),
            "build"   -> JNum(builds.toLong),
            "trigger" -> trigger.map(JStr(_)).getOrElse(JNull)
          )
        )
      } else Vector.empty
    compiling ++ (e match {
      case Event.BuildSucceeded(warnings, millis) =>
        Vector(
          obj(
            "event"    -> JStr("built"),
            "build"    -> JNum(builds.toLong),
            "millis"   -> JNum(millis),
            "warnings" -> JArr(warnings.map(diagnostic))
          )
        )
      case Event.BuildFailed(diagnostics, millis) =>
        Vector(
          obj(
            "event"       -> JStr("failed"),
            "build"       -> JNum(builds.toLong),
            "millis"      -> JNum(millis),
            "diagnostics" -> JArr(diagnostics.map(diagnostic))
          )
        )
      case Event.BuildError(message) =>
        Vector(
          obj("event" -> JStr("broken"), "build" -> JNum(builds.toLong), "message" -> JStr(message))
        )
      case Event.AppStarted(mainClass) =>
        Vector(obj("event" -> JStr("started"), "mainClass" -> JStr(mainClass)))
      case Event.AppExited(code) =>
        Vector(obj("event" -> JStr("exited"), "code" -> JNum(code.toLong)))
      case Event.AppStopped         => Vector(obj("event" -> JStr("stopped")))
      case Event.AppLine(text, err) =>
        Vector(
          obj(
            "event"  -> JStr("line"),
            "stream" -> JStr(if (err) "stderr" else "stdout"),
            "text"   -> JStr(text)
          )
        )
      case Event.Serving(url) => Vector(obj("event" -> JStr("serving"), "url" -> JStr(url)))
      case Event.RoutesReported(listing) =>
        Vector(
          obj(
            "event"      -> JStr("routes"),
            "routes"     -> JArr(listing.routes.map(route)),
            "overridden" -> JArr(listing.overridden.map(route)),
            "shadowed"   -> JArr(listing.shadowed.map { case (earlier, later) =>
              obj("earlier" -> route(earlier), "later" -> route(later))
            }),
            "orphans" -> JArr(
              listing.orphans.map(o =>
                obj(
                  "page"        -> JStr(o.page),
                  "pageRoute"   -> JStr(o.pageRoute),
                  "target"      -> JStr(o.target),
                  "targetRoute" -> JStr(o.targetRoute)
                )
              )
            )
          )
        )
      case Event.DriftReported(drift) =>
        Vector(
          obj(
            "event"    -> JStr("drift"),
            "blocking" -> JBool(drift.blocking),
            "changes"  -> JArr(
              drift.changes.map(c =>
                obj(
                  "describe"    -> JStr(c.describe),
                  "destructive" -> JBool(c.destructive),
                  "risky"       -> JBool(c.risky),
                  "sql"         -> JStr(c.sql)
                )
              )
            )
          )
        )
      case Event.ActionStarted(action) =>
        Vector(
          obj(
            "event"   -> JStr("command"),
            "command" -> JStr(action.label),
            "status"  -> JStr("started")
          )
        )
      case Event.ActionFinished(o) =>
        Vector(
          obj(
            "event"   -> JStr("command"),
            "command" -> JStr(o.action.label),
            "status"  -> JStr("finished"),
            "code"    -> JNum(o.code.toLong),
            "ok"      -> JBool(o.ok),
            "summary" -> JStr(Describe.summary(o)),
            "result"  -> o.json.getOrElse(JNull)
          )
        )
      case Event.Changed(_) | Event.BuildStarted(_) | Event.Pressed(_) | Event.Resized(_, _) |
          Event.Tick(_) =>
        Vector.empty
    })
  }

  private def diagnostic(d: Diagnostic): JValue =
    obj(
      "path"     -> JStr(d.path),
      "line"     -> JNum(d.line.toLong),
      "column"   -> JNum(d.column.toLong),
      "severity" -> JStr(d.severity match {
        case Severity.Error   => "error"
        case Severity.Warning => "warning"
        case Severity.Info    => "info"
      }),
      "message"  -> JStr(Ansi.strip(d.message)),
      "rendered" -> d.rendered.map(r => JStr(Ansi.strip(r))).getOrElse(JNull)
    )

  private def route(r: RouteRow): JValue =
    obj(
      "method"     -> JStr(r.method),
      "path"       -> JStr(r.path),
      "provenance" -> JStr(if (r.eezo) "eezo" else r.provenance),
      "api"        -> JBool(r.api)
    )

  private def obj(fields: (String, JValue)*): JValue = JObj(fields.toVector)
}
