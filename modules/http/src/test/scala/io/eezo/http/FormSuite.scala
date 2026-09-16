package io.eezo.http

import java.util.UUID

import io.eezo.core.Id
import io.eezo.core.html.Html

/** What `derives Form` produces, and what it refuses to produce.
  *
  * Every case here is a decision from #111 that could regress in silence: the key never reaching
  * the markup, a keyless model deriving at all, the unchecked checkbox, and the escaping that
  * `core` owns rather than this module.
  */
class FormSuite extends munit.FunSuite {

  case class Widget(
      id: UUID,
      name: String,
      price: Int,
      inStock: Boolean,
      note: Option[String]
  ) derives Form

  case class Login(email: String, password: String) derives Form

  /** The shape every real eezo model has: a key of type `Id[A]`, which `TableMacro` requires and
    * which this module could not see at all while `Id` lived in `db`.
    */
  case class Gadget(id: Id[Gadget], name: String) derives Form

  private val theKey = UUID.fromString("11111111-2222-3333-4444-555555555555")

  private val widget = Widget(theKey, "Bolt", 3, inStock = true, note = Some("hex"))

  /** What a test that renders a form outside a request hands it; inside one it is `request.csrf`.
    */
  private val token = Csrf.Token.gen()

  private def data(pairs: (String, String)*): Map[String, Seq[String]] =
    pairs.groupMap(_._1)(_._2)

  // ---------------------------------------------------------------- fields

  test("fields are declaration order with the key absent") {
    assertEquals(
      Form[Widget].fields.map(_.name),
      Seq("name", "price", "inStock", "note")
    )
  }

  test("a label humanises the Scala name while the name stays exact") {
    val f = Form[Widget].fields.find(_.name == "inStock").get
    assertEquals(f.label, "In stock")
    assertEquals(f.inputType, "checkbox")
  }

  test("input types come from the field type") {
    val byName = Form[Widget].fields.map(f => f.name -> f.inputType).toMap
    assertEquals(byName("name"), "text")
    assertEquals(byName("price"), "number")
    assertEquals(byName("note"), "text")
  }

  // ---------------------------------------------------------------- render

  test("the key is never rendered") {
    val html = Form[Widget].render("/widgets", Method.POST, Some(widget), token).render
    assert(!html.contains(theKey.toString), html)
    assert(!html.contains("""name="id""""), html)
  }

  test("a GET or POST form carries no _method, and anything else does") {
    val post = Form[Widget].render("/widgets", Method.POST, None, token).render
    assert(!post.contains("_method"), post)
    assert(post.contains("""method="post""""), post)

    val put = Form[Widget].render("/widgets/1", Method.PUT, None, token).render
    assert(put.contains("""<input type="hidden" name="_method" value="PUT">"""), put)
    assert(put.contains("""method="post""""), put)
  }

  test("every form carries the CSRF token as a hidden input, whatever its verb") {
    val hidden = Csrf.hidden(token).render
    val post   = Form[Widget].render("/widgets", Method.POST, None, token).render
    assert(post.contains(hidden), post)
    val put = Form[Widget].render("/widgets/1", Method.PUT, None, token).render
    assert(put.contains(hidden), put)
    // Beside `_method`, and before the first field, so the two reserved inputs read as one block.
    assert(
      put.contains(s"""name="_method" value="PUT">$hidden<div>"""),
      put
    )
  }

  test("an existing value fills the inputs, and a checked box renders bare checked") {
    val html = Form[Widget].render("/widgets", Method.POST, Some(widget), token).render
    assert(html.contains("""value="Bolt""""), html)
    assert(html.contains("""type="checkbox""""), html)
    assert(html.contains(" checked>"), html)
  }

  test("a value is escaped by core's constructor, not by anything here") {
    val nasty = widget.copy(name = "<script>alert(1)</script>")
    val html  = Form[Widget].render("/widgets", Method.POST, Some(nasty), token).render
    assert(!html.contains("<script>"), html)
    assert(html.contains("&lt;script&gt;"), html)
  }

  test("errors render beside their field") {
    val errs = FormErrors(Seq(FieldError("price", "is not a number")))
    val html = Form[Widget].render("/widgets", Method.POST, None, token, errs).render
    assert(html.contains("is not a number"), html)
  }

  // ---------------------------------------------------------------- show

  test("show pairs every rendered field with its value, and never the key") {
    val shown = Form[Widget].show(widget)
    assertEquals(shown.map(_._1.name), Seq("name", "price", "inStock", "note"))
    assertEquals(shown.map(_._2), Seq("Bolt", "3", "on", "hex"))
  }

  test("show reads an Option through its Field rather than printing Some") {
    val shown = Form[Widget].show(widget.copy(note = None)).toMap.map { case (f, v) => f.name -> v }
    assertEquals(shown("note"), "")
  }

  test("show and the edit form cannot disagree about one field's text") {
    val shown = Form[Widget].show(widget).collectFirst { case (f, v) if f.name == "name" => v }.get
    val html  = Form[Widget].render("/widgets/1", Method.PUT, Some(widget), token).render
    assert(html.contains(s"""value="$shown""""), html)
  }

  // ---------------------------------------------------------------- raw

  test("a rejected submission comes back with what was typed, beside its errors") {
    val errs = FormErrors(Seq(FieldError("price", "is not a number")))
    val html = Form[Widget]
      .render(
        "/widgets",
        Method.POST,
        None,
        token,
        errs,
        raw = data("name" -> "Bolt", "price" -> "cheap")
      )
      .render
    assert(html.contains("""value="Bolt""""), html)
    assert(html.contains("""value="cheap""""), html)
    assert(html.contains("is not a number"), html)
  }

  test("raw wins field by field over value, and a field it omits keeps the value's text") {
    val html = Form[Widget]
      .render("/widgets/1", Method.PUT, Some(widget), token, raw = data("name" -> "Nut"))
      .render
    assert(html.contains("""value="Nut""""), html)
    assert(!html.contains("""value="Bolt""""), html)
    assert(html.contains("""value="3""""), html)
  }

  test("a box the rejected submission had checked comes back checked") {
    val html = Form[Widget]
      .render("/widgets", Method.POST, None, token, raw = data("inStock" -> "on"))
      .render
    assert(html.contains(" checked>"), html)
  }

  test("a raw multi valued key takes the first value") {
    val html = Form[Widget]
      .render("/widgets", Method.POST, None, token, raw = Map("name" -> Seq("Bolt", "Nut")))
      .render
    assert(html.contains("""value="Bolt""""), html)
  }

  // ---------------------------------------------------------------- parse

  test("a keyed model parses with the caller's key as raw text") {
    val parsed = Form[Widget].parse(
      data("name" -> "Bolt", "price" -> "3", "inStock" -> "on", "note" -> "hex"),
      Some(theKey.toString)
    )
    assertEquals(parsed, Right(widget))
  }

  test("a model keyed by Id derives, renders without its key, and parses one back") {
    val key    = Id.gen[Gadget]()
    val gadget = Gadget(key, "Sprocket")
    val html   = Form[Gadget].render("/gadgets", Method.POST, Some(gadget), token).render
    assert(!html.contains(key.show), html)
    assertEquals(Form[Gadget].fields.map(_.name), Seq("name"))
    assertEquals(Form[Gadget].parse(data("name" -> "Sprocket"), Some(key.show)), Right(gadget))
  }

  test("a keyless case class derives and parses") {
    val parsed = Form[Login].parse(data("email" -> "a@b.c", "password" -> "s"), None)
    assertEquals(parsed, Right(Login("a@b.c", "s")))
  }

  test("a keyed model parsed with no key throws, naming the model") {
    val e = intercept[IllegalArgumentException] {
      Form[Widget].parse(data("name" -> "Bolt", "price" -> "3"), None)
    }
    assert(e.getMessage.contains("Widget"), e.getMessage)
  }

  test("an undecodable key is a BadRequest") {
    val e = intercept[BadRequest] {
      Form[Widget].parse(data("name" -> "Bolt", "price" -> "3"), Some("not-a-uuid"))
    }
    assert(e.detail.contains("id"), e.detail)
  }

  test("an undecodable key is a BadRequest for a model keyed by Id too") {
    val e = intercept[BadRequest] {
      Form[Gadget].parse(data("name" -> "Sprocket"), Some("not-an-id"))
    }
    assertEquals(e.detail, "id is not an id")
  }

  test("an unchecked checkbox reads false rather than missing") {
    val parsed = Form[Widget].parse(
      data("name" -> "Bolt", "price" -> "3"),
      Some(theKey.toString)
    )
    assertEquals(parsed.map(_.inStock), Right(false))
  }

  test("a missing Option field is None, a missing non Option field is required") {
    val parsed = Form[Widget].parse(data("price" -> "3"), Some(theKey.toString))
    assertEquals(parsed, Left(FormErrors(Seq(FieldError("name", "is required")))))
  }

  test("errors accumulate rather than stopping at the first") {
    val parsed = Form[Widget].parse(data("price" -> "cheap"), Some(theKey.toString))
    assertEquals(
      parsed.left.map(_.errors.map(_.name)),
      Left(Seq("name", "price"))
    )
  }

  test("extra submitted keys are ignored, which is what makes _method free") {
    val parsed = Form[Widget].parse(
      data("name" -> "Bolt", "price" -> "3", "_method" -> "PUT", "csrf" -> "x"),
      Some(theKey.toString)
    )
    assertEquals(parsed.map(_.name), Right("Bolt"))
  }

  test("a multi valued key takes the first value") {
    val parsed = Form[Widget].parse(
      Map("name" -> Seq("Bolt", "Nut"), "price" -> Seq("3")),
      Some(theKey.toString)
    )
    assertEquals(parsed.map(_.name), Right("Bolt"))
  }

  // ---------------------------------------------------------------- req.as

  test("req.as is Form.parse over the request's form body") {
    val req = Request(
      method = Method.POST,
      path = "/logins",
      query = Map.empty,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = "email=a%40b.c&password=s".getBytes("UTF-8"),
      pathParams = Map.empty
    )
    assertEquals(req.as[Login], Right(Login("a@b.c", "s")))
  }

  test("req.as passes the caller's key through") {
    val req = Request(
      method = Method.POST,
      path = "/widgets",
      query = Map.empty,
      headers = Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = "name=Bolt&price=3".getBytes("UTF-8"),
      pathParams = Map.empty
    )
    assertEquals(req.as[Widget](theKey.toString).map(_.id), Right(theKey))
  }

  // ---------------------------------------------------------------- Field

  test("Option reads an empty submission as None") {
    assertEquals(Field[Option[String]].read(""), Right(None))
    assertEquals(Field[Option[String]].absent, Some(None))
  }

  test("a field that will not decode reports its own message") {
    assert(Field[Int].read("cheap").isLeft)
  }

  test("Html is what the form is made of, so it composes with a layout") {
    val node: Html = Form[Login].render("/login", Method.POST, None, token)
    assert(node.render.startsWith("<form"), node.render)
  }
}
