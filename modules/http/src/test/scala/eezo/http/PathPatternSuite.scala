package eezo.http

/** Path patterns: three segment kinds, two fatal validations, and the way back to a string.
  */
class PathPatternSuite extends munit.FunSuite {

  test("a static pattern matches itself and nothing else") {
    val pattern = PathPattern.parse("/widgets")
    assertEquals(pattern.matchPath("/widgets"), Some(Map.empty[String, String]))
    assertEquals(pattern.matchPath("/widget"), None)
    assertEquals(pattern.matchPath("/widgets/1"), None)
  }

  test("the root pattern matches the root path") {
    assertEquals(PathPattern.parse("/").matchPath("/"), Some(Map.empty[String, String]))
  }

  test(":name captures exactly one segment") {
    val pattern = PathPattern.parse("/widgets/:id")
    assertEquals(pattern.matchPath("/widgets/7"), Some(Map("id" -> "7")))
    assertEquals(pattern.matchPath("/widgets/7/edit"), None)
    assertEquals(pattern.matchPath("/widgets"), None)
  }

  test("*name captures the rest of the path, including nothing at all") {
    val pattern = PathPattern.parse("/files/*path")
    assertEquals(pattern.matchPath("/files/a/b.txt"), Some(Map("path" -> "a/b.txt")))
    assertEquals(pattern.matchPath("/files/"), Some(Map("path" -> "")))
    assertEquals(pattern.matchPath("/files"), Some(Map("path" -> "")))
  }

  test("a trailing slash is normalised away on both sides") {
    assertEquals(
      PathPattern.parse("/widgets/").matchPath("/widgets"),
      Some(Map.empty[String, String])
    )
    assertEquals(
      PathPattern.parse("/widgets").matchPath("/widgets/"),
      Some(Map.empty[String, String])
    )
  }

  test("a duplicate parameter name is rejected at parse time") {
    val failure = intercept[IllegalArgumentException](PathPattern.parse("/a/:id/b/:id"))
    assert(clue(failure.getMessage).contains("id"))
  }

  test("a catch-all anywhere but the final segment is rejected at parse time") {
    val failure = intercept[IllegalArgumentException](PathPattern.parse("/files/*rest/name"))
    assert(clue(failure.getMessage).contains("*rest"))
  }

  test("render is the way back to the source string") {
    assertEquals(PathPattern.parse("/widgets/:id/edit").render, "/widgets/:id/edit")
    assertEquals(PathPattern.parse("/files/*path").render, "/files/*path")
    assertEquals(PathPattern.parse("/").render, "/")
  }

  test("two patterns with the same segments are equal, so a duplicate route is detectable") {
    assertEquals(PathPattern.parse("/widgets/:id"), PathPattern.parse("/widgets/:id/"))
    assertNotEquals(PathPattern.parse("/widgets/:id"), PathPattern.parse("/widgets/:key"))
  }
}
