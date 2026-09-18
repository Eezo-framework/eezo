package io.eezo.auth

import io.eezo.core.Id
import io.eezo.http.*

/** Turning a guard into an ownership declaration: the chain a model's companion writes, and the one
  * compile-time step in it.
  *
  * What the suite is really pinning is that the field name in the declaration and the column name
  * in the database are the same string by construction rather than by a user typing it twice. The
  * selector is resolved where the model is in scope, so a field that is not there is a compile
  * error and never a query against a column nobody has.
  */
class OwningSuite extends munit.FunSuite {

  import OwningSuite.*

  private val ann = User(Id.gen(), "ann@example.com")

  private def guard: Guard[User] =
    Guard[User](find = id => Option.when(id == ann.id)(ann), authenticate = (_, _) => None)

  private def signedIn(who: Id[User]): Request =
    Request(
      method = Method.GET,
      path = "/posts",
      query = Map.empty,
      headers = Map.empty,
      body = Array.emptyByteArray,
      pathParams = Map.empty,
      session = Session.empty.withReserved(Guard.UserEntry, who.show)
    )

  test("the blog's declaration types as an Owned and names the model's own field") {
    val declared: Owned[Post, User] =
      guard.required[Post].owning(_.author).except(Action.Index, Action.Show)

    assertEquals(declared.ownerOf.name, "author")
    assertEquals(declared.covers, Action.values.toSet -- Set(Action.Index, Action.Show))
    assertEquals(declared.actions, Action.values.toSet)
  }

  test("a chain ending in all covers all seven, and one ending in only covers exactly those") {
    assertEquals(guard.required[Post].owning(_.author).all.covers, Action.values.toSet)
    assertEquals(
      guard.required[Post].owning(_.author).only(Action.Destroy).covers,
      Set(Action.Destroy)
    )
  }

  test("owning follows required, only and except alike, and keeps what each of them guarded") {
    assertEquals(guard.required[Post].owning(_.author).all.actions, Action.values.toSet)
    assertEquals(guard.only[Post](Action.Update).owning(_.author).all.actions, Set(Action.Update))
    assertEquals(
      guard.except[Post](Action.Index).owning(_.author).all.actions,
      Action.values.toSet - Action.Index
    )
  }

  test("an ownership declaration carries the guard's own login and logout routes") {
    val declared = guard.required[Post].owning(_.author).all
    assertEquals(declared.carries.size, 3)
  }

  test("the getter reads the field the name names") {
    val post = Post(Id.gen(), ann.id, "Hello")
    assertEquals(guard.required[Post].owning(_.author).all.ownerOf.get(post), ann.id)
  }

  test("the current user of a request is who the session says is signed in") {
    val declared = guard.required[Post].owning(_.author).all
    assertEquals(declared.currentUser(signedIn(ann.id)), ann.id)
  }

  test("asking for the current user of a request nobody signed is an error rather than a guess") {
    val declared = guard.required[Post].owning(_.author).all
    intercept[Unauthorized](declared.currentUser(signedIn(ann.id).copy(session = Session.empty)))
  }

  test("a selector that is not a plain field of the model does not compile") {
    val computed =
      compileErrors(
        "OwningSuite.aGuard.required[OwningSuite.Post].owning(post => identity(post.author))"
      )
    assert(computed.contains("has to be a field of the model"), computed)
  }
}

object OwningSuite {
  case class User(id: Id[User], email: String)
  case class Post(id: Id[Post], author: Id[User], title: String)

  /** Named, so the compile-error check can reach a guard from inside a string. */
  val aGuard: Guard[User] = Guard[User](find = _ => None, authenticate = (_, _) => None)
}
