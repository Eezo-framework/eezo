package io.eezo

import io.eezo.auth.{Guard, SignInFixtures}
import io.eezo.core.Id
import io.eezo.core.html.Html
import io.eezo.http.*
import io.eezo.live.{Live, LiveServerFixtures}

/** A bound live page driven the way a browser drives one: a real guard, the cookie eezo itself
  * signed, and a real upgrade over a real socket.
  *
  * It lives in the umbrella because it is the only place `http`, `auth` and `live` are all on one
  * classpath, and binding is exactly the seam between the three: `auth` decides who is signed in,
  * `http` carries that verdict on the request, and `live` compares the two stamps. Each module's
  * own suite pins its half against something hand built; nothing but this can pin that the three
  * halves meet.
  *
  * No password is ever hashed here. A sign in is planted by handing the browser a session eezo
  * signed, which is what a successful login would have written anyway, so the expiry case costs a
  * cookie rather than a fortnight and the suite costs no bcrypt.
  */
class BoundLivePageSuite extends munit.FunSuite with LiveServerFixtures {

  private case class User(id: Id[User])

  private val alice   = User(Id.gen())
  private val mallory = User(Id.gen())

  /** `credentials` answers nobody: this suite never signs anyone in through the login form. The
    * clock is the auth suites' fixed one, so their stale stamp is stale here too.
    */
  private val guard: Guard[User] =
    Guard[User](
      Map(alice.id -> alice, mallory.id -> mallory).get,
      _ => None,
      clock = SignInFixtures.clock
    )

  /** A page that hands the browser a session, so the tests below own a real signed cookie without
    * going through a password.
    */
  private def planting(at: String, session: Session): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse(at),
      _ => Response.Ok(Html.text("planted")).withSession(session)
    )

  /** What the generator emits: the guarded page through its declaration, and the declaration's own
    * `identify` on the table, which is what reaches an upgrade.
    */
  private def bound(body: Rig => Unit): Unit = {
    val dashboard: Guarded[Any] = guard.required[Any]
    val user                    =
      dashboard.mounting(pageRoute(Live.mount(_, new Counter), "/dashboard")) ++ Seq(
        planting("/plant/alice", SignInFixtures.signedInSession(alice.id)),
        planting("/plant/mallory", SignInFixtures.signedInSession(mallory.id)),
        planting("/plant/lapsed", SignInFixtures.signedInSession(alice.id, SignInFixtures.stale))
      )
    serving(RouteTable(user, dashboard.identify) ++ RouteTable(Live.routes))(body)
  }

  /** The session cookie eezo signed for the browser that visited `path`, as a header to send back.
    */
  private def cookieFrom(rig: Rig, path: String): Seq[(String, String)] = {
    val cookie = rig
      .get(path)
      .headers()
      .firstValue("Set-Cookie")
      .orElseThrow(() => new NoSuchElementException(s"$path handed back no session cookie"))
      .takeWhile(_ != ';')
    Seq("Cookie" -> cookie)
  }

  test("a guarded page joins with the very cookie that rendered it") {
    bound { rig =>
      val cookie = cookieFrom(rig, "/plant/alice")
      val wire   = rig.connect(rig.mountedPageId("/dashboard", cookie), cookie)
      wire.join()
      assert(wire.frame().contains("\"setChildren\""))
      wire.close()
    }
  }

  test("a guarded page refuses a socket carrying no cookie at all, with 4403") {
    bound { rig =>
      val id = rig.mountedPageId("/dashboard", cookieFrom(rig, "/plant/alice"))
      assertEquals(rig.connect(id).closed(), (4403, "not signed in as the page's user"))
    }
  }

  test("a guarded page refuses another signed in user, with 4403") {
    bound { rig =>
      val id = rig.mountedPageId("/dashboard", cookieFrom(rig, "/plant/alice"))
      assertEquals(rig.connect(id, cookieFrom(rig, "/plant/mallory")).closed()._1, 4403)
    }
  }

  test("a guarded page refuses the same user a second past the sign in's lifetime, with 4403") {
    bound { rig =>
      val id = rig.mountedPageId("/dashboard", cookieFrom(rig, "/plant/alice"))
      // The same person, the same browser: a sign in the guard no longer honours names nobody, and
      // nobody is not the page's user.
      assertEquals(rig.connect(id, cookieFrom(rig, "/plant/lapsed")).closed()._1, 4403)
    }
  }
}
