package io.eezo.live

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*

/** The page lifecycle, on an injected clock: capped registration, one socket per page, the
  * never-connected TTL, the disconnect grace window, and reaping that closes what it removes.
  */
class RegistrySuite extends munit.FunSuite {

  private object Noop extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = n
    def render(n: Int): Html              = div(span(n))
  }

  private def mounted(id: String): Page[Int] = {
    val page = new Page(id, Noop, _ => ())
    val _    = page.mount()
    page
  }

  private def registry(now: AtomicLong, cap: Int = 4) =
    new PageRegistry(
      cap = cap,
      neverConnectedTtl = Duration.ofSeconds(30),
      disconnectedGrace = Duration.ofSeconds(60),
      clock = () => now.get()
    )

  test("registration mints distinct 128-bit ids and stops at the cap") {
    val now = new AtomicLong(0)
    val reg = registry(now, cap = 3)

    val pages = (1 to 3).map(_ => reg.register(None, mounted).get)
    assertEquals(pages.map(_.id).distinct.size, 3)
    pages.foreach(page => assert(page.id.matches("[0-9a-f]{32}"), page.id))

    assertEquals(reg.register(None, mounted), None)
    assertEquals(reg.size, 3)
  }

  test("connect claims the page once; a second socket is refused, not shared") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(None, mounted).get

    assertEquals(reg.connect(page.id, None).map(_.id), Right(page.id))
    assertEquals(reg.connect(page.id, None), Left(PageRegistry.ConnectRefusal.AlreadyConnected))
    assertEquals(reg.connect("0" * 32, None), Left(PageRegistry.ConnectRefusal.Unknown))
  }

  test("an unbound page admits a named socket too, since it is bound to nobody") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(None, mounted).get

    assertEquals(reg.connect(page.id, Some("alice")).map(_.id), Right(page.id))
  }

  test("a bound page admits its own user's socket and refuses every other") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(Some("alice"), mounted).get

    assertEquals(reg.connect(page.id, None), Left(PageRegistry.ConnectRefusal.NotTheUser))
    assertEquals(
      reg.connect(page.id, Some("mallory")),
      Left(PageRegistry.ConnectRefusal.NotTheUser)
    )
    assertEquals(reg.connect(page.id, Some("alice")).map(_.id), Right(page.id))
  }

  test("the wrong user is answered before the already connected check, so liveness never leaks") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(Some("alice"), mounted).get
    assert(reg.connect(page.id, Some("alice")).isRight)

    assertEquals(
      reg.connect(page.id, Some("mallory")),
      Left(PageRegistry.ConnectRefusal.NotTheUser),
      "a connected page told a stranger it was connected"
    )

    assertEquals(
      reg.connect(page.id, Some("alice")),
      Left(PageRegistry.ConnectRefusal.AlreadyConnected),
      "the page's own user opening a second socket should be refused, not shared"
    )
  }

  test("refusing the wrong user leaves the page exactly as it was, still joinable by its own") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(Some("alice"), mounted).get

    assertEquals(
      reg.connect(page.id, Some("mallory")),
      Left(PageRegistry.ConnectRefusal.NotTheUser)
    )

    // Still NeverConnected: the refusal neither claimed the slot nor started a grace window, so
    // the TTL is the one that was running before the stranger knocked.
    now.set(29_999)
    assertEquals(reg.reap(), Nil)
    assertEquals(reg.connect(page.id, Some("alice")).map(_.id), Right(page.id))
  }

  test("a page nobody ever connects to is reaped after its TTL, and closed by the reap") {
    val now   = new AtomicLong(0)
    val reg   = registry(now)
    val page  = reg.register(None, mounted).get
    val other = reg.register(None, mounted).get
    assertEquals(reg.connect(other.id, None).isRight, true)

    now.set(29_999)
    assertEquals(reg.reap(), Nil)

    now.set(30_000)
    assertEquals(reg.reap(), List(page.id))
    assertEquals(reg.size, 1)
    intercept[IllegalStateException](page.event(Event("click"))) // closed by the reap

    // The connected page is never TTL-reaped.
    now.set(1_000_000)
    assertEquals(reg.reap(), Nil)
  }

  test("a dropped socket keeps its page for the grace window; a rejoin inside it works") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(None, mounted).get
    assert(reg.connect(page.id, None).isRight)

    now.set(10_000)
    reg.disconnect(page.id)

    now.set(10_000 + 59_999)
    assertEquals(reg.reap(), Nil)
    assert(reg.connect(page.id, None).isRight, "a rejoin inside the grace window reclaims the page")

    reg.disconnect(page.id)
    now.set(10_000 + 59_999 + 60_000)
    assertEquals(reg.reap(), List(page.id))
    assertEquals(reg.size, 0)
  }

  test("a clean close frees immediately and closes the page") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(None, mounted).get

    reg.close(page.id)
    assertEquals(reg.size, 0)
    assertEquals(reg.connect(page.id, None), Left(PageRegistry.ConnectRefusal.Unknown))
    intercept[IllegalStateException](page.event(Event("click")))
  }

  test("disconnect on an unknown or never-connected page is a quiet no-op") {
    val now  = new AtomicLong(0)
    val reg  = registry(now)
    val page = reg.register(None, mounted).get

    reg.disconnect("0" * 32)
    reg.disconnect(page.id) // never connected: stays NeverConnected, still TTL-reaped
    now.set(30_000)
    assertEquals(reg.reap(), List(page.id))
  }
}
