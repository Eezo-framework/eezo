package example

import java.time.*
import io.eezo.db.*

case class Book(
    id: Id[Book],
    author: Ref[Author],
    title: Title,
    publishedOn: Option[LocalDate], // TODO maybe add the index here in the type
    isbn: Option[String],
    publishedBy: Option[Ref[PublishingHouse]],
    format: String
) derives Table

object Book {
  val seedBooks = List(
    Book(
      Id.gen(),
      Ref.to(Author.herbert.id),
      Title("Dune"),
      Some(LocalDate.of(1965, 8, 1)),
      None,
      None,
      "paperback"
    ),
    Book(
      Id.gen(),
      Ref.to(Author.herbert.id),
      Title("Dune Messiah"),
      Some(LocalDate.of(1969, 1, 1)),
      None,
      None,
      "paperback"
    ),
    Book(Id.gen(), Ref.to(Author.student.id), Title("Untitled"), None, None, None, "paperback")
  )
}
