package example

import io.eezo.db.*

case class Author(
    id: Id[Author],
    name: String,
    country: Option[String],
    mentor: Option[Ref[Author]]
) derives Table
