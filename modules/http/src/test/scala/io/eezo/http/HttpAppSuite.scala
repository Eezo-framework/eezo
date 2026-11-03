package io.eezo.http

import io.eezo.config.Config
import io.eezo.config.support.ConfigFileLoader
import io.eezo.core.Redacted
import io.eezo.core.html.Tags.p
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
}
