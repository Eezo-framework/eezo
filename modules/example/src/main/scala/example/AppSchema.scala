package example

import io.eezo.db.*

object AppSchema extends Schema {
  val authors = table[Author]
  val books   = table[Book].index(_.author, _.publishedOn, _.publishedBy).unique(_.title)
  val houses  = table[PublishingHouse]
}
