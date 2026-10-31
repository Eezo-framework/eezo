package io.eezo.sbt.dev

/** The values the console holds: what a build reported, what the application reported at boot, and
  * what a schema command answered. Each is the plugin's own spelling of a shape the application
  * writes, decoded from the JSON the `--json` commands and the marker lines carry
  * (`docs/reference/cli.md`), because the plugin cannot see the application's types.
  */

private[sbt] sealed trait Severity
private[sbt] object Severity {
  case object Error   extends Severity
  case object Warning extends Severity
  case object Info    extends Severity
}

/** One compiler diagnostic. `rendered` is the compiler's own text for it, excerpt and caret
  * included, when the compiler supplied one; the console shows it under the one line header it
  * builds from the other fields.
  */
private[sbt] final case class Diagnostic(
    path: String,
    line: Int,
    column: Int,
    severity: Severity,
    message: String,
    rendered: Option[String]
) {
  def isError: Boolean = severity == Severity.Error

  /** `path:line:column`, with the parts that are unknown left out. */
  def where: String =
    if (line <= 0) path
    else if (column <= 0) s"$path:$line"
    else s"$path:$line:$column"
}

private[sbt] final case class RouteRow(
    method: String,
    path: String,
    provenance: String,
    api: Boolean
) {

  /** Under the prefix the framework reserves: a route eezo mounts for itself, the live socket and
    * its client script, the health check, rather than one the application wrote or derived. The
    * application reports those as handwritten, since the framework builds them the way a
    * handwritten route is built; the console names them for what they are.
    */
  def eezo: Boolean =
    path == RouteRow.ReservedPrefix || path.startsWith(RouteRow.ReservedPrefix + "/")
}

private[sbt] object RouteRow {

  /** `RouteTable.ReservedPrefix` in the http module, spelled again here because the plugin cannot
    * depend on it.
    */
  val ReservedPrefix: String = "/eezo"
}

private[sbt] final case class Orphan(
    page: String,
    pageRoute: String,
    target: String,
    targetRoute: String
)

/** `routes --json`, decoded. */
private[sbt] final case class RouteListing(
    routes: Vector[RouteRow],
    overridden: Vector[RouteRow],
    shadowed: Vector[(RouteRow, RouteRow)],
    orphans: Vector[Orphan]
)

private[sbt] object RouteListing {

  private def row(j: JValue): RouteRow =
    RouteRow(
      j("method").strOr("?"),
      j("path").strOr("?"),
      j("provenance").strOr(""),
      j("api").boolOr(false)
    )

  def fromJson(j: JValue): RouteListing =
    RouteListing(
      routes = j("routes").arr.map(row),
      overridden = j("overridden").arr.map(row),
      shadowed = j("shadowed").arr.map(s => (row(s("earlier")), row(s("later")))),
      orphans = j("orphans").arr.map(o =>
        Orphan(
          o("page").strOr("?"),
          o("pageRoute").strOr("?"),
          o("target").strOr("?"),
          o("targetRoute").strOr("?")
        )
      )
    )
}

/** One schema change, as `db status --json` describes it. */
private[sbt] final case class Change(
    describe: String,
    destructive: Boolean,
    risky: Boolean,
    sql: String
) {
  def additive: Boolean = !destructive && !risky
}

private[sbt] object Change {
  def fromJson(j: JValue): Change =
    Change(
      j("describe").strOr("?"),
      j("destructive").boolOr(false),
      j("risky").boolOr(false),
      j("sql").strOr("")
    )
}

/** The model against the database. `blocking` is what the dev server decided: the drift page is
  * served instead of the application. The status command does not carry it, so a status read on
  * demand derives it the way the server does, from the classes of the changes.
  */
private[sbt] final case class Drift(changes: Vector[Change], blocking: Boolean) {
  def inSync: Boolean             = changes.isEmpty
  def destructive: Vector[Change] = changes.filter(_.destructive)
  def dangerous: Vector[Change]   = changes.filter(c => c.destructive || c.risky)
}

private[sbt] object Drift {
  def fromJson(j: JValue): Drift = {
    val changes = j("changes").arr.map(Change.fromJson)
    Drift(changes, j("blocking").bool.getOrElse(changes.exists(c => c.destructive || c.risky)))
  }
}

/** A schema command the console runs as a one-off child, `Main db <args>`. */
private[sbt] sealed trait Action {
  def args: Vector[String]
  def label: String
}

private[sbt] object Action {

  case object Status extends Action {
    val args: Vector[String] = Vector("db", "status", "--json")
    val label: String        = "db status"
  }

  final case class Sync(force: Boolean) extends Action {
    def args: Vector[String] =
      Vector("db", "sync", "--apply") ++ (if (force) Vector("--force")
                                          else Vector.empty) :+ "--json"
    def label: String = if (force) "db sync --apply --force" else "db sync --apply"
  }

  /** `dropAll` is the freeze policy for destructive changes: `--accept-all` writes them into the
    * migration, `--skip-destructive`, which `--json` implies, keeps the data and skips them.
    */
  final case class Freeze(name: String, dropAll: Boolean) extends Action {
    def args: Vector[String] =
      Vector("db", "freeze") ++ name.split("\\s+").toVector.filter(_.nonEmpty) :+
        (if (dropAll) "--accept-all" else "--skip-destructive") :+ "--json"
    def label: String = s"db freeze $name"
  }

  final case class Migrate(apply: Boolean) extends Action {
    def args: Vector[String] =
      Vector("db", "migrate") ++ (if (apply) Vector("--apply") else Vector.empty) :+ "--json"
    def label: String = if (apply) "db migrate --apply" else "db migrate"
  }
}

/** What a child answered: its exit code, its stdout parsed when it was JSON, and the raw lines of
  * both streams for the log.
  */
private[sbt] final case class Outcome(
    action: Action,
    code: Int,
    json: Option[JValue],
    out: Vector[String],
    err: Vector[String]
) {
  def ok: Boolean = code == 0

  /** Exit 2 is `Dispatch`'s unknown command: the application has no database layer. */
  def unknownCommand: Boolean = code == 2
}
