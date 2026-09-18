package io.eezo.auth

import java.nio.charset.StandardCharsets

import io.eezo.http.Field

class PasswordSuite extends munit.FunSuite {

  test("a hash verifies the text it was made from and refuses any other") {
    val stored = Password.hash(Password.Plain("correct horse battery staple"))
    assert(stored.verify(Password.Plain("correct horse battery staple")))
    assert(!stored.verify(Password.Plain("correct horse battery stapl")))
  }

  test("a hash names its own version and cost in its prefix") {
    assert(Password.hash(Password.Plain("whatever")).value.startsWith("$2b$12$"))
  }

  test("two hashes of one text differ, because each carries its own salt") {
    val one = Password.hash(Password.Plain("same text"))
    val two = Password.hash(Password.Plain("same text"))
    assertNotEquals(one.value, two.value)
    assert(one.verify(Password.Plain("same text")))
    assert(two.verify(Password.Plain("same text")))
  }

  test("a stored hash forms as a password box that shows nothing") {
    val field = Field[Password]
    assertEquals(field.inputType, "password")
    assertEquals(field.show(Password.hash(Password.Plain("secret"))), "")
    assertEquals(field.show(Password.stored("$2b$04$abcdefghijklmnopqrstuv")), "")
  }

  test("reading a password hashes it, so nothing between browser and model holds the text") {
    val read = Field[Password].read("hunter2")
    assert(clue(read).isRight)
    val stored = read.toOption.get
    assert(stored.verify(Password.Plain("hunter2")))
    assertNotEquals(stored.value, "hunter2")
  }

  test("an empty password is required, not a hash of nothing") {
    assertEquals(Field[Password].read(""), Left("is required"))
  }

  test("the 72 byte cap is counted in UTF-8 bytes, and names itself when it refuses") {
    val seventyTwo = "e" * 72
    assertEquals(seventyTwo.getBytes(StandardCharsets.UTF_8).length, 72)
    assert(Field[Password].read(seventyTwo).isRight)

    val seventyThree = "e" * 73
    assertEquals(seventyThree.getBytes(StandardCharsets.UTF_8).length, 73)
    assertEquals(
      Field[Password].read(seventyThree),
      Left("is longer than the 72 bytes bcrypt reads")
    )
  }

  test("a text of 73 UTF-8 bytes is refused even though it is 25 characters") {
    // Three bytes each in UTF-8, so `String.length` says 25 and bcrypt sees 75.
    val emoji = "世" * 25
    assertEquals(emoji.length, 25)
    assertEquals(emoji.getBytes(StandardCharsets.UTF_8).length, 75)
    assert(Field[Password].read(emoji).isLeft)
  }

  test("a plain password forms as a password box, shows nothing and reads back unhashed") {
    val field = Field[Password.Plain]
    assertEquals(field.inputType, "password")
    assertEquals(field.show(Password.Plain("secret")), "")
    assertEquals(field.read(""), Left("is required"))

    val read = field.read("hunter2")
    assert(clue(read).isRight)
    // Unhashed: the stored hash of the same text accepts what `read` produced.
    assert(Password.hash(Password.Plain("hunter2")).verify(read.toOption.get))
  }

  test("a plain password prints stars, so interpolating one into a log says nothing") {
    assertEquals(Password.Plain("hunter2").toString, "****")
    assertEquals(s"${Password.Plain("hunter2")}", "****")
  }

  test("a hash already made is taken at its word, cost and all") {
    // Strength 4, precomputed, which is what every suite but this one seeds with.
    val cheap = Password.stored("$2b$04$6oCIgC4QzztRP0Q1ZTYV2.rK6diTucKojirfE2pCcbTXpbuFq6hju")
    assert(cheap.verify(Password.Plain("secret")))
    assert(!cheap.verify(Password.Plain("wrong")))
  }
}
