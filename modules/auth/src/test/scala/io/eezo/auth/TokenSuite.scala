package io.eezo.auth

/** The API token: a secret handed to a program once, and the hash an application keeps of it. */
class TokenSuite extends munit.FunSuite {

  test("hashing the plain text gen handed out again gives the token gen stored") {
    val (plain, stored) = Token.gen()
    assertEquals(Token.hash(plain), stored)
    assertEquals(Token.hash(plain).value, stored.value)
  }

  test("the stored value is not the plain text and does not contain it") {
    val (plain, stored) = Token.gen()
    assertNotEquals(stored.value, plain)
    assert(!stored.value.contains(plain))
  }

  test("a token goes into a column and comes back out as itself") {
    val (_, stored) = Token.gen()
    assertEquals(Token.stored(stored.value), stored)
  }

  test("two tokens share neither the plain text nor the hash") {
    val (onePlain, one) = Token.gen()
    val (twoPlain, two) = Token.gen()
    assertNotEquals(onePlain, twoPlain)
    assertNotEquals(one, two)
  }

  test("the plain text carries at least 128 bits and needs no escaping in a header") {
    val (plain, _) = Token.gen()
    // base64url without padding: six bits a character, so 22 characters is the 128 bit floor.
    assert(clue(plain).length >= 22)
    assert(clue(plain).forall(c => c.isLetterOrDigit && c < 128 || c == '-' || c == '_'))
  }
}
