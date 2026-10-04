package models

import java.time.Instant

import io.eezo.core.Id
import io.eezo.db.Table

case class Order(
    id: Id[Order],
    product: Id[Product],
    name: String,
    price: Int,
    paid: Boolean,
    at: Instant
) derives Table
