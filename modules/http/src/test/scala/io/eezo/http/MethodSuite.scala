package io.eezo.http

/** The methods eezo models. `HEAD` and `OPTIONS` arrive whether eezo wants them or not; `TRACE` and
  * `CONNECT` are excluded and parse to nothing, which the boundary turns into a 501.
  */
class MethodSuite extends munit.FunSuite {

  test("the seven modelled methods parse, case insensitively") {
    assertEquals(Method.parse("GET"), Some(Method.GET))
    assertEquals(Method.parse("post"), Some(Method.POST))
    assertEquals(Method.parse("PUT"), Some(Method.PUT))
    assertEquals(Method.parse("DELETE"), Some(Method.DELETE))
    assertEquals(Method.parse("PATCH"), Some(Method.PATCH))
    assertEquals(Method.parse("HEAD"), Some(Method.HEAD))
    assertEquals(Method.parse("OPTIONS"), Some(Method.OPTIONS))
  }

  test("anything else parses to nothing") {
    assertEquals(Method.parse("TRACE"), None)
    assertEquals(Method.parse("CONNECT"), None)
    assertEquals(Method.parse("BREW"), None)
  }
}
