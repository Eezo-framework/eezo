package io.eezo.http

import io.eezo.config.Config
import io.eezo.config.support.ConfigFileLoader
import io.eezo.core.Redacted
import io.eezo.core.html.Tags.p
import io.eezo.core.internal.Json
import io.eezo.core.support.Captured.captured

/** The http layer's entry trait: what `sbt "run <command>"` answers on an application that has only
  * this layer.
  *
  * The server itself is `EezoServerSuite`'s to test. What this suite pins is the dispatch: the
  * empty argument list is the application, `dev` goes through the `devServer` hook the umbrella
  * overrides, `routes` needs no server, and the database layer's commands do not exist here.
  */
class HttpAppSuite extends munit.FunSuite {
  import HttpAppSuite.*

  private val ok: Handler = _ => Response.Ok(p("ok"))

  private val table: RouteTable =
    RouteTable(Seq(Route.Http(Method.GET, PathPattern.parse("/"), ok)))

  /** An application that records which hook ran instead of binding a port. */
  private class Recording extends HttpApp {
    var booted    = false
    var developed = false

    override def routes: RouteTable = table

    override def boot(): Unit = booted = true

    override protected def devServer(): Unit = developed = true

    def framework: Seq[Route] = frameworkRoutes
  }

  test("no arguments is the application: boot runs") {
    val app = new Recording
    assertEquals(app.run(Nil), 0)
    assert(app.booted)
    assert(!app.developed)
  }

  test("dev runs the dev server hook, not boot") {
    val app = new Recording
    assertEquals(app.run(List("dev")), 0)
    assert(app.developed)
    assert(!app.booted)
  }

  test("routes prints the listing without a server, as text and as JSON") {
    val app             = new Recording
    val (code, text, _) = captured(app.run(List("routes")))
    assertEquals(code, 0)
    assert(text.contains("1 route:"), text)
    assert(text.contains("GET /"), text)

    val (jsonCode, json, _) = captured(app.run(List("routes", "--json")))
    assertEquals(jsonCode, 0)
    assert(json.contains("\"command\": \"routes\""), json)
    assert(!app.booted)
  }

  test("the database layer's commands are unknown on an http-only application") {
    val app = new Recording
    List("status", "sync", "freeze", "migrate", "reset", "drop", "dump", "ddl").foreach { command =>
      val (code, out, err) = captured(app.run(List("db", command)))
      assertEquals(code, 2, command)
      assertEquals(out, "", command)
      assert(err.contains(s"unknown command: db $command"), err)
    }
    assert(!app.booted)
  }

  test("help names this layer's two commands, in one column with help's own row") {
    val (code, out, _) = captured(new Recording().run(List("help")))
    assertEquals(code, 0)
    val lines = out.linesIterator.toList
    assert(
      lines.contains("  dev     serve with the route listing and the reload client on"),
      out
    )
    assert(lines.contains("  routes  the mounted table, with boot's warnings"), out)
    assert(lines.contains("  help    this list"), out)
    assert(!out.contains("status"), out)
  }

  /** The application, as one whose build names [[Kiosk]], with `env` for its environment. */
  private class Configured(env: Map[String, String]) extends Recording {
    var seen: Option[Kiosk] = None

    override private[eezo] def environment: Map[String, String] = env

    override def boot(): Unit = {
      booted = true
      seen = Some(Config[Kiosk].current)
    }
  }

  private def configured(app: Recording, args: List[String]) =
    ConfigFileLoader.generating(classOf[KioskFile.type], Some("kiosk.conf"))(
      captured(app.run(args))
    )

  test("a refused Config stops the application before boot: exit 1 and the line, no stack") {
    val app              = new Configured(Map.empty)
    val (code, out, err) = configured(app, Nil)
    assertEquals(code, 1)
    assertEquals(out, "")
    assertEquals(
      err.linesIterator.toList,
      List("[eezo] key is missing: set KIOSK_KEY, which application.conf names for it")
    )
    assert(!app.booted)
  }

  test("a refused Config stops dev before the dev server, with the same line") {
    val app            = new Configured(Map.empty)
    val (code, _, err) = configured(app, List("dev"))
    assertEquals(code, 1)
    assertEquals(
      err.linesIterator.toList,
      List("[eezo] key is missing: set KIOSK_KEY, which application.conf names for it")
    )
    assert(!app.developed)
  }

  test("a good read boots with the Config installed, and dev serves after it") {
    val app              = new Configured(Map("KIOSK_KEY" -> "k"))
    val (code, out, err) = configured(app, Nil)
    assertEquals(code, 0, err)
    assertEquals(out, "")
    assertEquals(app.seen, Some(Kiosk("hi", Redacted("k"))))
    assertEquals(configured(app, List("dev"))._1, 0)
    assert(app.developed)
  }

  test("help lists config once, and config prints nothing for a build that names no class") {
    val (code, out, _) = captured(new Recording().run(List("help")))
    assertEquals(code, 0)
    assertEquals(out.linesIterator.count(_.trim.startsWith("config")), 1, out)
    assertEquals(captured(new Recording().run(List("config"))), (0, "", ""))
  }

  /** The application, as one whose build names [[Booth]], with `env` for its environment. */
  private class Boothed(env: Map[String, String]) extends Recording {
    override private[eezo] def environment: Map[String, String] = env
  }

  private val Markers = Map("EEZO_DEV_MARKERS" -> "1")

  private def booth(env: Map[String, String], args: List[String], conf: Option[String]) =
    ConfigFileLoader.generating(classOf[BoothFile.type], conf)(
      captured(new Boothed(env).run(args))
    )

  /** The `config` markers among `out`'s lines, parsed. */
  private def markers(out: String): List[Json] =
    out.linesIterator
      .filter(_.startsWith("eezo dev: config "))
      .map(line =>
        Json.parse(line.stripPrefix("eezo dev: config ")).fold(e => fail(s"$e in $line"), identity)
      )
      .toList

  private def variable(name: String, path: String, redacted: Boolean, need: String, set: Boolean) =
    Json.Obj(
      List(
        "variable" -> Json.Str(name),
        "path"     -> Json.Str(path),
        "redacted" -> Json.Bool(redacted),
        "need"     -> Json.Str(need),
        "set"      -> Json.Bool(set)
      )
    )

  private def offender(kind: String, path: String, variables: List[String], rest: (String, Json)*) =
    Json.Obj(
      List(
        "kind"      -> Json.Str(kind),
        "path"      -> Json.Str(path),
        "variables" -> Json.Arr(variables.map(Json.Str(_)))
      ) ++ rest
    )

  private def offenders(marker: Json): List[Json] = marker match {
    case Json.Obj(fields) =>
      fields.toMap.get("offenders") match {
        case Some(Json.Arr(items)) => items
        case other                 => fail(s"no offenders in $marker: $other")
      }
    case other => fail(s"not an object: $other")
  }

  /** `marker`'s only offender, without its line once that line is `line`: the console shows a file
    * offender by boot's own words, so the marker must carry the `[eezo]` line verbatim.
    */
  private def only(marker: Json, line: String): Json = offenders(marker) match {
    case List(Json.Obj(fields)) =>
      assertEquals(fields.toMap.get("line"), Some(Json.Str(line)), marker)
      Json.Obj(fields.filterNot(_._1 == "line"))
    case other => fail(s"not one offender: $other")
  }

  test("dev with markers prints one config marker on a good boot: every variable, no offender") {
    val env              = Markers ++ Map("BOOTH_KEY" -> "k")
    val (code, out, err) = booth(env, List("dev"), Some("booth.conf"))
    assertEquals(code, 0, err)
    assertEquals(
      markers(out),
      List(
        Json.Obj(
          List(
            "variables" -> Json.Arr(
              List(
                variable("BOOTH_PORT", "port", redacted = false, "required", set = false),
                variable("BOOTH_KEY", "key", redacted = true, "required", set = true),
                variable("BOOTH_SECRET", "key", redacted = true, "required", set = false)
              )
            ),
            "offenders" -> Json.Arr(Nil)
          )
        )
      )
    )
  }

  /** A refused `dev` under markers: exit 1, the `[eezo]` line on stderr, and the one marker. */
  private def refused(env: Map[String, String], conf: Option[String]): (Json, String) = {
    val (code, out, err) = booth(Markers ++ env, List("dev"), conf)
    assertEquals(code, 1, err)
    val lines = err.linesIterator.toList
    assertEquals(lines.length, 1, err)
    assert(lines.head.startsWith("[eezo] "), err)
    markers(out) match {
      case List(marker) => (marker, lines.head.stripPrefix("[eezo] "))
      case other        => fail(s"not one marker: $other in $out")
    }
  }

  test("a missing value is a missing offender, with the variables the file names for it") {
    val (marker, line) = refused(Map.empty, Some("booth.conf"))
    assertEquals(only(marker, line), offender("missing", "key", List("BOOTH_KEY", "BOOTH_SECRET")))
    assertEquals(
      line,
      "key is missing: set BOOTH_KEY or BOOTH_SECRET, which application.conf names for it"
    )
  }

  test("a malformed value carries the Parser's reason and the variables named for it") {
    val (marker, line) =
      refused(Map("BOOTH_PORT" -> "eighty", "BOOTH_KEY" -> "k"), Some("booth.conf"))
    val reason = line.stripPrefix("port ")
    assertEquals(
      only(marker, line),
      offender("malformed", "port", List("BOOTH_PORT"), "reason" -> Json.Str(reason))
    )
  }

  test("a reserved variable, a stated Redacted and the file itself are offenders of their kind") {
    val (reserved, reservedLine) = refused(Map("BOOTH_KEY" -> "k"), Some("booth-reserved.conf"))
    assertEquals(
      only(reserved, reservedLine),
      offender("reserved", "port", Nil, "variable" -> Json.Str("EEZO_PORT"))
    )
    val (literal, literalLine) = refused(Map.empty, Some("booth-literal.conf"))
    assertEquals(only(literal, literalLine), offender("literal", "key", Nil))
    val (absent, absentLine) = refused(Map.empty, conf = None)
    assertEquals(only(absent, absentLine), offender("absent", "application.conf", Nil))
    val (unparsable, line) = refused(Map.empty, Some("booth-unparsable.conf"))
    assertEquals(only(unparsable, line), offender("unparsable", "application.conf", Nil))
    assert(line.startsWith("application.conf does not parse"), line)
  }

  test("the marker never carries a value, on a good boot or on a malformed one") {
    val sentinel = "booth_sentinel_secret"
    val good     = booth(Markers ++ Map("BOOTH_KEY" -> sentinel), List("dev"), Some("booth.conf"))
    val bad      = booth(
      Markers ++ Map("BOOTH_KEY" -> sentinel, "BOOTH_PORT" -> s"port_$sentinel"),
      List("dev"),
      Some("booth.conf")
    )
    List(good, bad).foreach { case (_, out, err) =>
      assertEquals(markers(out).length, 1, out)
      assert(!out.contains("sentinel"), out)
      assert(!err.contains("sentinel"), err)
    }
  }

  test("no config marker under run, under dev without markers, or for a build with no class") {
    val env = Map("BOOTH_KEY" -> "k")
    assertEquals(markers(booth(Markers ++ env, Nil, Some("booth.conf"))._2), Nil)
    assertEquals(markers(booth(env, List("dev"), Some("booth.conf"))._2), Nil)
    assertEquals(markers(booth(Map.empty, List("dev"), Some("booth.conf"))._2), Nil)
    val plain = ConfigFileLoader.plain(Some("booth.conf"))(
      captured(new Boothed(Markers).run(List("dev")))
    )
    assertEquals(plain._1, 0)
    assertEquals(markers(plain._2), Nil)
  }

  test("every server setting is an override with the server's own default") {
    val app = new Recording
    assertEquals(app.port, 8080)
    assertEquals(app.maxBodySize, HttpConfig.DefaultMaxBodySize)
    assert(!app.problems.isDefinedAt(new RuntimeException("anything")))
    assertEquals(app.layout, Layout.plain)
  }

  test("the asset route is a framework route, so every way of serving carries it") {
    assertEquals(new Recording().framework.map(_.describe), Seq("GET /assets/*file"))
  }
}

object HttpAppSuite {

  /** Read by this suite alone, since the installed value is global to the JVM. */
  final case class Kiosk(greeting: String, key: Redacted) derives Config

  object KioskFile extends Config.Root.Generated {
    val root: Config.Root[Kiosk] = Config.root[Kiosk]
  }

  /** Read by this suite alone, for the dev marker. */
  final case class Booth(port: Int, key: Redacted) derives Config

  object BoothFile extends Config.Root.Generated {
    val root: Config.Root[Booth] = Config.root[Booth]
  }
}
