package io.eezo

import io.eezo.db.schema.Change
import io.eezo.http.{Body, Csrf}

/** The drift page's forms. What they post to is served through the same dispatch as any application
  * route, so a `POST` without the token is refused there; this suite pins the other half, that the
  * page hands the browser the token to return.
  */
class DriftGateSuite extends munit.FunSuite {

  private val drift = List(Change.DropColumn("posts", "body"))

  private def markup(token: Csrf.Token): String =
    DriftGate.refusal(drift, error = None, token).body match {
      case Body.Html(node) => node.render
      case other           => fail(s"expected an HTML body, got $other")
    }

  test("both forms on the drift page carry the CSRF token, since both apply changes") {
    val token  = Csrf.Token.gen()
    val page   = markup(token)
    val hidden = Csrf.hidden(token).render
    val forms  = page.split("<form").drop(1)
    assertEquals(forms.length, 2, page)
    forms.foreach(form => assert(form.contains(hidden), form))
  }
}
