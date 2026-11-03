package io.eezo.config

import com.typesafe.config.{Config as Tree, ConfigException, ConfigFactory, ConfigParseOptions}

import io.eezo.core.Dispatch
import io.eezo.core.Dispatch.Usage
import io.eezo.core.internal.Json

/** The configuration read every entry trait does before the application starts, and the `config`
  * command. Users never name this trait: `HttpApp` and `DbApp` both extend it, so `EezoApp`, which
  * stacks the two, holds it once, and `help` lists `config` once.
  *
  * It lives here and not in `Dispatch` because `core` cannot see this module. It answers `Nil`
  * ahead of `Dispatch`, because a refusal is an exit code the command returns and `program` returns
  * none, and it leaves `program` alone, so the stacking refusal `Dispatch.program` relies on still
  * holds.
  *
  * The entry object names nothing: the build names the class, the plugin writes the object that
  * holds it, and [[Config.Root.generated]] finds that object. A build that names no class reads
  * nothing and starts as it did before this trait existed.
  */
trait ConfigApp extends Dispatch {

  /** The one place boot reads the process's environment, so a suite hands a map instead. */
  private[eezo] def environment: Map[String, String] = sys.env

  /** Reads and installs the Config before `body`, and on a refusal prints each offender and runs
    * nothing, so that no port is bound and no pool is opened for an application that cannot start.
    * The layers call it around everything that starts the application, `program` and `dev`, and
    * around nothing else: no other command reads a value.
    */
  protected final def configured(flags: List[String])(body: => Unit): Int =
    ConfigApp.refusal(environment) match {
      case Nil =>
        body
        0
      case lines => refuse(flags, lines)
    }

  /** One `fail` per offender, so each line carries the `[eezo] ` prefix, or under `--json` is an
    * object of its own.
    */
  private def refuse(flags: List[String], lines: List[String]): Int = {
    lines.foreach(fail(flags, _))
    1
  }

  override protected def commands: PartialFunction[List[String], Int] = ({
    case Nil               => configured(Nil)(program())
    case "config" :: flags => listing(flags)
  }: PartialFunction[List[String], Int]) orElse super.commands

  override protected def usage: List[Usage] =
    Usage("config", "the variables application.conf names, without reading them") :: super.usage

  /** `config`: the file's variables, for an operator setting up a deployment. It reads the file and
    * never the environment, so it answers the same before a single variable is set as after, and no
    * value can reach its output. A value still missing is not its concern; a file that is wrong
    * whatever the environment holds is, and then nothing is listed, since a list beside a refusal
    * reads as a go ahead.
    */
  private def listing(flags: List[String]): Int =
    ConfigApp.variables match {
      case Left(lines)  => refuse(flags, lines)
      case Right(names) =>
        // An empty list prints no line as text, and an empty array under `--json`, which a caller
        // parses.
        if (names.nonEmpty || json(flags))
          emit(flags)(ConfigApp.json(names), names.map(ConfigApp.row).mkString("\n"))
        0
    }
}

object ConfigApp {

  /** What boot prints in place of starting: nothing when the build names no class or the read
    * succeeded, which installed the value.
    */
  private[config] def refusal(env: Map[String, String]): List[String] =
    Config.Root.generated.fold(Nil) { root =>
      application match {
        case Left(line)  => List(line)
        case Right(file) => root.boot(file, env).fold(Nil)(_.lines.toList)
      }
    }

  private def variables: Either[List[String], Seq[Config.Name]] =
    Config.Root.generated.fold(Right(Nil)) { root =>
      application.left.map(List(_)).flatMap { file =>
        root.config.check(file) match {
          case Nil       => Right(root.config.names(file))
          case offenders => Left(Config.Refused(offenders).lines.toList)
        }
      }
    }

  /** One variable as four tab separated fields of fixed words, no header: the shell safe form a
    * launcher reads with `while IFS=$'\t' read -r`, without a JSON parser.
    */
  private def row(name: Config.Name): String =
    List(name.variable, name.path, redaction(name), need(name)).mkString("\t")

  private def redaction(name: Config.Name): String = if (name.redacted) "redacted" else "plain"

  private def need(name: Config.Name): String =
    name.need match {
      case Config.Need.Required  => "required"
      case Config.Need.Defaulted => "defaulted"
      case Config.Need.Optional  => "optional"
    }

  private def json(names: Seq[Config.Name]): String =
    Json.render(
      Json.Obj(
        List(
          "command"   -> Json.Str("config"),
          "variables" -> Json.Arr(
            names.toList.map(name =>
              Json.Obj(
                List(
                  "variable" -> Json.Str(name.variable),
                  "path"     -> Json.Str(name.path),
                  "redacted" -> Json.Bool(name.redacted),
                  "need"     -> Json.Str(need(name))
                )
              )
            )
          )
        )
      )
    )

  /** The application's own `application.conf`, parsed and unresolved: the first one the loader
    * finds, which is the application's, and nothing else. `ConfigFactory.load` and `parseResources`
    * would merge every `application.conf` on the classpath, `reference.conf`, the system properties
    * and `-Dconfig.file` into it, so a dependency could feed a value nobody committed and `config`
    * would list a file boot does not read. An absent file is a refusal and not an empty one, or a
    * class whose every field has a default would boot on nothing.
    */
  private def application: Either[String, Tree] =
    Option(Config.loader.getResource("application.conf")) match {
      case None =>
        Left(
          "application.conf is missing: the build names a Config class with eezoConfigClass, " +
            "and boot reads it from src/main/resources/application.conf"
        )
      case Some(url) =>
        try Right(ConfigFactory.parseURL(url, ConfigParseOptions.defaults().setAllowMissing(false)))
        catch { case e: ConfigException => Left(Config.unparsable(e)) }
    }
}
