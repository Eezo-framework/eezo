import io.eezo.http.Method
import io.eezo.http.Request

/** What the blog's guard does to the blog's own routes: the editing screens refuse a browser
  * nobody has signed in, and the front page does not.
  *
  * Driven through `Main.routes`, the table the application actually serves, rather than through one
  * assembled here. The two answers below depend on the declaration in `models/Post.scala` and on
  * the mount in `Main.scala`, and a table assembled by the test would pin neither.
  *
  * No Docker, no network and no Postgres, because neither answer reads a row: a refusal stops at
  * the session, the public page never asks who is there, and the store the generated table mints
  * for `Post` holds no connection of its own.
  *
  * Where a sign in that was not preceded by a refusal lands, the guard's `home`, is `GuardSuite`'s
  * to pin and it does. Reaching that path for real goes through `User.byEmail`, which reads a
  * table, so there is no honest way to observe it from here.
  */
class UserSuite extends munit.FunSuite {

  private val table = Main.routes

  /** A browser asking for a page, carrying no session and so naming nobody. */
  private def visitor(path: String): Request =
    Request(
      method = Method.GET,
      path = path,
      query = Map.empty,
      headers = Map.empty,
      body = Array.empty[Byte],
      pathParams = Map.empty
    )

  test("an anonymous visitor asking for the posts is sent to the login page inside the mount") {
    val response = table.dispatch(visitor("/admin/posts"))
    assertEquals(response.status, 303)
    assertEquals(response.header("Location"), Some("/admin/login"))
  }

  test("the front page declares itself public, so the same visitor is served it") {
    assertEquals(table.dispatch(visitor("/")).status, 200)
  }
}
