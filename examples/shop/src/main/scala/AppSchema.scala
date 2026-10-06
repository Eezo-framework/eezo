import io.eezo.db.Schema

import models.{Order, Product, User}

object AppSchema extends Schema {
  val users    = table[User].unique(_.email)
  val products = table[Product]
  val orders   = table[Order]
}
