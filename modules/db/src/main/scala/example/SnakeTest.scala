package example

import io.eezo.db.util.*

object SnakeTest {
  def main(args: Array[String]) = {
    println(snake("Book"))       // book
    println(snake("BookReview")) // book_review
    println(snake("ISBN"))       // i_s_b_n     ← bad
    println(snake("HTTPLog"))    // h_t_t_p_log ← bad
    println(snake("bookISBN"))   // book_i_s_b_n ← bad
    println(snake("v2"))         // v2
    println(snake("Book2Movie")) // book2_movie? book2movie?
  }
}
