package io.eezo.auth

import java.time.Instant

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
  import SignInFixtures.*

  private val ann = User(Id.gen(), "ann@example.com")

  private def guard: Guard[User] =
    Guard[User](
      find = id => Option.when(id == ann.id)(ann),
      credentials = _ => None,
      clock = clock
    )

  private def signedIn(who: Id[User], since: Instant = now): Request =
    Request(
      method = Method.GET,
      path = "/posts",
      query = Map.empty,
      headers = Map.empty,
      body = Array.emptyByteArray,
      pathParams = Map.empty,
      session = signedInSession(who, since)
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
    assertEquals(declared.currentUser(signedIn(ann.id)), Some(ann.id))
  }

  test("asking for the current user of a request nobody signed answers nobody") {
    val declared = guard.required[Post].owning(_.author).all
    assertEquals(declared.currentUser(signedIn(ann.id).copy(session = Session.empty)), None)
  }

  test("a sign in past its lifetime scopes nothing: the key is nobody, not a stale owner") {
    val declared = guard.required[Post].owning(_.author).all
    assertEquals(declared.currentUser(signedIn(ann.id, since = stale)), None)
    // The shape every session signed before the lifetime shipped has. Reading it as an owner would
    // hand a copied session the rows of whoever it names, which is the whole reason for the stamp.
    assertEquals(
      declared.currentUser(signedIn(ann.id).copy(session = stamplessSession(ann.id))),
      None
    )
  }

  test("a selector that is not a plain field of the model does not compile") {
    val computed =
      compileErrors(
        "OwningSuite.aGuard.required[OwningSuite.Post].owning(post => identity(post.author))"
      )
    assert(computed.contains("has to be a field of the model"), computed)
  }

  test("a member the model computes is not a field, however much it is spelled like one") {
    val aDef =
      compileErrors("OwningSuite.aGuard.required[OwningSuite.Draft].owning(_.writer)")
    assert(aDef.contains("has to be a field of the model"), aDef)

    val aVal =
      compileErrors("OwningSuite.aGuard.required[OwningSuite.Draft].owning(_.alias)")
    assert(aVal.contains("has to be a field of the model"), aVal)
  }
}

object OwningSuite {
  case class User(id: Id[User], email: String)
  case class Post(id: Id[Post], author: Id[User], title: String)

  /** A model whose body has members shaped like the owner field: neither is a column. */
  case class Draft(id: Id[Draft], author: Id[User]) {
    def writer: Id[User] = author
    val alias: Id[User]  = author
  }

  /** Named, so the compile-error check can reach a guard from inside a string. */
  val aGuard: Guard[User] = Guard[User](find = _ => None, credentials = _ => None)
}
