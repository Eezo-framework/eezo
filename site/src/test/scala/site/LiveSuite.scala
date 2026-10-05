package site

import io.eezo.core.html.*
import io.eezo.http.Method
import io.eezo.http.Request
import io.eezo.http.Response
import io.eezo.http.Session
import io.eezo.live.Event
import munit.FunSuite

/** The two components and the theme handler, as pure functions of their inputs. */
class LiveSuite extends FunSuite {

  private val pages   = Pages.load(Content.current)
  private val current = Some(pages.bySlug("tutorials/a-live-page"))

  test("the drawer opens on toggle, closes on close, and ignores what it does not know") {
    assertEquals(Drawer.handle(Event("toggle"), false), true)
    assertEquals(Drawer.handle(Event("toggle"), true), false)
    assertEquals(Drawer.handle(Event("close"), true), false)
    assertEquals(Drawer.handle(Event("whatever"), true), true)
  }

  test("the drawer renders every section, marks the current page, and says whether it is open") {
    val closed = Drawer.render(false, pages, current).render
    val open   = Drawer.render(true, pages, current).render
    pages.sections.foreach(section => assert(closed.contains(section.name), section.name))
    assert(closed.contains("""href="/docs/tutorials/a-live-page" aria-current="page""""), closed)
    assert(closed.contains("""<h2><a href="/docs/how-to">How-to guides</a></h2>"""), closed)
    assert(closed.contains("""<h3 class="nav-group">The example applications</h3>"""), closed)
    assert(closed.contains("""href="/api/""""), closed)
    assert(closed.contains("""class="drawer""""), closed)
    assert(closed.contains("""aria-expanded="false""""), closed)
    assert(open.contains("""class="drawer open""""), open)
    assert(open.contains("""aria-expanded="true""""), open)
  }

  test("the counter counts and resets") {
    assertEquals(Clicks.handle(Event("inc"), 2), 3)
    assertEquals(Clicks.handle(Event("reset"), 9), 0)
    assert(Clicks.render(1).render.contains("<strong>1</strong> click,"))
    assert(Clicks.render(2).render.contains("<strong>2</strong> clicks,"))
  }

  private val index  = Search.build(pages, Content.current)
  private val chrome =
    new Page("/docs", Html.empty, _ => main("content"), () => index, Page.State.initial)

  test("the chrome moves one slice per event and leaves the others alone") {
    val start = Page.State.initial
    assertEquals(chrome.handle(Event("toggle"), start), start.copy(drawer = true))
    assertEquals(chrome.handle(Event("inc"), start), start.copy(clicks = 1))
    val open = chrome.handle(Event("search-open"), start)
    assertEquals(open, start.copy(search = Some("")))
    val typed = chrome.handle(Event("search", Map("value" -> "derives")), open)
    assertEquals(typed.search, Some("derives"))
    assertEquals(chrome.handle(Event("search-open"), typed), typed)
    assertEquals(chrome.handle(Event("search-close"), typed), start)
    assertEquals(
      chrome.handle(Event("search", Map("value" -> "x" * 500)), open).search.map(_.length),
      Some(Page.QueryLimit)
    )
  }

  test("the chrome renders the header's search button, and the dialog only when it is open") {
    val closed = chrome.render(Page.State.initial).render
    assert(closed.contains("""data-eezo-click="search-open""""), closed)
    assert(!closed.contains("search-panel"), closed)
    val empty = chrome.render(Page.State.searching).render
    assert(empty.contains("search-input"), empty)
    assert(empty.contains("Type to search"), empty)
    val found = chrome.render(Page.State.searching.copy(search = Some("derives"))).render
    assert(found.contains("<mark>derives</mark>"), found)
    assert(found.contains("""href="/docs/tutorials/first-model"""), found.take(3000))
    val none = chrome.render(Page.State.searching.copy(search = Some("zzzzqqq"))).render
    assert(none.contains("No page mentions"), none)
  }

  test("a search matches every term, ranks a title first, and excerpts around the first term") {
    val hits = index.query("case class")
    assert(hits.nonEmpty)
    assertEquals(hits.head.page.slug, "explanation/the-case-class")
    assert(hits.forall(hit => hit.excerpt.render.contains("<mark>")), hits.map(_.href))
    assert(hits.size <= Search.Limit)
    assert(hits.groupBy(_.page).values.forall(_.size <= Search.PerPage))
    val sectioned = index.query("drift gate")
    assert(sectioned.exists(_.href.contains("#")), sectioned.map(_.href))
    assertEquals(index.query("   "), Vector.empty)
    assertEquals(index.query("qqqqzzzz"), Vector.empty)
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
