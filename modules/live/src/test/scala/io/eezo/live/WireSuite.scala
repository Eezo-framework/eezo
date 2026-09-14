package io.eezo.live

import io.eezo.core.html.Tags.*
import io.eezo.core.internal.Json

/** The frame the server writes, pinned: the client's `JSON.parse` and the harness both read this
  * exact shape, so a renaming here is a breaking change and should look like one in a diff. The
  * patches are constructed directly rather than diffed into existence, because this suite pins the
  * *encoding*; `DiffSuite` pins what the differ chooses to emit.
  */
class WireSuite extends munit.FunSuite {

  private def field(patch: Json, name: String): Option[Json] = patch match {
    case Json.Obj(fields) => fields.toMap.get(name)
    case _                => None
  }

  test("every op encodes with its documented fields") {
    val frame = Wire.patches(
      List(
        Patch.SetText(List(0, 1), "a & b"),
        Patch.SetAttr(List(0), "div", "class", "x"),
        Patch.RemoveAttr(List(0), "div", "title"),
        Patch.ReplaceNode(List(0, 2), "span", em("y")),
        Patch.RemoveNode(List(0, 3), "li"),
        Patch.AppendChildren(List(0), "ul", Vector(li("z"))),
        Patch.SetChildren(Nil, None, Vector(div("root")))
      )
    )

    val Right(Json.Obj(fields)) = Json.parse(frame): @unchecked
    assertEquals(fields.toMap.get("kind"), Some(Json.Str("patches")))

    val Some(Json.Arr(patches)) = fields.toMap.get("patches"): @unchecked
    assertEquals(
      patches.flatMap(field(_, "op")),
      List(
        Json.Str("setText"),
        Json.Str("setAttr"),
        Json.Str("removeAttr"),
        Json.Str("replaceNode"),
        Json.Str("removeNode"),
        Json.Str("appendChildren"),
        Json.Str("setChildren")
      )
    )

    assertEquals(field(patches(0), "path"), Some(Json.Arr(List(Json.Num(0), Json.Num(1)))))
    assertEquals(field(patches(0), "text"), Some(Json.Str("a & b")))
    assertEquals(field(patches(1), "expect"), Some(Json.Str("div")))
    assertEquals(field(patches(1), "name"), Some(Json.Str("class")))
    assertEquals(field(patches(1), "value"), Some(Json.Str("x")))
    assertEquals(field(patches(2), "name"), Some(Json.Str("title")))
    assertEquals(field(patches(3), "html"), Some(Json.Str("<em>y</em>")))
    assertEquals(field(patches(4), "expect"), Some(Json.Str("li")))
    assertEquals(field(patches(5), "html"), Some(Json.Str("<li>z</li>")))
    // The anchor's setChildren has nothing to verify: null, not absent, so the client reads one
    // shape.
    assertEquals(field(patches(6), "expect"), Some(Json.Null))
    assertEquals(field(patches(6), "html"), Some(Json.Str("<div>root</div>")))
  }

  test("no difference is an empty patch list, not an absent frame") {
    assertEquals(
      Json.parse(Wire.patches(Nil)),
      Right(Json.Obj(List("kind" -> Json.Str("patches"), "patches" -> Json.Arr(Nil))))
    )
  }

  test("markup in the payload survives the JSON round trip byte for byte") {
    val tree  = div(p("""a<b & "c""""), span("it's"))
    val frame = Wire.patches(List(Patch.SetChildren(Nil, None, Vector(tree))))
    Json.parse(frame) match {
      case Right(Json.Obj(fields)) =>
        val Some(Json.Arr(List(patch))) = fields.toMap.get("patches"): @unchecked
        assertEquals(field(patch, "html"), Some(Json.Str(tree.render)))
      case other => fail(s"not an object: $other")
    }
  }
}
