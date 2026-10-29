package io.eezo.core

class RedactedSuite extends munit.FunSuite {

  test("reveal gives back the wrapped text") {
    assertEquals(Redacted("whsec_1").reveal, "whsec_1")
  }

  test("the same text is equal with one hashCode, and different text is not equal") {
    assertEquals(Redacted("a"), Redacted("a"))
    assertEquals(Redacted("a").hashCode, Redacted("a").hashCode)
    assertNotEquals(Redacted("a"), Redacted("b"))
    assert(!Redacted("a").equals("a"))
  }

  test("the hashCode is not the text's own, which a short text can be read back from") {
    List("a", "ab", "sk_test").foreach(text =>
      assertNotEquals(Redacted(text).hashCode, text.hashCode, text)
    )
  }

  test("its Parser is found by implicit search and reads any text, the empty text included") {
    val p = summon[Parser[Redacted]]
    List("", " ", "short", "x" * 500).foreach(text =>
      assertEquals(p.read(text), Right(Redacted(text)))
    )
  }
}
