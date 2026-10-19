package io.eezo.http

import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

import io.eezo.core.internal.Json

/** A failure that escapes the handler of an API route, answered the way a program reads one: RFC
  * 9457's `application/problem+json`, carrying the very `Problem` the HTML page would show.
  *
  * The expected value is always asked of `Boundary.resolve` rather than written out here, because
  * the claim is that the two renderings cannot disagree, and a second copy of what a 404 says would
  * only prove that this suite agrees with itself.
  */
class ProblemJsonSuite extends munit.FunSuite with ServerFixtures {

  /** An application's own failure, which only the `problems` hook gives a status. */
  private final class OutOfStock extends RuntimeException("the mug is out of stock")

  private val hook: PartialFunction[Throwable, Problem] = { case _: OutOfStock =>
    Problem(status = 409, detail = "the mug is out of stock", instance = "/api/orders")
  }

  /** The path each handler last saw, so the instance can be checked against what eezo read. */
  private val seen = new AtomicReference[String]("")

  private def failing(failure: => Throwable): RouteTable =
    RouteTable(
      Seq(
        Route.handwritten(
          Method.GET,
          "/api/:id",
          (request: api.Request) => { seen.set(request.path); throw failure }
        ),
        Route.Http(
          Method.GET,
          PathPattern.parse("/page/:id"),
          request => { seen.set(request.path); throw failure }
        )
      )
    )

  private def members(response: HttpResponse[String]): Map[String, Json] =
    Json.parse(response.body()) match {
      case Right(Json.Obj(fields)) =>
        assertEquals(fields.map(_._1), List("type", "title", "status", "detail", "instance"))
        fields.toMap
      case other => fail(s"not a JSON object: $other in ${response.body()}")
    }

  /** The problem the HTML page shows for the same failure on the same path. */
  private def expected(failure: Throwable, dev: Boolean = false): Problem =
    Boundary.resolve(failure, seen.get, HttpConfig(dev = dev, problems = hook)).problem

  private def assertProblem(response: HttpResponse[String], problem: Problem): Unit = {
    assertEquals(response.statusCode(), problem.status)
    assertEquals(
      response.headers().allValues("Content-Type").toArray.toSeq,
      Seq("application/problem+json")
    )
    assertEquals(
      members(response),
      Map(
        "type"     -> Json.Str(problem.tpe),
        "title"    -> Json.Str(problem.title),
        "status"   -> Json.Num(problem.status.toLong),
        "detail"   -> Json.Str(problem.detail),
        "instance" -> Json.Str(problem.instance)
      )
    )
  }

  test("a NotFound thrown on an API route answers 404 as problem+json") {
    val failure = NotFound("/widgets/7")
    serving(failing(failure)) { (_, port) =>
      val response = get(port, "/api/7")
      assertProblem(response, expected(failure))
      assertEquals(members(response)("type"), Json.Str("about:blank"))
      assertEquals(members(response)("title"), Json.Str("Not Found"))
      assertEquals(members(response)("instance"), Json.Str("/api/7"))
    }
  }

  test("a BadRequest and a Forbidden thrown on an API route answer as problem+json") {
    Seq(BadRequest("id is not a number"), Forbidden("bad signature")).foreach { failure =>
      serving(failing(failure)) { (_, port) =>
        assertProblem(get(port, "/api/abc"), expected(failure))
      }
    }
  }

  test("a value the problems hook maps answers as problem+json on an API route") {
    val failure = new OutOfStock
    serving(failing(failure), problems = hook) { (_, port) =>
      val response = get(port, "/api/mug")
      assertEquals(response.statusCode(), 409)
      assertProblem(response, expected(failure))
    }
  }

  test("an unexpected failure on an API route is a redacted 500 outside dev, its message in dev") {
    val failure = new IllegalStateException("password=hunter2 in the connection string")
    serving(failing(failure)) { (_, port) =>
      val response = get(port, "/api/1")
      assertProblem(response, expected(failure))
      assert(!response.body().contains("hunter2"), response.body())
    }
    serving(failing(failure), dev = true) { (_, port) =>
      val response = get(port, "/api/1")
      assertProblem(response, expected(failure, dev = true))
      assertEquals(
        members(response)("detail"),
        Json.Str("password=hunter2 in the connection string")
      )
    }
  }

  test("a header the boundary decides still goes out beside the JSON") {
    val failure = MethodNotAllowed(Seq(Method.GET, Method.POST))
    serving(failing(failure)) { (_, port) =>
      val response = get(port, "/api/1")
      assertProblem(response, expected(failure))
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET, POST")
    }
  }

  test("text a client or an exception controls is escaped, and the body parses back") {
    val nasty   = "a \"quoted\" back\\slash\nnew line\ttab \u0001 control </script>"
    val failure = BadRequest(nasty)
    serving(failing(failure)) { (_, port) =>
      val response = get(port, "/api/a%22b")
      assertProblem(response, expected(failure))
      assertEquals(members(response)("detail"), Json.Str(nasty))
    }
    // The server hands a handler its path still percent encoded, so a quote reaches an instance
    // through a hook that builds its own, which is text eezo did not write either.
    val instance                                 = "/api/\"1\"\\" + nasty
    val odd: PartialFunction[Throwable, Problem] = { case _: OutOfStock =>
      Problem(status = 422, detail = nasty, instance = instance)
    }
    serving(failing(new OutOfStock), problems = odd) { (_, port) =>
      val response = get(port, "/api/1")
      assertEquals(response.statusCode(), 422)
      assertEquals(members(response)("detail"), Json.Str(nasty))
      assertEquals(members(response)("instance"), Json.Str(instance))
    }
  }

  test("the same failure on a browser route is still the HTML page, in the layout") {
    val failure = NotFound("/widgets/7")
    serving(failing(failure), layout = marked) { (_, port) =>
      val response = get(port, "/page/7")
      assertEquals(response.statusCode(), 404)
      assertEquals(
        response.headers().allValues("Content-Type").toArray.toSeq,
        Seq("text/html; charset=utf-8")
      )
      assert(clue(response.body()).contains("framed"))
      assert(clue(response.body()).contains("404 Not Found"))
    }
  }

  test("a failure before any API route matched stays the HTML page") {
    serving(failing(NotFound("/x"))) { (_, port) =>
      Seq(get(port, "/api"), send(port, "POST", "/api/7", None)).foreach { response =>
        assert(Set(404, 405)(response.statusCode()), response.statusCode())
        assertEquals(
          response.headers().allValues("Content-Type").toArray.toSeq,
          Seq("text/html; charset=utf-8")
        )
      }
    }
  }
}
