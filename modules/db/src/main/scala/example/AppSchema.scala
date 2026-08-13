package example

import io.eezo.db.*

object AppSchema extends Schema {
  val authors = table[Author]
  // val books   = table[Book].index("author_id", "published_on").unique("title")
  val books = table[Volume].index("author_id", "published_on").unique("title")
}
