package site

import io.eezo.http.Method
import io.eezo.http.Request
import io.eezo.http.Response
import io.eezo.http.Session
import io.eezo.live.Event
import munit.FunSuite

/** The two components and the theme handler, as pure functions of their inputs. */
class LiveSuite extends FunSuite {

  private val pages  = Pages.load(Content.current)
  private val drawer = new Drawer(pages, pages.bySlug("live"))

  test("the drawer opens on toggle, closes on close, and ignores what it does not know") {
    assertEquals(drawer.handle(Event("toggle"), false), true)
    assertEquals(drawer.handle(Event("toggle"), true), false)
    assertEquals(drawer.handle(Event("close"), true), false)
    assertEquals(drawer.handle(Event("whatever"), true), true)
  }

  test("the drawer renders every section, marks the current page, and says whether it is open") {
    val closed = drawer.render(false).render
    val open   = drawer.render(true).render
    pages.sections.foreach(section => assert(closed.contains(s"<h2>${section.name}</h2>")))
    assert(closed.contains("""href="/docs/live" aria-current="page""""), closed)
    assert(closed.contains("""class="drawer""""), closed)
    assert(closed.contains("""aria-expanded="false""""), closed)
    assert(open.contains("""class="drawer open""""), open)
    assert(open.contains("""aria-expanded="true""""), open)
  }

  test("the counter counts and resets") {
    val clicks = new Clicks
    assertEquals(clicks.handle(Event("inc"), 2), 3)
    assertEquals(clicks.handle(Event("reset"), 9), 0)
    assert(clicks.render(1).render.contains("<strong>1</strong> click,"))
    assert(clicks.render(2).render.contains("<strong>2</strong> clicks,"))
  }

  private def post(fields: (String, String)*): Request =
    Request(
      Method.POST,
      "/theme",
      Map.empty,
      Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      fields.map { case (k, v) => s"$k=$v" }.mkString("&").getBytes,
      Map.empty,
      Session.empty.set(Theme.Key, "light")
    )

  private def sessionOf(response: Response): Session =
    response.session.getOrElse(fail("the response keeps no session"))

  test("the theme handler stores the choice and goes back") {
    val response = app.theme.Create.create(post("to" -> "dark", "back" -> "/docs/live"))
    assertEquals(response.status, 303)
    assertEquals(response.header("Location"), Some("/docs/live"))
    assertEquals(sessionOf(response).get(Theme.Key), Some("dark"))
  }

  test("the theme handler refuses an unknown theme and an off-site return address") {
    intercept[io.eezo.http.BadRequest](app.theme.Create.create(post("to" -> "sepia")))
    val elsewhere = app.theme.Create.create(post("to" -> "light", "back" -> "//evil.example/x"))
    assertEquals(elsewhere.header("Location"), Some("/"))
  }

  test("a chosen theme is rendered on the document, and none when nothing was chosen") {
    val chosen = Request(Method.GET, "/", Map.empty, Map.empty, Array.empty, Map.empty)
      .copy(session = Session.empty.set(Theme.Key, "dark"))
    assertEquals(Theme.current(chosen), Some("dark"))
    val fresh = Request(Method.GET, "/", Map.empty, Map.empty, Array.empty, Map.empty)
    assertEquals(Theme.current(fresh), None)
  }
}
