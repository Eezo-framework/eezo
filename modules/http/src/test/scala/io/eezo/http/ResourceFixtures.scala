package io.eezo.http

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import io.eezo.core.Id

/** The model every suite that drives a derived `Resource` reaches for: three fields, one of them
  * numeric, so that a rejected submission is one bad character away.
  */
case class Widget(id: Id[Widget], name: String, price: Int) derives Form, Resource

/** What a suite needs to drive routes the way a server does and to read back what came out.
  *
  * Mixed into a `FunSuite` rather than kept in an object because `markup` fails the test when the
  * body is not HTML, and failing is the suite's own business.
  */
trait ResourceFixtures { self: munit.FunSuite =>

  /** The token the browser these fixtures stand in for was handed on its first visit: in its
    * session on every request, and returned by every unsafe one, the way a real form does.
    */
  val token: Csrf.Token = Csrf.Token.gen()

  /** A request the way a browser that has seen the application sends it: the session carries the
    * token, and a `POST`, `PUT` or `DELETE` returns it in its form encoded body, which is the only
    * body shape the derived routes read.
    */
  def request(method: Method, path: String, form: (String, String)*): Request = {
    val returned = if (method.safe) form else form :+ (Csrf.Field -> token.value)
    forged(method, path, returned*)
  }

  /** A request from a page eezo did not serve: the browser has a session, so the token is there to
    * compare against, and the body does not return it.
    */
  def forged(method: Method, path: String, form: (String, String)*): Request =
    anonymous(method, path, form*).copy(session = Csrf.carrying(Session.empty, token))

  /** A request from a browser never seen before: no session, so no token anywhere, and a form
    * encoded body when there are fields.
    */
  def anonymous(method: Method, path: String, form: (String, String)*): Request = {
    val body = form
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers =
        if (form.isEmpty) Map.empty
        else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )
  }

  def markup(response: Response): String = response.body match {
    case Body.Html(node) => node.render
    case other           => fail(s"expected an HTML body, got $other")
  }

  def location(response: Response): String = response.header("Location").getOrElse("")
}
