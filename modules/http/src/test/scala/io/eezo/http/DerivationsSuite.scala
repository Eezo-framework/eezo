package io.eezo.http

import java.nio.charset.StandardCharsets

import io.eezo.core.Id
import io.eezo.core.html.Html

/** The one question the generated route table asks of every candidate model, asked here of each
  * shape a model can take: pages only, a program's routes only, both, and neither.
  */
class DerivationsSuite extends munit.FunSuite with ResourceFixtures {

  import DerivationsSuite.*

  test("a model mounts the seven of its Resource and the five of its Api, pages first") {
    assertEquals(Derivations.routesOf[Widget](InMemoryStore(), Guarded.public).size, 7)
    assertEquals(Derivations.routesOf[Probe](InMemoryStore(), Guarded.public).size, 5)

    val both = Derivations.routesOf[Book](InMemoryStore(), Guarded.public)
    assertEquals(both.size, 12)
    assertEquals(both.take(7).map(_.describe).head, "GET /books")
    assert(clue(both.take(7).map(_.describe)).forall(!_.contains("/api/")))
    assertEquals(both.drop(7).map(_.describe).head, "GET /api/books")
    assert(clue(both.drop(7).map(_.describe)).forall(_.contains(" /api/books")))
  }

  test("a model that derives neither mounts nothing, and still compiles") {
    assertEquals(Derivations.routesOf[Note](InMemoryStore(), Guarded.public).size, 0)
  }

  test("a model mounts something when it has a Resource, an Api or both, and not otherwise") {
    assert(Derivations.mounts[Widget])
    assert(Derivations.mounts[Probe])
    assert(Derivations.mounts[Book])
    assert(!Derivations.mounts[Note])
  }

  test("a row a program creates is a row the page lists: the two halves share the one store") {
    // The store is an argument expression, as `storeFor[A]` is in the generated row, so a call
    // that evaluated it once per half would hand the pages and the program two stores.
    val routes  = RouteTable(Derivations.routesOf[Book](InMemoryStore(), Guarded.public))
    val created = routes.dispatch(
      Request(
        method = Method.POST,
        path = "/api/books",
        query = Map.empty,
        headers = Map.empty,
        body = "made".getBytes(StandardCharsets.UTF_8),
        pathParams = Map.empty
      )
    )
    assertEquals(created.status, 201)
    assert(clue(markup(routes.dispatch(request(Method.GET, "/books")))).contains("made"))
  }

  test("what a declaration carries goes in front of the seven, and never in front of the five") {
    // A refused browser is sent to the guard's login page, so the pages bring it along; a program
    // is answered 401 and never sent to a page, so the API brings nothing.
    val login =
      Route.Http(Method.GET, PathPattern.parse("/login"), _ => Response.Ok(Html.text("form")))

    val both = Derivations.routesOf[Book](
      InMemoryStore(),
      Guarded[Book](Set(Action.Index), identity, Seq(login))
    )
    assertEquals(both.size, 13)
    assertEquals(both.head, login)
    assertEquals(both.count(_ == login), 1)

    val program = Derivations.routesOf[Probe](
      InMemoryStore(),
      Guarded[Probe](Set(Action.Index), identity, Seq(login))
    )
    assertEquals(program.size, 5)
    assert(!program.contains(login))
  }
}

object DerivationsSuite {

  /** A model with its pages and its API. */
  case class Book(id: Id[Book], title: String) derives Form, Resource, Api

  /** A model whose only routes are the five a program calls. */
  case class Probe(id: Id[Probe], title: String) derives Api

  /** A model with a form and nothing to mount. */
  case class Note(id: Id[Note], text: String) derives Form

  given api.Serde[Book]  = titled(Book(_, _))(_.title)
  given api.Serde[Probe] = titled(Probe(_, _))(_.title)

  /** A Serde that takes the whole body as the title, since nothing here is about JSON: only about
    * which routes a model mounts and which store they read.
    */
  private def titled[A](build: (Id[A], String) => A)(title: A => String): api.Serde[A] =
    new api.Serde[A] {
      def serialize(value: A): String                                  = title(value)
      def deserialize(body: Array[Byte]): Either[api.Serde.Failure, A] =
        deserialize(body, Map.empty)
      def deserialize(
          body: Array[Byte],
          decided: Map[String, Id[Any]]
      ): Either[api.Serde.Failure, A] = {
        val id = decided.get("id").fold(Id.gen[A]())(key => Id(key.value))
        Right(build(id, new String(body, StandardCharsets.UTF_8)))
      }
    }
}
