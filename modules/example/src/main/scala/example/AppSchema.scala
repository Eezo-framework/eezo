package example

import io.eezo.db.*

object AppSchema extends Schema {
  val authors = table[Author]
  val books   = table[Book].index(_.author, _.publishedOn, _.publishedBy).unique(_.title)
  val houses  = table[PublishingHouse]
}

// TODO weird that I have to declare them here - why not generate a given Table[T] in each T's companion
// TODO index shouldn't take strings but lambdas which can be mapped to column names (nasty especially in fk relations)
