package io.eezo.auth

import java.nio.charset.StandardCharsets
import java.util.UUID

import io.eezo.core.{Id, OwnerOf}
import io.eezo.core.internal.Json
import io.eezo.http.*

/** The password guard over the five JSON routes a model derives: a program says who it is with an
  * API token, and an owned model reads and writes that program's user's rows alone.
  *
  * Dispatched through a `RouteTable`, so the session is emptied the way a served API route empties
  * it, and the only thing that can name the caller is the Bearer header.
  */
class ApiGuardSuite extends munit.FunSuite {

  import ApiGuardSuite.*
  import SignInFixtures.*

  private val (annPlain, annToken)     = Token.gen()
  private val (gracePlain, graceToken) = Token.gen()

  private val tokens: Token => Option[Id[User]] = {
    case token if token == annToken   => Some(ann.id)
    case token if token == graceToken => Some(grace.id)
    case _                            => None
  }

  private val guard: Guard[User] =
    Guard[User](
      find = id => Seq(ann, grace).find(_.id == id),
      credentials = _ => None,
      clock = clock,
      tokens = tokens
    )

  /** A program's request, carrying the token it was issued when it has one. */
  private def program(
      method: Method,
      path: String,
      body: String = "",
      token: Option[String] = None
  ): Request =
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers = token.fold(Map.empty)(plain => Map("Authorization" -> Seq(s"Bearer $plain"))),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty,
      // A browser calling the URL with a session: it must count for nothing on an API route.
      session = signedInSession(grace.id)
    )

  // ---------------------------------------------------------------- guarded writes

  private def products(): (io.eezo.core.Store[Product], RouteTable, Product) = {
    val store = InMemoryStore[Product]()
    val bolt  = Product(Id.gen(), "Bolt", 3)
    store.insert(bolt.id, bolt)
    val declared = guard.only[Product](Action.Create, Action.Update, Action.Destroy)
    (store, RouteTable(summon[Api[Product]].routes(store, declared)), bolt)
  }

  test("index and show answer a program with no token, as the declaration leaves them open") {
    val (_, routes, bolt) = products()
    assertEquals(routes.dispatch(program(Method.GET, "/api/products")).status, 200)
    assertEquals(routes.dispatch(program(Method.GET, s"/api/products/${bolt.id.show}")).status, 200)
  }

  test("the writes refuse a program with no token or a token nobody issued, and write nothing") {
    val (store, routes, bolt) = products()
    val member                = s"/api/products/${bolt.id.show}"
    val body                  = """{"name":"Nut","cents":1}"""
    val (stranger, _)         = Token.gen()
    for (token <- Seq(None, Some(stranger))) {
      intercept[Unauthorized](routes.dispatch(program(Method.POST, "/api/products", body, token)))
      intercept[Unauthorized](routes.dispatch(program(Method.PUT, member, body, token)))
      intercept[Unauthorized](routes.dispatch(program(Method.DELETE, member, token = token)))
    }
    assertEquals(store.all(), Seq(bolt))
  }

  test("the writes answer a program with a token its guard knows") {
    val (store, routes, bolt) = products()
    val member                = s"/api/products/${bolt.id.show}"
    val token                 = Some(annPlain)
    assertEquals(
      routes
        .dispatch(program(Method.POST, "/api/products", """{"name":"Nut","cents":1}""", token))
        .status,
      201
    )
    assertEquals(
      routes.dispatch(program(Method.PUT, member, """{"name":"Screw","cents":5}""", token)).status,
      200
    )
    assertEquals(routes.dispatch(program(Method.DELETE, member, token = token)).status, 204)
    assertEquals(store.all().map(_.name), Seq("Nut"))
  }

  test("an API brings no login page with it, though the declaration carries one") {
    val declared = guard.only[Product](Action.Create)
    val routes   = Api.routesOf[Product](InMemoryStore(), declared)
    assertEquals(declared.carries.size, 3)
    assertEquals(routes.map(_.describe).filter(_.contains("/login")), Seq.empty)
    assertEquals(routes.size, 5)
  }

  test("a guard with no tokens lookup over a guarded API route refuses at boot, naming it") {
    val sessionOnly = Guard[User](find = _ => None, credentials = _ => None)
    val thrown      = intercept[IllegalStateException](
      summon[Api[Product]].routes(InMemoryStore(), sessionOnly.only[Product](Action.Destroy))
    )
    assert(clue(thrown.getMessage).startsWith("DELETE /api/products/:id is an API route"))
  }

  // ---------------------------------------------------------------- owned

  /** One note each for ann and grace, behind a declaration owning the index and everything else. */
  private def notes(): (Scoped[Note], RouteTable) = {
    val store = InMemoryStore.scoped(OwnerOf[Note, Id[User]]("author", _.author))
    Seq(ann -> "ann's", grace -> "grace's").foreach { case (who, text) =>
      val key = Id.gen[Note]()
      store.insert(key, Note(key, who.id, text))
    }
    val declared = guard.required[Note].owning(_.author).all
    (store, RouteTable(summon[Api[Note]].routes(store, declared)))
  }

  test("an owned index lists only the rows of the user the token names") {
    val (_, routes) = notes()
    val listed      = routes.dispatch(program(Method.GET, "/api/notes", token = Some(annPlain)))
    assertEquals(listed.status, 200)
    assertEquals(textsOf(listed), Seq("ann's"))
    // The session the request carries names grace, and counts for nothing: her token does.
    assertEquals(
      textsOf(routes.dispatch(program(Method.GET, "/api/notes", token = Some(gracePlain)))),
      Seq("grace's")
    )
  }

  test("a create through a token records the token's user as owner, whatever the body says") {
    val (store, routes) = notes()
    val created         = routes.dispatch(
      program(
        Method.POST,
        "/api/notes",
        s"""{"author":"${grace.id.show}","text":"forged"}""",
        Some(annPlain)
      )
    )
    assertEquals(created.status, 201)
    assertEquals(store.all().filter(_.text == "forged").map(_.author), Seq(ann.id))
  }

  private def textsOf(response: Response): Seq[String] = response.body match {
    case Body.Json(text) =>
      Json.parse(text) match {
        case Right(Json.Arr(items)) =>
          items.collect { case Json.Obj(fields) =>
            fields.toMap.get("text").collect { case Json.Str(t) => t }.getOrElse("")
          }
        case other => fail(s"not an array: $other")
      }
    case other => fail(s"expected a JSON body, got $other")
  }
}

object ApiGuardSuite {

  case class User(id: Id[User], email: String)

  val ann: User   = User(Id.gen(), "ann@example.com")
  val grace: User = User(Id.gen(), "grace@example.com")

  case class Product(id: Id[Product], name: String, cents: Long) derives Api

  case class Note(id: Id[Note], author: Id[User], text: String) derives Api

  /** Reads one object's members, and hands back `None` for anything that is not JSON. */
  private def members(body: Array[Byte]): Either[api.Serde.Failure, Map[String, Json]] =
    Json.parse(new String(body, StandardCharsets.UTF_8)) match {
      case Right(Json.Obj(fields)) => Right(fields.toMap)
      case Right(_)                => Left(api.Serde.Failure.Unfit("not an object"))
      case Left(reason)            => Left(api.Serde.Failure.Malformed(reason))
    }

  given api.Serde[Product] with {
    def serialize(value: Product): String =
      Json.render(
        Json.Obj(
          List(
            "id"    -> Json.Str(value.id.show),
            "name"  -> Json.Str(value.name),
            "cents" -> Json.Num(value.cents)
          )
        )
      )

    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, Product] =
      deserialize(body, Id.gen())

    def deserialize(body: Array[Byte], key: Id[Product]): Either[api.Serde.Failure, Product] =
      members(body).flatMap { m =>
        (m.get("name"), m.get("cents")) match {
          case (Some(Json.Str(name)), Some(Json.Num(cents))) => Right(Product(key, name, cents))
          case _ => Left(api.Serde.Failure.Unfit("a product needs a name and cents"))
        }
      }
  }

  given api.Serde[Note] with {
    def serialize(value: Note): String =
      Json.render(
        Json.Obj(
          List(
            "id"     -> Json.Str(value.id.show),
            "author" -> Json.Str(value.author.show),
            "text"   -> Json.Str(value.text)
          )
        )
      )

    def deserialize(body: Array[Byte]): Either[api.Serde.Failure, Note] =
      deserialize(body, Id.gen())

    def deserialize(body: Array[Byte], key: Id[Note]): Either[api.Serde.Failure, Note] =
      members(body).flatMap { m =>
        (m.get("author"), m.get("text")) match {
          case (Some(Json.Str(author)), Some(Json.Str(text))) =>
            Right(Note(key, Id(UUID.fromString(author)), text))
          case _ => Left(api.Serde.Failure.Unfit("a note needs an author and a text"))
        }
      }
  }
}
