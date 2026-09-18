import io.eezo.auth.Password

/** Pins the one property the operator's tool has to keep: a password that spring-security-crypto
  * would refuse is refused by eezo's own message, before that library ever sees the text.
  *
  * Reaches [[CreateUser.hashPassword]] directly rather than [[CreateUser.boot]], because `boot`
  * reads its input from `sys.env`, which a JVM already running cannot be made to change.
  */
class CreateUserSuite extends munit.FunSuite {

  test("a password over the 72 byte cap refuses with the field's message, not spring's") {
    val tooLong = "e" * 73
    val thrown  = intercept[IllegalStateException] {
      CreateUser.hashPassword(tooLong)
    }
    assertEquals(thrown.getMessage, "EEZO_BLOG_PASSWORD is longer than the 72 bytes bcrypt reads")
  }

  test("a password of exactly 72 UTF-8 bytes hashes rather than refuses") {
    val seventyTwo = "e" * 72
    val stored      = CreateUser.hashPassword(seventyTwo)
    assert(stored.verify(Password.Plain(seventyTwo)))
  }

  test("an empty password is required, not hashed as an empty string") {
    val thrown = intercept[IllegalStateException] {
      CreateUser.hashPassword("")
    }
    assertEquals(thrown.getMessage, "EEZO_BLOG_PASSWORD is required")
  }

  test("a password within the cap hashes to a value the field's own read would have produced") {
    val stored = CreateUser.hashPassword("correct horse battery staple")
    assert(stored.verify(Password.Plain("correct horse battery staple")))
    assert(!stored.verify(Password.Plain("wrong")))
  }
}
