package example

import io.eezo.db.*

object AppSchema extends Schema {
  val authors = table[Author]
  // val books   = table[Book].index("author_id", "published_on", "published_by_id").unique("title")
  val houses  = table[PublishingHouse]
  val volumes = table[Volume].index("author_id", "published_on", "published_by_id").unique("title")
}

// TODO weird that I have to declare them here - why not generate a given Table[T] in each T's companion
// TODO index shouldn't take strings but lambdas which can be mapped to column names (nasty especially in fk relations)
