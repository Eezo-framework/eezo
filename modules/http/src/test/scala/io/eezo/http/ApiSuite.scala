package io.eezo.http

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.UUID

import io.eezo.core.{Id, OwnerOf, Store}
import io.eezo.core.internal.Json

/** What `derives Api` mounts, and what each of the five answers a program with.
  *
  * The routes are driven through a `RouteTable`, as `ResourceSuite` drives the seven, because the
  * path parameters a handler reads and the session an API route empties are dispatch's own work.
  * The JSON comes from a hand written [[ApiSuite.gadgetSerde]] over `core`'s reader, since `http`
  * has no JSON library and a derived API has to work with whatever Serde the application holds.
  */
class ApiSuite extends munit.FunSuite with ServerFixtures {

  import ApiSuite.*

  private def kindOf(route: Route): RouteKind = route match {
    case http: Route.Http => http.kind
    case other            => fail(s"not an HTTP route: $other")
  }

  // ---------------------------------------------------------------- the route set

  test("the five are mounted under /api, and nothing else") {
    assertEquals(
      summon[Api[Gadget]].routes(InMemoryStore()).map(_.describe),
      Seq(
        "GET /api/gadgets",
        "GET /api/gadgets/:id",
        "POST /api/gadgets",
        "PUT /api/gadgets/:id",
        "DELETE /api/gadgets/:id"
      )
    )
  }

  test("every route is a derived API route") {
    val routes = summon[Api[Gadget]].routes(InMemoryStore())
    assertEquals(routes.map(kindOf).distinct, Seq(RouteKind.Api))
    assertEquals(routes.map(_.provenance).distinct, Seq(Provenance.Derived))
  }

  test("the plural is the one Resource mounts the same model at") {
    def collectionOf[A](using a: Api[A], r: Resource[A]): (String, String) =
      (
        a.routes(InMemoryStore()).head.describe.stripPrefix("GET "),
        r.routes(InMemoryStore()).head.describe.stripPrefix("GET ")
      )

    assertEquals(collectionOf[BlogPost], ("/api/blog_posts", "/blog_posts"))
    assertEquals(collectionOf[Category], ("/api/categories", "/categories"))
  }

  test("routesOf mounts nothing for a model with no Api, and never fails to compile") {
    assertEquals(Api.routesOf[Widget](InMemoryStore()), Seq.empty[Route])
    assertEquals(Api.routesOf[Gadget](InMemoryStore()).size, 5)
  }

  // ---------------------------------------------------------------- index and show

  test("index answers 200 with a JSON array of every row, written by the Serde") {
    val (_, routes, saved) = gadgets("Bolt" -> 3, "Nut" -> 1)
    val response           = routes.dispatch(program(Method.GET, "/api/gadgets"))
    assertEquals(response.status, 200)
    assertEquals(
      json(response),
      Json.Arr(saved.map(row => tree(gadgetSerde.serialize(row))).toList)
    )
  }

  test("an empty store answers an empty array") {
    val (_, routes, _) = gadgets()
    assertEquals(text(routes.dispatch(program(Method.GET, "/api/gadgets"))), "[]")
  }

  test("show answers 200 with that row, written by the Serde") {
    val (_, routes, saved) = gadgets("Bolt" -> 3, "Nut" -> 1)
    val response = routes.dispatch(program(Method.GET, s"/api/gadgets/${saved(1).id.show}"))
    assertEquals(response.status, 200)
    assertEquals(text(response), gadgetSerde.serialize(saved(1)))
  }

  test("show of a key naming no row is a NotFound, and a key that is no key a BadRequest") {
    val (_, routes, _) = gadgets("Bolt" -> 3)
    intercept[NotFound](routes.dispatch(program(Method.GET, s"/api/gadgets/${Id.gen().show}")))
    intercept[BadRequest](routes.dispatch(program(Method.GET, "/api/gadgets/not-a-key")))
  }

  // ---------------------------------------------------------------- create

  test("create answers 201 with the stored row, under a key it minted, and points at it") {
    val (store, routes, _) = gadgets()
    val claimed            = Id.gen[Gadget]()
    val response           = routes.dispatch(
      program(Method.POST, "/api/gadgets", s"""{"id":"${claimed.show}","name":"Bolt","cents":3}""")
    )
    assertEquals(response.status, 201)
    val stored = store.all()
    assertEquals(stored.size, 1)
    assertNotEquals(stored.head.id, claimed)
    assertEquals(stored.head, Gadget(stored.head.id, "Bolt", 3))
    assertEquals(text(response), gadgetSerde.serialize(stored.head))
    assertEquals(response.header("Location"), Some(s"/api/gadgets/${stored.head.id.show}"))
  }

  test("a body that names no id creates a row all the same") {
    val (store, routes, _) = gadgets()
    val response           =
      routes.dispatch(program(Method.POST, "/api/gadgets", """{"name":"Nut","cents":1}"""))
    assertEquals(response.status, 201)
    assertEquals(store.all().map(_.name), Seq("Nut"))
  }

  test("a create body that is not JSON is a BadRequest, one that does not fit is Unprocessable") {
    val (store, routes, _) = gadgets()
    intercept[BadRequest](routes.dispatch(program(Method.POST, "/api/gadgets", "name=Bolt")))
    intercept[UnprocessableContent](
      routes.dispatch(program(Method.POST, "/api/gadgets", """{"name":"Bolt"}"""))
    )
    assertEquals(store.all(), Seq.empty[Gadget])
  }

  test("a Serde that keeps the body's id still cannot choose the key of the row it creates") {
    val store    = InMemoryStore[Gadget]()
    val routes   = RouteTable(careless.routes(store))
    val claimed  = Id.gen[Gadget]()
    val response = routes.dispatch(
      program(Method.POST, "/api/gadgets", s"""{"id":"${claimed.show}","name":"Bolt","cents":3}""")
    )
    assertEquals(response.status, 201)
    assertNotEquals(store.all().map(_.id), Seq(claimed))
  }

  // ---------------------------------------------------------------- update

  test("update answers 200 with the stored row, keyed by the path whatever the body says") {
    val (store, routes, saved) = gadgets("Bolt" -> 3)
    val key                    = saved.head.id
    val response               = routes.dispatch(
      program(
        Method.PUT,
        s"/api/gadgets/${key.show}",
        s"""{"id":"${Id.gen[Gadget]().show}","name":"Screw","cents":5}"""
      )
    )
    assertEquals(response.status, 200)
    assertEquals(store.all(), Seq(Gadget(key, "Screw", 5)))
    assertEquals(text(response), gadgetSerde.serialize(Gadget(key, "Screw", 5)))
  }

  test("update of a key naming no row is a NotFound, and writes nothing") {
    val (store, routes, saved) = gadgets("Bolt" -> 3)
    intercept[NotFound](
      routes.dispatch(
        program(Method.PUT, s"/api/gadgets/${Id.gen().show}", """{"name":"Screw","cents":5}""")
      )
    )
    assertEquals(store.all(), saved)
  }

  test("an update body that is not JSON, or does not fit, leaves the row as it was") {
    val (store, routes, saved) = gadgets("Bolt" -> 3)
    val path                   = s"/api/gadgets/${saved.head.id.show}"
    intercept[BadRequest](routes.dispatch(program(Method.PUT, path, "{")))
    intercept[UnprocessableContent](routes.dispatch(program(Method.PUT, path, """{"cents":5}""")))
    assertEquals(store.all(), saved)
  }

  // ---------------------------------------------------------------- destroy

  test("destroy answers 204 with no body, and the row is gone") {
    val (store, routes, saved) = gadgets("Bolt" -> 3, "Nut" -> 1)
    val response = routes.dispatch(program(Method.DELETE, s"/api/gadgets/${saved.head.id.show}"))
    assertEquals(response.status, 204)
    assertEquals(response.body, Body.Empty)
    assertEquals(store.all(), saved.drop(1))
  }

  test("destroy of a key naming no row is a NotFound") {
    val (_, routes, _) = gadgets("Bolt" -> 3)
    intercept[NotFound](routes.dispatch(program(Method.DELETE, s"/api/gadgets/${Id.gen().show}")))
  }

  // ---------------------------------------------------------------- over the wire

  /** A call a program makes over the loopback, with no cookie, a JSON body when there is one. */
  private def calling(port: Int, method: String, path: String, body: String = "") =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://$Loopback:$port$path"))
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def assertProblem(response: HttpResponse[String], status: Int): Unit = {
    assertEquals(response.statusCode, status)
    assertEquals(
      response.headers.firstValue("Content-Type").orElse(""),
      "application/problem+json"
    )
    assert(clue(response.body).contains(s"\"status\": $status"))
  }

  test(
    "a missing row, a key that is no key and a body that is not JSON or does not fit are problems"
  ) {
    val (store, routes, saved) = gadgets("Bolt" -> 3)
    serving(routes) { (_, port) =>
      assertProblem(calling(port, "GET", s"/api/gadgets/${Id.gen().show}"), 404)
      assertProblem(calling(port, "GET", "/api/gadgets/not-a-key"), 400)
      assertProblem(calling(port, "POST", "/api/gadgets", "name=Bolt"), 400)
      assertProblem(calling(port, "POST", "/api/gadgets", """{"name":"Bolt"}"""), 422)
      assertProblem(
        calling(port, "PUT", s"/api/gadgets/${Id.gen().show}", """{"name":"Nut","cents":1}"""),
        404
      )
      assertProblem(calling(port, "DELETE", s"/api/gadgets/${Id.gen().show}"), 404)
    }
    assertEquals(store.all(), saved)
  }

  test("a 201 and a 200 go out as JSON, and a 204 with no body and no content type") {
    val (_, routes, saved) = gadgets("Bolt" -> 3)
    serving(routes) { (_, port) =>
      val created = calling(port, "POST", "/api/gadgets", """{"name":"Nut","cents":1}""")
      assertEquals(created.statusCode, 201)
      assertEquals(
        created.headers.firstValue("Content-Type").orElse(""),
        "application/json; charset=utf-8"
      )
      assert(created.headers.firstValue("Location").orElse("").startsWith("/api/gadgets/"))
      assertEquals(calling(port, "GET", "/api/gadgets").statusCode, 200)

      val deleted = calling(port, "DELETE", s"/api/gadgets/${saved.head.id.show}")
      assertEquals(deleted.statusCode, 204)
      assertEquals(deleted.body, "")
      assert(!deleted.headers.firstValue("Content-Type").isPresent, deleted.headers)
      assert(!deleted.headers.firstValue("Set-Cookie").isPresent, deleted.headers)
    }
  }

  // ---------------------------------------------------------------- a mount

  test("a mount moves all five, and the Location a create sets moves with them") {
    val store  = InMemoryStore[Gadget]()
    val routes = Route.under("/admin")(summon[Api[Gadget]].routes(store))
    assertEquals(
      routes.map(_.describe),
      Seq(
        "GET /admin/api/gadgets",
        "GET /admin/api/gadgets/:id",
        "POST /admin/api/gadgets",
        "PUT /admin/api/gadgets/:id",
        "DELETE /admin/api/gadgets/:id"
      )
    )
    val response = RouteTable(routes).dispatch(
      program(Method.POST, "/admin/api/gadgets", """{"name":"Bolt","cents":3}""")
    )
    assertEquals(
      response.header("Location"),
      Some(s"/admin/api/gadgets/${store.all().head.id.show}")
    )
  }

  // ---------------------------------------------------------------- Actions

  test("a read only API is the API minus the three writes") {
    assertEquals(
      summon[Api[Ledger]].routes(InMemoryStore()).map(_.describe),
      Seq("GET /api/ledgers", "GET /api/ledgers/:id")
    )
  }

  test("subtracting one action removes exactly its route, and New or Edit remove nothing") {
    def without(actions: Action*): Seq[String] = {
      given Actions[Gadget] = Actions.except(actions*)
      Api.derived[Gadget].routes(InMemoryStore()).map(_.describe)
    }

    val all = without()
    assertEquals(without(Action.Index), all.filterNot(_ == "GET /api/gadgets"))
    assertEquals(without(Action.Show), all.filterNot(_ == "GET /api/gadgets/:id"))
    assertEquals(without(Action.Create), all.filterNot(_ == "POST /api/gadgets"))
    assertEquals(without(Action.Update), all.filterNot(_ == "PUT /api/gadgets/:id"))
    assertEquals(without(Action.Destroy), all.filterNot(_ == "DELETE /api/gadgets/:id"))
    assertEquals(without(Action.New, Action.Edit), all)
  }

  // ---------------------------------------------------------------- the declaration

  test("a declaration wraps the routes whose roles it names, and no others") {
    val (_, _, saved) = gadgets("Bolt" -> 3)
    val store         = InMemoryStore[Gadget]()
    saved.foreach(row => store.insert(row.id, row))
    val routes = RouteTable(summon[Api[Gadget]].routes(store, writesNeedAKey))
    val path   = s"/api/gadgets/${saved.head.id.show}"

    assertEquals(routes.dispatch(program(Method.GET, "/api/gadgets")).status, 200)
    assertEquals(routes.dispatch(program(Method.GET, path)).status, 200)
    intercept[Unauthorized](
      routes.dispatch(program(Method.POST, "/api/gadgets", """{"name":"Nut","cents":1}"""))
    )
    intercept[Unauthorized](
      routes.dispatch(program(Method.PUT, path, """{"name":"Nut","cents":1}"""))
    )
    intercept[Unauthorized](routes.dispatch(program(Method.DELETE, path)))
    assertEquals(store.all(), saved)

    val keyed = program(Method.DELETE, path).copy(headers = Map("X-Key" -> Seq("open")))
    assertEquals(routes.dispatch(keyed).status, 204)
  }

  test("the routes carry nothing the declaration carries, so an API brings no login page") {
    val routes = Api.routesOf[Gadget](InMemoryStore(), writesNeedAKey)
    assertEquals(routes.size, 5)
    assert(!routes.exists(_.describe == "GET /login"), routes.map(_.describe))
  }

  // ---------------------------------------------------------------- ownership

  test("an owned index lists only the caller's rows") {
    val (routes, _) = memos(Set(Action.Index))
    val response    = routes.dispatch(caller(ada, Method.GET, "/api/memos"))
    assertEquals(
      json(response) match {
        case Json.Arr(items) => items.map(memoTextOf)
        case other           => fail(s"not an array: $other")
      },
      List("ada's")
    )
  }

  test("a create records the caller as owner, whatever owner the body names") {
    val (routes, store) = memos(Set(Action.Index))
    val response        = routes.dispatch(
      caller(ada, Method.POST, "/api/memos", s"""{"owner":"${grace.show}","text":"forged"}""")
    )
    assertEquals(response.status, 201)
    assertEquals(store.all().filter(_.text == "forged").map(_.owner), Seq(ada))
  }

  test("a create body that names no owner records the caller") {
    val (routes, store) = memos(Set(Action.Index))
    val response = routes.dispatch(caller(ada, Method.POST, "/api/memos", """{"text":"bare"}"""))
    assertEquals(response.status, 201)
    assertEquals(store.all().filter(_.text == "bare").map(_.owner), Seq(ada))
  }

  test("the Serde is handed the key and the owner eezo decided, and an unowned model the key") {
    var handed: Seq[Map[String, Id[Any]]]               = Seq.empty
    def recording[A](serde: api.Serde[A]): api.Serde[A] = new api.Serde[A] {
      def serialize(value: A): String                                   = serde.serialize(value)
      def deserialize(body: Array[Byte]): Either[api.Serde.Failure, A]  = serde.deserialize(body)
      def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]) = {
        handed :+= decided
        serde.deserialize(body, decided)
      }
    }
    val memoStore  = InMemoryStore.scoped(memoOwner)
    val memoRoutes = {
      given api.Serde[Memo] = recording(summon[api.Serde[Memo]])
      RouteTable(Api.derived[Memo].routes(memoStore, ownedMemos(Set(Action.Index))))
    }
    memoRoutes.dispatch(caller(ada, Method.POST, "/api/memos", """{"text":"mine"}"""))
    val memo = memoStore.all().head
    assertEquals(handed, Seq(Map("id" -> Id[Any](memo.id.value), "owner" -> Id[Any](ada.value))))

    handed = Seq.empty
    val gadgetStore  = InMemoryStore[Gadget]()
    val gadgetRoutes = {
      given api.Serde[Gadget] = recording(gadgetSerde)
      RouteTable(Api.derived[Gadget].routes(gadgetStore))
    }
    gadgetRoutes.dispatch(program(Method.POST, "/api/gadgets", """{"name":"Nut","cents":1}"""))
    assertEquals(handed, Seq(Map("id" -> Id[Any](gadgetStore.all().head.id.value))))
  }

  test("a Serde that trusts the body still cannot choose the key or the owner of a row") {
    val store  = InMemoryStore.scoped(memoOwner)
    val routes = {
      given api.Serde[Memo] = trusting
      RouteTable(Api.derived[Memo].routes(store, ownedMemos(Set(Action.Index, Action.Update))))
    }
    val claimed = Id.gen[Memo]()
    val forged  = s"""{"id":"${claimed.show}","owner":"${grace.show}","text":"forged"}"""
    val created = routes.dispatch(caller(ada, Method.POST, "/api/memos", forged))
    assertEquals(created.status, 201)
    val stored = store.all().head
    assertNotEquals(stored.id, claimed)
    assertEquals(stored.owner, ada)
    assertEquals(created.header("Location"), Some(s"/api/memos/${stored.id.show}"))

    val updated = routes.dispatch(caller(ada, Method.PUT, s"/api/memos/${stored.id.show}", forged))
    assertEquals(updated.status, 200)
    assertEquals(store.all(), Seq(Memo(stored.id, ada, "forged")))
  }

  test("a create body naming another id is stored under the minted key, and points at it") {
    val (routes, store) = memos(Set(Action.Index))
    val claimed         = Id.gen[Memo]()
    val response        = routes.dispatch(
      caller(ada, Method.POST, "/api/memos", s"""{"id":"${claimed.show}","text":"keyed"}""")
    )
    assertEquals(response.status, 201)
    val stored = store.all().find(_.text == "keyed").get
    assertNotEquals(stored.id, claimed)
    assertEquals(response.header("Location"), Some(s"/api/memos/${stored.id.show}"))
  }

  test(
    "an update body that names no owner keeps the row's owner when ownership does not cover it"
  ) {
    val (routes, store) = memos(Set(Action.Index))
    val graces          = store.all().find(_.owner == grace).get
    val response        = routes.dispatch(
      caller(ada, Method.PUT, s"/api/memos/${graces.id.show}", """{"text":"edited"}""")
    )
    assertEquals(response.status, 200)
    assertEquals(store.find(graces.id), Some(Memo(graces.id, grace, "edited")))
  }

  test("an update ownership covers records the requester, whether or not the body names an owner") {
    val (routes, store) = memos(Set(Action.Index, Action.Update))
    val adas            = store.all().find(_.owner == ada).get
    val path            = s"/api/memos/${adas.id.show}"
    assertEquals(routes.dispatch(caller(ada, Method.PUT, path, """{"text":"one"}""")).status, 200)
    assertEquals(store.find(adas.id), Some(Memo(adas.id, ada, "one")))
    val named = s"""{"owner":"${grace.show}","text":"two"}"""
    assertEquals(routes.dispatch(caller(ada, Method.PUT, path, named)).status, 200)
    assertEquals(store.find(adas.id), Some(Memo(adas.id, ada, "two")))
  }

  test("an update ownership does not cover keeps the row's owner, whatever the body names") {
    val (routes, store) = memos(Set(Action.Index))
    val graces          = store.all().find(_.owner == grace).get
    val response        = routes.dispatch(
      caller(
        ada,
        Method.PUT,
        s"/api/memos/${graces.id.show}",
        s"""{"owner":"${ada.show}","text":"edited"}"""
      )
    )
    assertEquals(response.status, 200)
    assertEquals(store.find(graces.id), Some(Memo(graces.id, grace, "edited")))
  }

  test("an update ownership covers refuses another's row the way the page does, and keeps it") {
    // Show is mounted and not covered, so the row is readable by anyone and saying it is somebody
    // else's reveals nothing: the 403 Ownership.missing answers on a page.
    val (routes, store) = memos(Set(Action.Index, Action.Update))
    val graces          = store.all().find(_.owner == grace).get
    intercept[Forbidden](
      routes.dispatch(
        caller(ada, Method.PUT, s"/api/memos/${graces.id.show}", """{"owner":"x","text":"no"}""")
      )
    )
    assertEquals(store.find(graces.id).map(_.text), Some("grace's"))
  }

  test("an owner naming the key is refused at mount, as Resource refuses it") {
    val byKey: OwnerOf[Memo, Id[Memo]] = OwnerOf("id", _.id)
    val owned                          = Owned[Memo, Memo](
      byKey,
      request => request.currentUser.map(text => Id[Memo](UUID.fromString(text))),
      Action.values.toSet,
      named
    )
    val thrown = intercept[IllegalStateException] {
      summon[Api[Memo]].routes(InMemoryStore.scoped(byKey), owned)
    }
    assert(thrown.getMessage.contains("""but "id" is the key"""), thrown.getMessage)
  }

  /** A memo store holding one row each for ada and grace, and the five over it, owned with these
    * actions covered and every route guarded by [[named]].
    */
  private def memos(covers: Set[Action]): (RouteTable, Store[Memo]) = {
    val store = InMemoryStore.scoped(memoOwner)
    Seq(ada -> "ada's", grace -> "grace's").foreach { case (who, text) =>
      val key = Id.gen[Memo]()
      store.insert(key, Memo(key, who, text))
    }
    (RouteTable(summon[Api[Memo]].routes(store, ownedMemos(covers))), store)
  }

  /** Memos owned through [[memoOwner]] with these actions covered, every route guarded by
    * [[named]].
    */
  private def ownedMemos(covers: Set[Action]): Owned[Memo, Person] =
    Owned[Memo, Person](
      memoOwner,
      request => request.currentUser.map(text => Id[Person](UUID.fromString(text))),
      covers,
      named
    )

  /** The request a program naming `who` sends, the way a guard's token arm names it. */
  private def caller(who: Id[Person], method: Method, path: String, body: String = ""): Request =
    program(method, path, body).copy(headers = Map("X-User" -> Seq(who.show)))

  private def memoTextOf(item: Json): String = item match {
    case Json.Obj(fields) => fields.toMap.get("text").collect { case Json.Str(t) => t }.get
    case other            => fail(s"not an object: $other")
  }

  // ---------------------------------------------------------------- the table

  test("the HTML seven and the JSON five of one model share a table, a store and nothing else") {
    val store  = InMemoryStore[BlogPost]()
    val routes = RouteTable(
      summon[Resource[BlogPost]].routes(store) ++ summon[Api[BlogPost]].routes(store)
    )
    assertEquals(routes.routes.size, 12)
    assertEquals(routes.shadowed, Seq.empty)
    assertEquals(
      routes.routes.take(7).map(_.describe),
      summon[Resource[BlogPost]].routes(store).map(_.describe)
    )
  }

  test("a handwritten API route at the same method and path takes over its derived twin") {
    val handwritten = Route.handwritten(
      Method.GET,
      "/api/gadgets",
      (_: api.Request) => Response.status(299)
    )
    val routes = RouteTable(handwritten +: summon[Api[Gadget]].routes(InMemoryStore()))
    assertEquals(routes.overridden.map(_.describe), Seq("GET /api/gadgets"))
    assertEquals(routes.routes.size, 5)
    assertEquals(routes.dispatch(program(Method.GET, "/api/gadgets")).status, 299)
    assertEquals(
      RouteReport.warnings(routes),
      Seq(RouteReport.overridden(routes.overridden.head))
    )
  }

  test("the listing marks the five as API routes, in text and in JSON") {
    assertEquals(
      RouteReport.listing(summon[Api[Gadget]].routes(InMemoryStore())),
      """5 routes:
        |  GET /api/gadgets api
        |  GET /api/gadgets/:id api
        |  POST /api/gadgets api
        |  PUT /api/gadgets/:id api
        |  DELETE /api/gadgets/:id api""".stripMargin
    )
    val listed = cli.RenderJson.routes(
      cli.RouteListing(summon[Api[Gadget]].routes(InMemoryStore()), Nil, Nil, Nil)
    )
    tree(listed) match {
      case Json.Obj(fields) =>
        fields.toMap.get("routes") match {
          case Some(Json.Arr(routes)) =>
            assertEquals(routes.size, 5)
            routes.foreach {
              case Json.Obj(route) =>
                assertEquals(route.toMap.get("api"), Some(Json.Bool(true)), route)
                assertEquals(route.toMap.get("provenance"), Some(Json.Str("derived")), route)
              case other => fail(s"not a route: $other")
            }
          case other => fail(s"no routes: $other")
        }
      case other => fail(s"not an object: $other")
    }
  }

  // ---------------------------------------------------------------- deriving

  test("a model with no id field does not derive, and the refusal names Api") {
    val errors = compileErrors(
      "case class Keyless(name: String) derives Api\n" +
        "object Keyless { given api.Serde[Keyless] = ApiSuite.blank }"
    )
    assert(
      errors.contains(
        "A model deriving Api needs a field named `id`, typed Id[Model]: its routes read it " +
          "out of the path, and `create` mints one with Id.gen()."
      ),
      errors
    )
  }

  test("a model whose id is not an Id[Model] does not derive, and the refusal says why") {
    val errors = compileErrors(
      "case class Mistyped(id: io.eezo.core.Id[String], name: String) derives Api\n" +
        "object Mistyped { given api.Serde[Mistyped] = ApiSuite.blank }"
    )
    assert(errors.contains("typed Id[Model]"), errors)
  }

  test("a mistyped id names Api as the derivation that refused it, not Resource") {
    val errors = compileErrors(
      "case class Misnamed(id: io.eezo.core.Id[String], name: String) derives Api\n" +
        "object Misnamed { given api.Serde[Misnamed] = ApiSuite.blank }"
    )
    assert(
      errors.contains(
        "A model deriving Api needs its `id` field typed Id[Model]: `create` mints a key with " +
          "Id.gen(), which no other key type has."
      ),
      errors
    )
  }

  test("a model with no instance is told to derive it, and where its Serde comes from") {
    val errors = compileErrors("summon[Api[io.eezo.http.ApiSuite.Plain]]")
    val lines  = errors.linesIterator.map(_.trim).toSeq
    val first  = lines.indexWhere(_.contains("derives Api"))
    val serde  = lines.indexWhere(_.contains("import io.eezo.json.upickle.given"))
    assert(first >= 0, errors)
    assert(serde > first, errors)
    assert(
      lines.exists(line => line.contains("Resource") && line.contains("optional")),
      errors
    )
    assert(lines.exists(_.contains("pages")), errors)
    assert(!errors.contains("derives Form, Resource, Api"), errors)
  }

  // ---------------------------------------------------------------- the store a program shares

  /** A request a program sends: JSON or nothing in the body, no cookie, no form, no token. */
  private def program(method: Method, path: String, body: String = ""): Request =
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers = Map("Content-Type" -> Seq("application/json")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )

  /** A gadget store holding these rows, the table over it, and the rows in the store's order. */
  private def gadgets(rows: (String, Long)*): (Store[Gadget], RouteTable, Seq[Gadget]) = {
    val store = InMemoryStore[Gadget]()
    rows.foreach { case (name, cents) =>
      val key = Id.gen[Gadget]()
      store.insert(key, Gadget(key, name, cents))
    }
    (store, RouteTable(summon[Api[Gadget]].routes(store)), store.all())
  }

  private def text(response: Response): String = response.body match {
    case Body.Json(text) => text
    case other           => fail(s"expected a JSON body, got $other")
  }

  private def tree(text: String): Json =
    Json.parse(text).fold(reason => fail(s"not JSON: $reason in $text"), identity)

  private def json(response: Response): Json = tree(text(response))
}

object ApiSuite {

  case class Gadget(id: Id[Gadget], name: String, cents: Long) derives Api

  case class BlogPost(id: Id[BlogPost], title: String) derives Form, Resource, Api

  case class Category(id: Id[Category], name: String) derives Form, Resource, Api

  given api.Serde[BlogPost] = blank[BlogPost]
  given api.Serde[Category] = blank[Category]

  /** A read only API. */
  case class Ledger(id: Id[Ledger], memo: String) derives Api

  object Ledger {
    given Actions[Ledger] = Actions.except(Action.Create, Action.Update, Action.Destroy)
  }

  given api.Serde[Ledger] = blank[Ledger]

  /** A model that derives nothing at all. */
  case class Plain(id: Id[Plain])

  /** A declaration over the three writes standing in for a token guard: a program that sends no
    * `X-Key` header is refused the way a guard refuses a missing Bearer token, and the declaration
    * carries a page a browser guard would, so a test can see it is not mounted.
    */
  val writesNeedAKey: Guarded[Gadget] = Guarded(
    Set(Action.Create, Action.Update, Action.Destroy),
    {
      case http: Route.Http =>
        http.copy(handler =
          request =>
            if (request.header("X-Key").isDefined) http.handler(request)
            else throw Unauthorized("no key")
        )
      case other => other
    },
    Seq(Route.derived(Method.GET, "/login", _ => Response.status(200)))
  )

  case class Memo(id: Id[Memo], owner: Id[Person], text: String) derives Api

  val ada: Id[Person]   = Id.gen[Person]()
  val grace: Id[Person] = Id.gen[Person]()

  val memoOwner: OwnerOf[Memo, Id[Person]] = OwnerOf("owner", _.owner)

  /** Every route guarded, the caller named off an `X-User` header the way a guard's token arm names
    * it off a Bearer token, and nobody when the header is missing.
    */
  val named: Guarded[Memo] = Guarded(
    Action.values.toSet,
    {
      case http: Route.Http =>
        http.copy(handler =
          request =>
            request.header("X-User") match {
              case Some(who) => http.handler(request.copy(currentUser = Some(who)))
              case None      => throw Unauthorized("nobody")
            }
        )
      case other => other
    },
    Seq.empty
  )

  /** Memo JSON: an `owner` key and a `text`, with each field eezo decided under the key it is
    * handed, so a body may leave the owner out.
    */
  given api.Serde[Memo] with {
    def serialize(value: Memo): String =
      Json.render(
        Json.Obj(
          List(
            "id"    -> Json.Str(value.id.show),
            "owner" -> Json.Str(value.owner.show),
            "text"  -> Json.Str(value.text)
          )
        )
      )

    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, Memo] =
      deserialize(body, Map.empty)

    def deserialize(
        body: Array[Byte],
        decided: Map[String, Id[Any]]
    ): Either[api.Serde.Failure, Memo] =
      memoFrom(body) { members =>
        val owner = decided.get("owner").map(_.value).orElse(ownerIn(members))
        val key   = decided.get("id").map(_.value).getOrElse(UUID.randomUUID())
        (owner, members.get("text")) match {
          case (Some(who), Some(Json.Str(text))) => Right(Memo(Id(key), Id(who), text))
          case _ => Left(api.Serde.Failure.Unfit("a memo needs an owner and a text"))
        }
      }
  }

  /** Memo JSON read the way a hand written instance can get wrong: the key and the owner are
    * whatever the body names, and what eezo decided is never looked at.
    */
  val trusting: api.Serde[Memo] = new api.Serde[Memo] {
    def serialize(value: Memo): String = summon[api.Serde[Memo]].serialize(value)

    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, Memo] =
      memoFrom(body) { members =>
        val key = members.get("id").collect { case Json.Str(text) => UUID.fromString(text) }
        (key, ownerIn(members), members.get("text")) match {
          case (Some(key), Some(who), Some(Json.Str(text))) => Right(Memo(Id(key), Id(who), text))
          case _ => Left(api.Serde.Failure.Unfit("a memo needs an id, an owner and a text"))
        }
      }

    def deserialize(
        body: Array[Byte],
        decided: Map[String, Id[Any]]
    ): Either[api.Serde.Failure, Memo] = deserialize(body)
  }

  private def memoFrom(body: Array[Byte])(
      read: Map[String, Json] => Either[api.Serde.Failure, Memo]
  ): Either[api.Serde.Failure, Memo] =
    Json.parse(new String(body, StandardCharsets.UTF_8)) match {
      case Right(Json.Obj(fields)) => read(fields.toMap)
      case Right(_)                => Left(api.Serde.Failure.Unfit("a memo is an object"))
      case Left(reason)            => Left(api.Serde.Failure.Malformed(reason))
    }

  /** An owner that is no key still reads, as a key nobody holds: the route overwrites it. */
  private def ownerIn(members: Map[String, Json]): Option[UUID] =
    members.get("owner").collect { case Json.Str(owner) =>
      scala.util
        .Try(UUID.fromString(owner))
        .getOrElse(UUID.nameUUIDFromBytes(owner.getBytes(StandardCharsets.UTF_8)))
    }

  /** A Serde for a model whose JSON no test reads, which only its routes' paths are about. */
  def blank[A]: api.Serde[A] = new api.Serde[A] {
    def serialize(value: A): String                                   = "{}"
    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, A]  = Left(nothing)
    def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]) = Left(nothing)
    private def nothing = api.Serde.Failure.Unfit("no test reads this")
  }

  /** The same model's routes over a Serde whose decided read ignores the key it is handed, the
    * mistake a hand written instance can make.
    */
  val careless: Api[Gadget] = {
    given api.Serde[Gadget] = new api.Serde[Gadget] {
      def serialize(value: Gadget): String = gadgetSerde.serialize(value)
      def deserialize(body: Array[Byte])   = gadgetSerde.deserialize(body)
      def deserialize(body: Array[Byte], decided: Map[String, Id[Any]]) =
        gadgetSerde.deserialize(body)
    }
    Api.derived[Gadget]
  }

  /** A Serde over `core`'s own reader: an object with a text `name` and a whole `cents`, and an
    * `id` the decided read replaces. Anything that is not JSON is malformed, and JSON that is not
    * that object is unfit, which is the split the adapter makes.
    */
  given api.Serde[Gadget] = gadgetSerde

  object gadgetSerde extends api.Serde[Gadget] {
    def serialize(value: Gadget): String =
      Json.render(
        Json.Obj(
          List(
            "id"    -> Json.Str(value.id.show),
            "name"  -> Json.Str(value.name),
            "cents" -> Json.Num(value.cents)
          )
        )
      )

    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, Gadget] = read(body, None)

    def deserialize(
        body: Array[Byte],
        decided: Map[String, Id[Any]]
    ): Either[api.Serde.Failure, Gadget] =
      read(body, decided.get("id").map(key => Id(key.value)))

    private def read(body: Array[Byte], key: Option[Id[Gadget]]) =
      Json.parse(new String(body, StandardCharsets.UTF_8)) match {
        case Left(reason)            => Left(api.Serde.Failure.Malformed(reason))
        case Right(Json.Obj(fields)) =>
          val members = fields.toMap
          val id      = key.orElse(members.get("id").collect { case Json.Str(text) =>
            Id[Gadget](UUID.fromString(text))
          })
          (id, members.get("name"), members.get("cents")) match {
            case (Some(id), Some(Json.Str(name)), Some(Json.Num(cents))) =>
              Right(Gadget(id, name, cents))
            case _ => Left(api.Serde.Failure.Unfit("a gadget needs a name and whole cents"))
          }
        case Right(_) => Left(api.Serde.Failure.Unfit("a gadget is an object"))
      }
  }
}
