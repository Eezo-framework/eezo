package example

import io.eezo.db.*

final case class PublishingHouse(id: Id[PublishingHouse], name: String, location: String)
    derives Table
