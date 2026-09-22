package io.eezo.http

import io.eezo.core.Store
import io.eezo.core.html.{Html, Url}

/** What `Guarded` is as a value, and what `Resource` does with one.
  *
  * `http` has no guard of its own: `Guarded` is the declaration and `modules/auth` is one thing
  * that fulfils it. Everything here therefore builds a `Guarded` by hand, which is exactly what any
  * other way of signing in would have to do.
  */
class GuardedSuite extends munit.FunSuite with ResourceFixtures {

  private def store: Store[Widget] = InMemoryStore[Widget]()

  /** A wrapper that answers instead of the handler, so that "the wrapper ran" is visible in the
    * response rather than in a mutable flag a passing test could leave unset.
    */
  private val refuse: Route => Route = {
    case Route.Http(method, pattern, _, provenance) =>
      Route.Http(method, pattern, _ => Response.Redirect(Url.Mounted("/login")), provenance)
    case ws => ws
  }

  private def guardedOn(actions: Action*): Guarded[Widget] =
    Guarded(actions.toSet, refuse, Seq.empty)

  private def routesOf(guarded: Guarded[Widget]): Map[String, Route] =
    Resource[Widget].routes(store, guarded).map(route => route.describe -> route).toMap

  test("a public declaration wraps nothing and carries nothing") {
    assertEquals(Guarded.public[Widget].actions, Set.empty[Action])
    assertEquals(Guarded.public[Widget].carries, Seq.empty[Route])

    val route = Route.Http(Method.GET, PathPattern.parse("/x"), _ => Response.Ok(Html.text("hi")))
    assertEquals(Guarded.public[Widget].through(route), route)
  }

  test("the wrapper reaches only the routes whose Action the declaration names") {
    val routes = routesOf(guardedOn(Action.Create, Action.Destroy))

    def statusOf(key: String, method: Method, path: String): Int =
      routes(key).asInstanceOf[Route.Http].handler(request(method, path)).status

    assertEquals(statusOf("POST /widgets", Method.POST, "/widgets"), 303)
    assertEquals(statusOf("DELETE /widgets/:id", Method.DELETE, "/widgets/7"), 303)
    // Index is not named, so its handler runs and renders the empty listing.
    assertEquals(statusOf("GET /widgets", Method.GET, "/widgets"), 200)
  }

  test("a declaration naming nothing leaves every route exactly as it was") {
    val bare    = Resource[Widget].routes(store).map(_.describe)
    val guarded = Resource[Widget].routes(store, Guarded.public).map(_.describe)
    assertEquals(guarded, bare)
  }

  test("the routes a guard carries come out beside the model's own") {
    val login =
      Route.Http(Method.GET, PathPattern.parse("/login"), _ => Response.Ok(Html.text("form")))
    val guarded = Guarded[Widget](Set(Action.Index), identity, Seq(login))
    assert(guarded.carries.contains(login))
  }

  test("mounting puts what a guard carries ahead of the handwritten route it wraps") {
    val login =
      Route.Http(Method.GET, PathPattern.parse("/login"), _ => Response.Ok(Html.text("form")))
    val route   = Route.Http(Method.GET, PathPattern.parse("/x"), _ => Response.Ok(Html.text("hi")))
    val guarded = Guarded[Widget](Set(Action.Index), refuse, Seq(login))

    val mounted = guarded.mounting(route)

    // The login page comes first, so a table built from several declarations still finds it
    // ahead of every route that might redirect to it.
    assertEquals(mounted.head, login)
    // The second route is `route` wrapped by `through`, not `route` itself: the redirect proves
    // the wrapper actually ran rather than the original handwritten handler.
    val wrapped = mounted(1).asInstanceOf[Route.Http]
    assertEquals(wrapped.handler(request(Method.GET, "/x")).status, 303)
  }

  test("routesOf mounts what the declaration carries alongside the model's own routes") {
    val login =
      Route.Http(Method.GET, PathPattern.parse("/login"), _ => Response.Ok(Html.text("form")))
    val guarded = Guarded[Widget](Set(Action.Index), identity, Seq(login))

    assert(Resource.routesOf[Widget](store, guarded).contains(login))
  }

  test("routesOf takes a declaration too, and a model with no Resource still yields nothing") {
    case class Unmounted(name: String)
    assertEquals(
      Resource.routesOf[Unmounted](InMemoryStore[Unmounted](), Guarded.public).size,
      0
    )
    assertEquals(
      Resource.routesOf[Widget](store, guardedOn(Action.Index)).size,
      Resource[Widget].routes(store).size
    )
  }

  test("an existing call site that names no declaration still compiles and is unguarded") {
    assertEquals(Resource.routesOf[Widget](store).size, 7)
  }

  test("a declaration written before there was anything to name still names nobody") {
    // Three fields is how every declaration in the wild is written, and the fourth has to default
    // to naming nobody or `Guarded.public` would be the only declaration that compiles.
    val req = request(Method.GET, "/widgets")
    assertEquals(guardedOn(Action.Create).identify(req), req)
  }

  test("a public declaration names nobody, the way it wraps nothing and carries nothing") {
    val req = request(Method.GET, "/widgets")
    assertEquals(Guarded.public[Widget].identify(req), req)
  }
}
