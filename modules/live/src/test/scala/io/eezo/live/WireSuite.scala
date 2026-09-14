package io.eezo.live

import io.eezo.core.html.Tags.*
import io.eezo.core.internal.Json

/** The frame the server writes, pinned: the client's `JSON.parse` and the harness both read this
  * exact shape, so a renaming here is a breaking change and should look like one in a diff.
  */
class WireSuite extends munit.FunSuite {

  test("a patches frame carries kind, op, path, expect and html") {
    val frame = Wire.patches(Differ.diff(div("old"), div("new")))
    assertEquals(
      Json.parse(frame),
      Right(
        Json.Obj(
          List(
            "kind"    -> Json.Str("patches"),
            "patches" -> Json.Arr(
              List(
                Json.Obj(
                  List(
                    "op"     -> Json.Str("setChildren"),
                    "path"   -> Json.Arr(Nil),
                    "expect" -> Json.Null,
                    "html"   -> Json.Str("<div>new</div>")
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  test("no difference is an empty patch list, not an absent frame") {
    assertEquals(
      Json.parse(Wire.patches(Nil)),
      Right(Json.Obj(List("kind" -> Json.Str("patches"), "patches" -> Json.Arr(Nil))))
    )
  }

  test("markup in the payload survives the JSON round trip byte for byte") {
    val tree  = div(p("""a<b & "c""""), span("it's"))
    val frame = Wire.patches(Differ.diff(div("x"), tree))
    Json.parse(frame) match {
      case Right(Json.Obj(fields)) =>
        val Some(Json.Arr(List(Json.Obj(patch)))) = fields.toMap.get("patches"): @unchecked
        assertEquals(patch.toMap.get("html"), Some(Json.Str(tree.render)))
      case other => fail(s"not an object: $other")
    }
  }
}
