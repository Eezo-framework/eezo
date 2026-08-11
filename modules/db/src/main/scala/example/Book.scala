package example

import java.time.*
import io.eezo.db.*

case class Book(
    id: Id[Book],
    title: String,
    author: String,
    publishedOn: Option[LocalDate]
) derives Table
