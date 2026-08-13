package example

import java.time.*
import io.eezo.db.*

case class Volume(
    id: Id[Volume],
    author: Ref[Author],
    title: Title,
    publishedOn: Option[LocalDate]
) derives Table
