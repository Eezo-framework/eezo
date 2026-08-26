package example

import io.eezo.db.*
import io.eezo.core.Id

final case class PublishingHouse(id: Id[PublishingHouse], name: String, location: String)
    derives Table
