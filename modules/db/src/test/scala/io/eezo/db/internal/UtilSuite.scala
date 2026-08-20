package io.eezo.db.internal

import munit.FunSuite

class UtilSuite extends FunSuite {
  import util.*

  test("snake: a capital starts a word when it follows a lowercase letter") {
    assertEquals(snake("Book"), "book")
    assertEquals(snake("BookReview"), "book_review")
    assertEquals(snake("publishedOn"), "published_on")
    assertEquals(snake("PublishingHouse"), "publishing_house")
  }

  test("snake: an acronym run stays one word") {
    // The naive rule — every capital starts a word — gives i_s_b_n and h_t_t_p_log.
    // `snake` splits before the last capital of a run only when a lowercase letter follows,
    // which is what makes HTTPLog break as http_log rather than http_l_og.
    assertEquals(snake("ISBN"), "isbn")
    assertEquals(snake("bookISBN"), "book_isbn")
    assertEquals(snake("HTTPLog"), "http_log")
  }

  test("snake: a digit ends a word") {
    assertEquals(snake("v2"), "v2")
    assertEquals(snake("Book2Movie"), "book2_movie")
  }

  test("canonicalCheck erases the differences Postgres is entitled to introduce") {
    // DESIGN §3.3: Postgres quotes only identifiers that need it and reformats freely, so
    // the comparison key drops quoting, case and spacing.
    assertEquals(canonicalCheck("""length("title") <= 200"""), "length(title) <= 200")
    assertEquals(canonicalCheck("LENGTH(title)   <=  200"), "length(title) <= 200")
    assertEquals(canonicalCheck("  length(title) <= 200  "), "length(title) <= 200")
  }
}
