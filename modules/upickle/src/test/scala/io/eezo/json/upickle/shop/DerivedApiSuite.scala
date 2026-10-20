package io.eezo.json.upickle.shop

import java.nio.charset.StandardCharsets
import java.util.UUID

import io.eezo.core.{Id, OwnerOf, Store}
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

  private val ada   = Id.gen[Clerk]()
  private val grace = Id.gen[Clerk]()

  /** A note store holding one row of grace's, and the five over it, owned with these actions
    * covered, every route guarded and the caller named off an `X-User` header the way a guard's
    * token arm names it.
    */
  private def notes(covers: Set[Action]): (RouteTable, Store[Note], Note) = {
    val ownerOf = OwnerOf[Note, Id[Clerk]]("owner", _.owner)
    val store   = InMemoryStore.scoped(ownerOf)
    val graces  = Note(Id.gen(), grace, "grace's")
    store.insert(graces.id, graces)
    val named = Guarded[Note](
      Action.values.toSet,
      {
        case http: Route.Http =>
          http.copy(handler =
            request => http.handler(request.copy(currentUser = request.header("X-User")))
          )
        case other => other
      },
      Seq.empty
    )
    val owned = Owned[Note, Clerk](
      ownerOf,
      request => request.currentUser.map(text => Id[Clerk](UUID.fromString(text))),
      covers,
      named
    )
    (RouteTable(Api[Note].routes(store, owned)), store, graces)
  }

  private def by(who: Id[Clerk], method: Method, path: String, body: String): Request =
    program(method, path, body).copy(headers =
      Map("Content-Type" -> Seq("application/json"), "X-User" -> Seq(who.show))
    )

  test("an owned model's body names no owner, and the row records the caller") {
    val (routes, store, _) = notes(Set(Action.Index))
    val created = routes.dispatch(by(ada, Method.POST, "/api/notes", """{"text":"hello"}"""))
    assertEquals(created.status, 201)
    val stored = store.all().find(_.text == "hello").get
    assertEquals(stored.owner, ada)
    assertEquals(
      text(created),
      s"""{"id":"${stored.id.show}","owner":"${ada.show}","text":"hello"}"""
    )
  }

  test("an owned model's body that names another owner and another key is overruled on both") {
    val (routes, store, _) = notes(Set(Action.Index))
    val claimed            = Id.gen[Note]()
    val body               = s"""{"id":"${claimed.show}","owner":"${grace.show}","text":"kept"}"""
    val created            = routes.dispatch(by(ada, Method.POST, "/api/notes", body))
    assertEquals(created.status, 201)
    val stored = store.all().find(_.text == "kept").get
    assertEquals(stored.owner, ada)
    assertNotEquals(stored.id, claimed)
    assertEquals(created.header("Location"), Some(s"/api/notes/${stored.id.show}"))
  }

  test("an update ownership does not cover keeps the row's owner, named in the body or not") {
    val (routes, store, graces) = notes(Set(Action.Index))
    val path                    = s"/api/notes/${graces.id.show}"
    assertEquals(routes.dispatch(by(ada, Method.PUT, path, """{"text":"one"}""")).status, 200)
    assertEquals(store.find(graces.id), Some(Note(graces.id, grace, "one")))
    val named = s"""{"owner":"${ada.show}","text":"two"}"""
    assertEquals(routes.dispatch(by(ada, Method.PUT, path, named)).status, 200)
    assertEquals(store.find(graces.id), Some(Note(graces.id, grace, "two")))
  }

  test("an update ownership covers records the requester, named in the body or not") {
    val (routes, store, graces) = notes(Set(Action.Index, Action.Update))
    val path                    = s"/api/notes/${graces.id.show}"
    assertEquals(routes.dispatch(by(grace, Method.PUT, path, """{"text":"one"}""")).status, 200)
    assertEquals(store.find(graces.id), Some(Note(graces.id, grace, "one")))
    val named = s"""{"owner":"${ada.show}","text":"two"}"""
    assertEquals(routes.dispatch(by(grace, Method.PUT, path, named)).status, 200)
    assertEquals(store.find(graces.id), Some(Note(graces.id, grace, "two")))
  }

  test("a field nobody decided is still required of an owned model's body") {
    val (routes, store, graces) = notes(Set(Action.Index))
    intercept[UnprocessableContent](routes.dispatch(by(ada, Method.POST, "/api/notes", "{}")))
    assertEquals(store.all(), Seq(graces))
  }
}
