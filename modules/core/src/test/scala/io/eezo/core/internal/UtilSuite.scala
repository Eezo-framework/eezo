package io.eezo.core.internal

class UtilSuite extends munit.FunSuite {
  import util.*

  test("snake: a capital starts a word when it follows a lowercase letter") {
    assertEquals(snake("Book"), "book")
    assertEquals(snake("BookReview"), "book_review")
    assertEquals(snake("publishedOn"), "published_on")
    assertEquals(snake("PublishingHouse"), "publishing_house")
  }

  test(
    "snake: an acronym run stays one word, and its last capital starts a word before lowercase"
  ) {
    assertEquals(snake("ISBN"), "isbn")
    assertEquals(snake("bookISBN"), "book_isbn")
    assertEquals(snake("HTTPLog"), "http_log")
  }

  test("snake: a digit ends a word") {
    assertEquals(snake("v2"), "v2")
    assertEquals(snake("Book2Movie"), "book2_movie")
  }
}
