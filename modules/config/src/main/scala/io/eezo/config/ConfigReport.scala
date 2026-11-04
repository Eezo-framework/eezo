package io.eezo.config

import io.eezo.core.internal.Json

/** What boot learned of the Config, for the dev console: every variable the file names and whether
  * the environment holds it, and every offender of the read. Built from the one read boot does, so
  * that what the console shows is what boot decided, and a good value is installed once.
  *
  * Private to eezo, because only the dev arm of the http layer prints it, as the `config` marker,
  * and a type here that users could match on would be API for a dev only feature. It holds names,
  * paths, reasons and booleans, and never a value: `set` says that a variable is there, so the
  * marker can say what to supply without saying what was supplied.
  */
private[eezo] final case class ConfigReport(
    variables: List[ConfigReport.Variable],
    offenders: List[ConfigReport.Offender]
) {

  /** Boot's refusal, one line per offender: the lines it printed before the report existed. */
  def lines: List[String] = offenders.map(_.line)

  def json: Json =
    Json.Obj(
      List(
        "variables" -> Json.Arr(variables.map(_.json)),
        "offenders" -> Json.Arr(offenders.map(_.json))
      )
    )
}

private[eezo] object ConfigReport {

  /** One variable the file names. `set` is present in the environment, an empty value included,
    * since a substitution resolves on presence alone.
    */
  final case class Variable(name: Config.Name, set: Boolean) {

    def json: Json =
      Json.Obj(
        List(
          "variable" -> Json.Str(name.variable),
          "path"     -> Json.Str(name.path),
          "redacted" -> Json.Bool(name.redacted),
          "need"     -> Json.Str(need(name.need)),
          "set"      -> Json.Bool(set)
        )
      )
  }

  /** Why the application did not start: an offender of the read, or the file itself. */
  sealed trait Offender {
    def line: String
    def json: Json
  }

  /** An offender of the read, with the variables the file names for its path, so that the console
    * knows what to ask for a value that is missing or wrong, and among them the ones `feeding` the
    * value the read saw: for a malformed value, the ones a new value replaces, whatever shape the
    * file gives the path. A missing value none feeds.
    */
  final case class Read(offender: Config.Offender, variables: List[String], feeding: List[String])
      extends Offender {

    def line: String = Config.Refused(List(offender)).lines.mkString

    def json: Json = {
      val (kind, path, own) = offender match {
        case Config.Offender.Missing(path, _)        => ("missing", path, Nil)
        case Config.Offender.Malformed(path, reason) =>
          ("malformed", path, List("reason" -> Json.Str(reason)))
        case Config.Offender.Reserved(path, variable) =>
          ("reserved", path, List("variable" -> Json.Str(variable)))
        case Config.Offender.Literal(path) => ("literal", path, Nil)
      }
      Json.Obj(
        List(
          "kind"      -> Json.Str(kind),
          "path"      -> Json.Str(path),
          "variables" -> Json.Arr(variables.map(Json.Str(_))),
          "feeding"   -> Json.Arr(feeding.map(Json.Str(_)))
        ) ++ own :+ ("line" -> Json.Str(line))
      )
    }
  }

  /** `application.conf` itself: absent, or not parsing. Its line is the whole refusal, since no
    * path of the file can be read.
    */
  final case class File(kind: File.Kind, line: String) extends Offender {

    def json: Json =
      Json.Obj(
        List(
          "kind"      -> Json.Str(kind.toString.toLowerCase(java.util.Locale.ROOT)),
          "path"      -> Json.Str("application.conf"),
          "variables" -> Json.Arr(Nil),
          "feeding"   -> Json.Arr(Nil),
          "line"      -> Json.Str(line)
        )
      )
  }

  object File {
    enum Kind {
      case Absent, Unparsable
    }
  }

  /** The report of a file that parsed: `names` and `written` from the file, `refused` from the
    * read.
    */
  def of(
      names: Seq[Config.Name],
      written: Config.Written,
      env: Map[String, String],
      refused: Option[Config.Refused]
  ): ConfigReport = {
    def named(path: String): List[String] = written.variables(path).distinct
    def read(o: Config.Offender, path: String, variables: List[String]): Read =
      Read(o, variables, written.feeding(path, env))
    ConfigReport(
      names.toList.map(name => Variable(name, env.contains(name.variable))),
      refused.toList.flatMap(_.offenders).map {
        case o @ Config.Offender.Missing(path, variables) => read(o, path, variables)
        case o @ Config.Offender.Malformed(path, _)       => read(o, path, named(path))
        case o @ Config.Offender.Reserved(path, _)        => read(o, path, named(path))
        case o @ Config.Offender.Literal(path)            => read(o, path, named(path))
      }
    )
  }

  /** The fixed word `config` prints for a need, so the marker and the listing agree. */
  def need(need: Config.Need): String =
    need match {
      case Config.Need.Required  => "required"
      case Config.Need.Defaulted => "defaulted"
      case Config.Need.Optional  => "optional"
    }
}
