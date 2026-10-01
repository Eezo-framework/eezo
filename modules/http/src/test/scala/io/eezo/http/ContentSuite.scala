package io.eezo.http

import io.eezo.http.client.{Content, Http}

/** Outside the client package on purpose: inside it the constructor is reachable, so only from here
  * can a test see that an application has no way to make a Content but an encoder.
  */
class ContentSuite extends munit.FunSuite {

  private def refused(errors: String, because: String): Unit =
    assert(errors.contains(because), s"expected '$because', got: $errors")

  test("an application cannot build a Content, only get one from an encoder") {
    val content: Content = Http.form("a" -> "1")
    assertEquals(content.contentType, "application/x-www-form-urlencoded")
    refused(compileErrors("""Content("x", Array.emptyByteArray)"""), "does not take parameters")
    refused(
      compileErrors("""new Content("x", Array.emptyByteArray)"""),
      "constructor Content cannot be accessed"
    )
    refused(compileErrors("""content.copy(contentType = "x")"""), "method copy cannot be accessed")
  }
}
