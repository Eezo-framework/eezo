package example

import java.time.*
import io.eezo.db.*

case class Volume(
    id: Id[Volume],
    author: Ref[Author],
    title: Title,
    publishedOn: Option[LocalDate],
    isbn: Option[String],
    publishedBy: Option[Ref[PublishingHouse]]
) derives Table

object Volume {
  val seedVolumes = List(
    Volume(
      Id.gen(),
      Ref.to(Author.herbert.id),
      Title("Dune"),
      Some(LocalDate.of(1965, 8, 1)),
      None,
      None
    ),
    Volume(
      Id.gen(),
      Ref.to(Author.herbert.id),
      Title("Dune Messiah"),
      Some(LocalDate.of(1969, 1, 1)),
      None,
      None
    ),
    Volume(Id.gen(), Ref.to(Author.student.id), Title("Untitled"), None, None, None)
  )
}
