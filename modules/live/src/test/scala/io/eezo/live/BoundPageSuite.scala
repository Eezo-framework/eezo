package io.eezo.live

import io.eezo.http.*

/** A bound page against a booted server, with a `Guarded` built here rather than by a guard.
  *
  * The declaration is hand built on purpose: `live` compares two strings some other module put on
  * the request, so the suite that pins the comparison must not be able to reach a guard. What
  * stands in for a session is a header the fake declaration reads, which is enough because the
  * whole rule under test is that the two stamps are the same text. The umbrella's own suite is the
  * one that drives a real guard, real cookies and a real expiry.
  */
class BoundPageSuite extends munit.FunSuite with LiveServerFixtures {

  private val WhoHeader = "X-Eezo-Test-User"

  /** The fake session: whoever the header names is who this request is for. */
  private val naming: Request => Request =
    request => request.copy(currentUser = request.header(WhoHeader))

  /** A declaration that names a request and lets every one of them through. Refusing is the other
    * half of a real guard and has nothing to do with binding, so it is deliberately absent: what
    * this suite needs is a route whose renders are stamped and whose upgrades are stamped too.
    */
  private val guarded: Guarded[Any] = Guarded[Any](
    actions = Set.empty,
    through = {
      case http: Route.Http => http.copy(handler = naming.andThen(http.handler))
      case other            => other
    },
    carries = Seq.empty,
    identify = naming
  )

  private def page(at: String): Route = pageRoute(Live.mount(_, new Counter), at)

  /** What a generated table looks like: the guarded page through its declaration, the public one
    * beside it, and the declaration's own naming on the table, which is what reaches an upgrade.
    */
  private def bound(body: Rig => Unit): Unit = {
    val user = guarded.mounting(page("/dashboard")) :+ page("/public")
    serving(RouteTable(user, guarded.identify) ++ RouteTable(Live.routes))(body)
  }

  private def as(who: String): Seq[(String, String)] = Seq(WhoHeader -> who)

  test("a request free mount does not compile: every page must say who rendered it") {
    assert(compileErrors("io.eezo.live.Live.mount(new Counter)").nonEmpty)
  }

  test("a bound page answers its own user's socket") {
    bound { rig =>
      val wire = rig.connect(rig.mountedPageId("/dashboard", as("alice")), as("alice"))
      wire.join()
      assert(wire.frame().contains("\"setChildren\""))
      wire.close()
    }
  }

  test("a bound page refuses a socket signed in as nobody, with 4403 and the reason why") {
    bound { rig =>
      val refused = rig.connect(rig.mountedPageId("/dashboard", as("alice"))).closed()
      assertEquals(refused, (4403, "not signed in as the page's user"))
    }
  }

  test("a refused stranger costs the page nothing: its own user still joins afterwards") {
    bound { rig =>
      val id = rig.mountedPageId("/dashboard", as("alice"))
      assertEquals(rig.connect(id, as("mallory")).closed()._1, 4403)

      val wire = rig.connect(id, as("alice"))
      wire.join()
      assert(wire.frame().contains("\"setChildren\""))
      wire.close()
    }
  }

  test("a page rendered on a public route is bound to nobody, whoever visited") {
    bound { rig =>
      // /public bypasses the declaration's naming, so the header never reaches the handler and
      // the page is not bound to that name either: a socket naming someone else joins it.
      val wire = rig.connect(rig.mountedPageId("/public", as("alice")), as("mallory"))
      wire.join()
      assert(wire.frame().contains("\"setChildren\""))
      wire.close()
    }
  }
}
