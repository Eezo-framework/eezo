package elsewhere

/** Written from a package outside `io.eezo` on purpose, for the reason `MiBVisibilitySuite` gives:
  * that is where an application lives, and the API route's doors that must stay the framework's own
  * (building an [[io.eezo.http.ApiRequest]], naming the API kind, calling the row that picks it)
  * can only be seen closed from here.
  *
  * Settled when this file compiles, and the incremental build never recompiles it for a change to a
  * name only a string mentions: after editing a modifier, trust a clean build only.
  */
class ApiVisibilitySuite extends munit.FunSuite {

  test("an application cannot build an ApiRequest out of a Request: only the generated row does") {
    val errors = compileErrors(
      "import io.eezo.http.*\n" +
        "val request = Request(Method.POST, \"/\", Map.empty, Map.empty, Array.emptyByteArray, Map.empty)\n" +
        "ApiRequest.of(request)"
    )
    assert(clue(errors).contains("cannot be accessed"), errors)
  }

  test("an application cannot name the API kind, so it cannot turn a browser route into one") {
    val named = compileErrors("io.eezo.http.RouteKind.Api")
    assert(clue(named).contains("cannot be accessed"), named)
    val built = compileErrors("new io.eezo.http.RouteKind(true)")
    assert(clue(built).contains("cannot be accessed"), built)
  }

  test("an application does not name the browser kind either: leaving the field out is enough") {
    val named = compileErrors("io.eezo.http.RouteKind.Browser")
    assert(clue(named).contains("cannot be accessed"), named)
    val built = compileErrors(
      "import io.eezo.http.*\n" +
        "Route.Http(Method.GET, PathPattern.parse(\"/\"), _ => Response.status(204))"
    )
    assertEquals(built, "")
  }

  test(
    "an application cannot reach the generated row that picks the kind from the handler's type"
  ) {
    val errors = compileErrors(
      "import io.eezo.http.*\n" +
        "Route.handwritten(Method.POST, \"/hook\", (_: ApiRequest) => Response.status(204))"
    )
    assert(clue(errors).contains("can only be accessed from package io.eezo"), errors)
  }
}
