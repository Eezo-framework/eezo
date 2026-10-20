package io.eezo.json.upickle.shop

import java.nio.charset.StandardCharsets
import java.util.UUID

import io.eezo.core.{Id, OwnerOf}
import io.eezo.http.*
import io.eezo.json.upickle.given

/** A model the way an application declares one: in a package of its own, with the adapter's given
  * imported and nothing else, so the Serde `derives Api` asks for comes from that import alone.
  */
case class Product(id: Id[Product], name: String, cents: Long) derives Form, Resource, Api

/** The user a [[Note]] records, which appears only as the type of a key. */
case class Clerk(id: Id[Clerk])

/** An owned model read by the real adapter, whose case class reader needs every field. */
case class Note(id: Id[Note], owner: Id[Clerk], text: String) derives Api

/** The five, with the JSON written and read by eezo-upickle: what a program sends and what it reads
  * back, beside the seven of the same model on the same store.
  */
class DerivedApiSuite extends munit.FunSuite {

  private def program(method: Method, path: String, body: String = ""): Request =
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers = Map("Content-Type" -> Seq("application/json")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )

  private def text(response: Response): String = response.body match {
    case Body.Json(text) => text
    case other           => fail(s"expected a JSON body, got $other")
  }

  test("an imported adapter is all derives Api needs, and the five are what it mounts") {
    assertEquals(
      Api[Product].routes(InMemoryStore(), Guarded.public).map(_.describe),
      Seq(
        "GET /api/products",
        "GET /api/products/:id",
        "POST /api/products",
        "PUT /api/products/:id",
        "DELETE /api/products/:id"
      )
    )
  }

  test("a program creates, reads and lists through upickle, under a key eezo minted") {
    val store   = InMemoryStore[Product]()
    val routes  = RouteTable(Resource[Product].routes(store) ++ Api[Product].routes(store))
    val claimed = Id.gen[Product]()

    val created = routes.dispatch(
      program(Method.POST, "/api/products", s"""{"id":"${claimed.show}","name":"Bolt","cents":3}""")
    )
    assertEquals(created.status, 201)
    val stored = store.all().head
    assertNotEquals(stored.id, claimed)
    assertEquals(
      text(created),
      s"""{"id":"${stored.id.show}","name":"Bolt","cents":3}"""
    )
    assertEquals(created.header("Location"), Some(s"/api/products/${stored.id.show}"))

    assertEquals(
      text(routes.dispatch(program(Method.GET, "/api/products"))),
      s"""[{"id":"${stored.id.show}","name":"Bolt","cents":3}]"""
    )
    // The row the program made is the row the page shows.
    assertEquals(routes.dispatch(program(Method.GET, s"/products/${stored.id.show}")).status, 200)
  }

  test("an owned model's body carries the owner field, and the caller's key replaces it") {
    val ada     = Id.gen[Clerk]()
    val ownerOf = OwnerOf[Note, Id[Clerk]]("owner", _.owner)
    val store   = InMemoryStore.scoped(ownerOf)
    // Every route guarded, the caller named the way a guard's token arm names it.
    val named = Guarded[Note](
      Action.values.toSet,
      {
        case http: Route.Http =>
          http.copy(handler = request => http.handler(request.copy(currentUser = Some(ada.show))))
        case other => other
      },
      Seq.empty
    )
    val owned = Owned[Note, Clerk](
      ownerOf,
      request => request.currentUser.map(text => Id[Clerk](UUID.fromString(text))),
      Set(Action.Index),
      named
    )
    val routes = RouteTable(Api[Note].routes(store, owned))

    // The case class reader needs every field, so a body without the owner never reaches the
    // overwrite: it is the 422 any other missing field is, and nothing is stored.
    intercept[UnprocessableContent](
      routes.dispatch(program(Method.POST, "/api/notes", """{"text":"bare"}"""))
    )
    assertEquals(store.all(), Seq.empty)

    // Any key the body names is a placeholder: the row records the caller.
    val placeholder = Id.gen[Clerk]()
    val created     = routes.dispatch(
      program(Method.POST, "/api/notes", s"""{"owner":"${placeholder.show}","text":"kept"}""")
    )
    assertEquals(created.status, 201)
    assertEquals(store.all().map(_.owner), Seq(ada))
  }
}
