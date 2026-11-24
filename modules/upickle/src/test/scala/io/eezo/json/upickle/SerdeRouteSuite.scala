package io.eezo.json.upickle

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}

import scala.jdk.CollectionConverters.*

import io.eezo.core.html.Tags.p
import io.eezo.core.internal.Json
import io.eezo.http.*

final case class Basket(product: String, quantity: Int)

class SerdeRouteSuite extends munit.FunSuite with ServerFixtures {

  private val routes: RouteTable = RouteTable(
    Seq(
      Route.handwritten(
        Method.POST,
        "/api/baskets",
        (request: api.Request) => {
          val basket = request.as[Basket]
          Response.Created(basket).withHeader("Location", s"/api/baskets/${basket.product}")
        }
      ),
      Route.handwritten(
        Method.GET,
        "/api/baskets/:product",
        (request: api.Request) => Response.Ok(Basket(request.param[String]("product"), 1))
      ),
      Route.handwritten(
        Method.GET,
        "/api/read",
        (request: api.Request) => {
          val _ = request.as[Basket]
          Response.status(204)
        }
      ),
      Route.Http(Method.GET, PathPattern.parse("/page"), _ => Response.Ok(p("a page")))
    )
  )

  private def post(port: Int, path: String, body: String): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://localhost:$port$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def contentTypes(response: HttpResponse[String]): Seq[String] =
    response.headers().allValues("Content-Type").asScala.toSeq

  private def problem(response: HttpResponse[String]): Map[String, Json] =
    Json.parse(response.body()) match {
      case Right(Json.Obj(fields)) => fields.toMap
      case other                   => fail(s"not a JSON object: $other in ${response.body()}")
    }

  private def adapterReason(body: String): String =
    summon[api.Serde[Basket]].deserialize(body.getBytes("UTF-8")) match {
      case Left(api.Serde.Failure.Malformed(reason)) => reason
      case Left(api.Serde.Failure.Unfit(reason))     => reason
      case Right(value)                              => fail(s"the body read as $value")
    }

  test("a body that is not JSON answers 400 as problem+json, with the adapter's reason") {
    serving(routes) { (_, port) =>
      val response = post(port, "/api/baskets", "not json")
      assertEquals(response.statusCode(), 400)
      assertEquals(contentTypes(response), Seq("application/problem+json"))
      assertEquals(problem(response)("detail"), Json.Str(adapterReason("not json")))
    }
  }

  test("a request with no body that asks for one answers 400, never a 500") {
    serving(routes) { (_, port) =>
      val response = get(port, "/api/read")
      assertEquals(response.statusCode(), 400)
      assertEquals(problem(response)("detail"), Json.Str(adapterReason("")))
    }
  }

  test("JSON that does not fit the model answers 422 Unprocessable Content with the reason") {
    Seq("""{"product":"mug"}""", """{"product":"mug","quantity":"two"}""", "[1,2]").foreach {
      body =>
        serving(routes) { (_, port) =>
          val response = post(port, "/api/baskets", body)
          assertEquals(response.statusCode(), 422, body)
          assertEquals(contentTypes(response), Seq("application/problem+json"))
          assertEquals(problem(response)("title"), Json.Str("Unprocessable Content"))
          assertEquals(problem(response)("detail"), Json.Str(adapterReason(body)))
        }
    }
  }

  test("a body that fits is read, and Created answers 201 with it as JSON and its Location") {
    serving(routes) { (_, port) =>
      val response = post(port, "/api/baskets", """{"product":"mug","quantity":2}""")
      assertEquals(response.statusCode(), 201)
      assertEquals(contentTypes(response), Seq("application/json; charset=utf-8"))
      assertEquals(response.body(), """{"product":"mug","quantity":2}""")
      assertEquals(response.headers().firstValue("Location").orElse(""), "/api/baskets/mug")
    }
  }

  test("Ok with a model answers 200 with its serialized text as JSON") {
    serving(routes) { (_, port) =>
      val response = get(port, "/api/baskets/cup")
      assertEquals(response.statusCode(), 200)
      assertEquals(contentTypes(response), Seq("application/json; charset=utf-8"))
      assertEquals(response.body(), summon[api.Serde[Basket]].serialize(Basket("cup", 1)))
    }
  }

  test("Ok with a page answers HTML with the adapter's given in scope") {
    serving(routes) { (_, port) =>
      val response = get(port, "/page")
      assertEquals(response.statusCode(), 200)
      assertEquals(contentTypes(response), Seq("text/html; charset=utf-8"))
      assertEquals(response.body(), plainly("<p>a page</p>"))
    }
  }
}
