package example

import io.eezo.db.*
import io.eezo.core.Id

case class Author(
    id: Id[Author],
    name: String,
    country: Option[String],
    mentor: Option[Ref[Author]]
) derives Table

object Author {
  val herbert = Author(Id.gen(), "Frank Herbert", Some("US"), None)
  val student = Author(Id.gen(), "Kevin Anderson", Some("US"), Some(Ref.to(herbert.id)))
}
