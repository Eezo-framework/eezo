package io.eezo.http

import io.eezo.core.html.Tags.p

/** The API route over the wire: the session cookie is neither read nor written on it, whatever the
  * caller sends.
  *
  * A caller with no cookie is the easy case and proves little, since it passes as soon as nothing
  * is minted. The cases that matter are a browser that calls an API URL with its cookie: a valid
  * one, one carrying a flash, and one that does not verify. On a browser route each of those gets a
  * `Set-Cookie` back, one signed again that sweeps the flash or an expired one; on an API route
  * none of them may.
  */
class ApiRouteServerSuite extends munit.FunSuite with ServerFixtures {

  private def webhook(request: ApiRequest): Response =
    Response.status(if (request.header("Content-Type").contains("application/json")) 200 else 415)

  private def named(request: ApiRequest): Response =
    Response.status(200).withSession(Session.empty.set("user", request.path))

  private val routes: RouteTable = RouteTable(
    Seq(
      Route.handwritten(Method.POST, "/webhooks/stripe", webhook),
      Route.handwritten(Method.POST, "/webhooks/broken", named),
      Route.Http(
        Method.POST,
        PathPattern.parse("/login"),
        request =>
          Response
            .Redirect("/me")
            .withSession(request.session.set("user", "42").flash("notice", "welcome"))
      ),
      Route.Http(
        Method.GET,
        PathPattern.parse("/me"),
        request =>
          Response.Ok(
            p(
              s"user=${request.session.get("user").getOrElse("nobody")} " +
                s"notice=${request.session.flash("notice").getOrElse("-")}"
            )
          )
      )
    )
  )

  private val json = Seq("Content-Type" -> "application/json")

  private def call(port: Int, cookie: Option[String]) =
    send(port, "POST", "/webhooks/stripe", cookie, headers = json)

  test("a program with no cookie is answered with no session cookie") {
    serving(routes) { (_, port) =>
      val response = call(port, None)
      assertEquals(response.statusCode(), 200)
      assertEquals(sessionCookie(response), None)
    }
  }

  test("a browser calling with a valid session cookie carrying a flash gets no cookie back") {
    serving(routes) { (_, port) =>
      val cookie   = sessionCookie(submit(port, "/login", visit(port, "/me"))).get
      val response = call(port, Some(cookie))
      assertEquals(response.statusCode(), 200)
      assertEquals(sessionCookie(response), None, "the API route wrote the session cookie")
      // The flash was not consumed by the API call: the next page the browser visits still has it.
      assertEquals(
        send(port, "GET", "/me", Some(cookie)).body(),
        plainly("<p>user=42 notice=welcome</p>")
      )
    }
  }

  test("a browser calling with a valid session cookie carrying no flash gets no cookie back") {
    serving(routes) { (_, port) =>
      val browser  = visit(port, "/me")
      val response = call(port, Some(browser.cookie))
      assertEquals(response.statusCode(), 200)
      assertEquals(sessionCookie(response), None)
    }
  }

  test("a tampered session cookie is neither expired nor replaced") {
    serving(routes) { (_, port) =>
      val cookie   = sessionCookie(submit(port, "/login", visit(port, "/me"))).get
      val tampered = cookie.replaceFirst("\\.", "x.")
      val response = call(port, Some(tampered))
      assertEquals(response.statusCode(), 200)
      assertEquals(sessionCookie(response), None, "the API route touched a cookie it cannot read")
    }
  }

  test("a response naming a session is answered 500, with the reason only in dev") {
    serving(routes) { (_, port) =>
      val response = send(port, "POST", "/webhooks/broken", None, headers = json)
      assertEquals(response.statusCode(), 500)
      assert(!response.body().contains("API route"), response.body())
      assertEquals(sessionCookie(response), None)
    }
    serving(routes, dev = true) { (_, port) =>
      val response = send(port, "POST", "/webhooks/broken", None, headers = json)
      assertEquals(response.statusCode(), 500)
      assert(response.body().contains("no session takes part in an API route"), response.body())
    }
  }
}
